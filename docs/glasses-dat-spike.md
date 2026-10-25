# Fiche de référence — DAT (Meta Wearables Device Access Toolkit) pour le spike lunettes

> **Doc de référence pour l'intégration : [`META-GLASSES.md`](META-GLASSES.md).** Cette fiche
> reste le relevé d'API du spike ; ses « écarts » (§6) décrivent le scaffold `glasses-spike/`,
> pas l'app : le manifeste de `:app` (intent-filter `VIEW`/`BROWSABLE`, `BLUETOOTH_CONNECT`) et
> son `minSdk = 29` sont désormais conformes. En cas de divergence, `META-GLASSES.md` fait foi.

Sources : AAR réels `com.meta.wearable:mwdat-{core,camera,mockdevice}:1.0.1` (classes extraites dans
`/tmp/datcheck/*/classes.jar`), `javap` sur ces binaires, plus la doc DAT
(`wearables.developer.meta.com/docs/develop/dat/getting-started-toolkit/` et
`github.com/facebook/meta-wearables-dat-android` → `AGENTS.md`).
Chaque symbole ci-dessous est extrait du bytecode ou cité de la doc ; sinon « non vérifié ».
Le SDK n'expose **aucune** classe « publique » de camera hors `com.meta.wearable.dat.camera.*` :
`javap` a été fait sur `core.d`, `camera.d` et `mockdevice.d` (bytecode Java 11, `major=55`).

## 1. Ce que le bytecode prouve

### 1.1 Point d'entrée — `com.meta.wearable.dat.core.Wearables` (objet `Wearables.INSTANCE`)

| Signature exacte (`javap`) | Notes |
|---|---|
| `DatResult<Unit, WearablesError> initialize(Context)` | `WearablesError` = `NOT_INITIALIZED`, `ALREADY_INITIALIZED` |
| `void startRegistration(Activity)` | fire-and-forget ; passe par `RegistrationManager.askRegistration(activity, true)` |
| `DatResult<DeviceSession, DeviceSessionError> createSession(DeviceSelector)` | `DeviceSelector` = interface (`activeDevice()`, `activeDeviceFlow()`) ; impl. `AutoDeviceSelector`, `SpecificDeviceSelector` |
| `StateFlow<RegistrationState> getRegistrationState()` | `UNAVAILABLE`, `AVAILABLE`, `UNREGISTERING`, `REGISTERED`, `REGISTERING` |
| `Flow<RegistrationError> getRegistrationErrorStream()` | `ALREADY_REGISTERED`, `FAILED_TO_REGISTER`, `META_AI_NOT_INSTALLED`, `UNKNOWN`, … |
| `StateFlow<Set<DeviceIdentifier>> getDevices()` / `Map<DeviceIdentifier, StateFlow<Device>> getDevicesMetadata()` | |
| `Object checkPermissionStatus(Permission, Continuation)` | suspend → `DatResult<PermissionStatus, PermissionError>` |
| `DatResult<Boolean, RegistrationRequestError> handleIntent(Intent, Function1<RegistrationRequest, Unit>)` | à appeler sur l'intent de deep-link |
| `boolean isDevMode()` / `void reset()` | |

`Permission` = enum `CAMERA`, `MICROPHONE` ; `PermissionStatus` = `PermissionStatus.Granted` / `PermissionStatus.Denied`.

### 1.2 Session — `com.meta.wearable.dat.core.session.DeviceSession`

- `StateFlow<DeviceSessionState> getState()` → `IDLE`, `STARTING`, `STARTED`, `PAUSED`, `STOPPING`, `STOPPED`.
- `SharedFlow<DeviceSessionError> getErrors()` ; `void start()` ; `void stop()` (les deux `Unit`).
- `addCamera` **n'est pas une méthode de session** : c'est une extension Kotlin du module camera,
  `com.meta.wearable.dat.camera.addCamera` (classe `SessionCameraExtensionsKt`) :
  `DatResult<Camera, DeviceSessionError> addCamera(DeviceSession, StreamConfiguration)`
  (le second paramètre a une valeur par défaut → `addCamera(session)` existe) et
  `DatResult<Unit, DeviceSessionError> removeCamera(DeviceSession)`.

### 1.3 Caméra — `com.meta.wearable.dat.camera`

- `Camera` (interface) : `StateFlow<CameraState> getState()` (`STARTING`, `STARTED`, `STOPPING`, `STOPPED`),
  `Stream getStream()`, `Photo getPhoto()`, `void stop()`.
- `Stream` (interface) : `StateFlow<StreamState> getState()` (`STOPPED`, `STARTING`, `STARTED`, `STREAMING`,
  `STOPPING`, `PAUSED`, `CLOSED`), `Flow<VideoFrame> getVideoStream()`, `Flow<AudioFrame> getAudioStream()`,
  `Flow<StreamError> getErrorStream()`, `DatResult<Unit, StreamError> start()`,
  `DatResult<PhotoData, CaptureError> capturePhoto()` (suspend), `void stop()`.
- `StreamConfiguration(audioCodec: AudioCodec? = null, videoQuality: VideoQuality = MEDIUM, frameRate: Int = 24, compressVideo: Boolean = false)`
  — defaults lus dans le constructeur synthétique (`$default` mask) de la classe.
- `VideoQuality` : `HIGH` = 720×1280, `MEDIUM` = 504×896, `LOW` = 360×640
  (constantes dans `StreamImpl.getVideoResolution`).
- Frame rates acceptés : `2, 7, 15, 24, 30` (`VideoFormat.SUPPPORTED_FRAME_RATES` = `[30, 24, 15, 7, 2]` ;
  `getFrameRate()` borne la valeur entre 2 et 30 puis l'arrondit sur cette liste).

### 1.4 `VideoFrame` — le point sensible

```
public final class com.meta.wearable.dat.camera.types.VideoFrame {
  VideoFrame(java.nio.ByteBuffer, int width, int height, long presentationTimeUs,
            boolean isCompressed, boolean isCodecConfig)
  java.nio.ByteBuffer getBuffer();  int getWidth();  int getHeight();
  long getPresentationTimeUs();     boolean isCompressed();  boolean isCodecConfig();
}
```

Le buffer est un **`java.nio.ByteBuffer`**, jamais un `Bitmap` ni un `ByteArray`.
YUV ou compressé dépend de `compressVideo`, prouvé dans `StreamImpl.configureCoordinator` :

- `compressVideo = false` (défaut) → `registerToReceiveDecodedVideo(true)` → le SDK décode le H.265
  **sur le téléphone** et publie `VideoFrame(buffer, w, h, pts, isCompressed = false, isCodecConfig = false)`
  (voir `StreamImpl$videoEventListener$2$1.decodedVideoFrameReceived`). Le buffer contient donc des
  **pixels YUV bruts** ; largeur/hauteur = dimensions annoncées par le décodeur.
  Format exact (I420 vs NV12 semi-planaire) : **non vérifié** — le SDK ne l'expose pas.
- `compressVideo = true` → `registerToReceiveEncodedVideo(true)` → `VideoFrame(buffer, w, h, pts, isCompressed = true, isCodecConfig = <flag>)`,
  buffer = **flux H.265** à décoder soi-même ; `isCodecConfig` signale la trame de configuration.
- `PhotoData` : type scellé → `PhotoData.Bitmap(bitmap: android.graphics.Bitmap)` ou `PhotoData.HEIC(data: ByteBuffer)`.

## 2. Squelette d'appel minimal (vérifié)

```kotlin
// 1. initialize — une seule fois, avant tout appel DAT
Wearables.initialize(context).onFailure { error, _ -> log(error.description) }

// 2. registration (Developer Mode : placeholders APPLICATION_ID/CLIENT_TOKEN = "0")
Wearables.startRegistration(activity)                       // Unit
lifecycleScope.launch { Wearables.registrationState.collect { /* … REGISTERED */ } }

// 3. session
Wearables.createSession(AutoDeviceSelector()).fold(
    onSuccess = { session ->
        lifecycleScope.launch { session.errors.collect { e -> log(e.description) } }
        lifecycleScope.launch {
            session.state.collect { state ->
                if (state == DeviceSessionState.STARTED && camera == null) {
                    // 4. caméra — SEULEMENT une fois STARTED, sinon SESSION_IDLE
                    session.addCamera(
                        StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 24),
                    ).fold(
                        onSuccess = { cam ->
                            camera = cam
                            cam.stream.start().onFailure { e, _ -> log(e.description) }
                            // 5. frames
                            lifecycleScope.launch {
                                cam.stream.videoStream.collect { frame ->
                                    // frame.buffer : ByteBuffer (YUV par défaut, H.265 si compressVideo)
                                    // frame.width/height, frame.presentationTimeUs
                                    // frame.isCompressed, frame.isCodecConfig
                                }
                            }
                        },
                        onFailure = { e, _ -> log(e.description) },
                    )
                }
            }
        }
        session.start()   // fire-and-forget : Unit, les erreurs sortent par session.errors
    },
    onFailure = { e, _ -> log(e.description) },
)
```

Import obligatoire pour l'extension : `com.meta.wearable.dat.camera.addCamera`.

## 3. Erreurs typées à gérer

- `DatResult<T, E : DatError>` : `isSuccess()`, `isFailure()`, `getOrNull()`, `errorOrNull()`,
  `onSuccess { }`, `onFailure { error, cause -> }` (2 args), `fold(onSuccess, onFailure)`,
  `onSpecificError<E> { }`, `getOrElse { throwable -> }` (**reçoit une `Throwable`, pas un `DatError`**),
  `getOrThrow()` (à éviter en prod).
- `DatError` : `getDescription(): String` et `getLocalizedDescription(Context): String`.
- `DeviceSessionError` : `SESSION_IDLE` (capacité ajoutée trop tôt), `SESSION_ALREADY_STOPPED`,
  `NO_ELIGIBLE_DEVICE`, `CAPABILITY_DENIED`, `CAPABILITY_ALREADY_ADDED`, `CAPABILITY_NOT_FOUND`,
  `DEVICE_DISCONNECTED`, `SESSION_ENDED_BY_DEVICE`, `THERMAL_CRITICAL`, `THERMAL_EMERGENCY`,
  `PEAK_POWER_SHUTDOWN`, `BATTERY_CRITICAL`, `DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED`,
  `DWA_UNAVAILABLE`, `INSUFFICIENT_SDK_VERSION`, `UNEXPECTED_ERROR`.
- `StreamError` : `STREAM_ERROR` (informatif, le flux continue), `CRITICAL_STREAM_ERROR`
  (→ démonter le flux), `HINGE_CLOSED`, `PERMISSIONS_DENIED`, `THERMAL_HOT`, `BATTERY_LOW`,
  `PEAK_POWER_LIMIT`, `TIMEOUT`.
- `PermissionError` : `NO_DEVICE`, `NO_DEVICE_WITH_CONNECTION`, `META_AI_NOT_INSTALLED`,
  `CONNECTION_ERROR`, `REQUEST_IN_PROGRESS`, `REQUEST_TIMEOUT`, `INTERNAL_ERROR`.
- `CaptureError` : `CaptureInProgress` (une seule capture à la fois), `CaptureFailed`,
  `NotStreaming`, `DeviceDisconnected`.

## 4. Prérequis

- **Developer Mode** activé dans l'app Meta AI (sinon l'attestation est exigée) ; placeholders
  `mwdat_application_id` / `mwdat_client_token` = `"0"` acceptés uniquement en Developer Mode.
- **Android 10+ (minSdk 29)** : les trois AAR déclarent `android:minSdkVersion="29"` ;
  permissions `BLUETOOTH` et `BLUETOOTH_CONNECT` (runtime sur API 31+).
- **MockDeviceKit** (`mwdat-mockdevice`) : `MockDeviceKit.getInstance(context)`, `enable()`,
  `pairGlasses(GlassesModel.RAYBAN_META): DatResult<MockGlasses, MockDeviceKitError>`,
  `permissions.set(Permission.CAMERA, PermissionStatus.Denied)`,
  `glasses.services.camera.setCameraFeed(Uri)` ou `setCameraFeed(CameraFacing.FRONT)` (feed = caméra du
  téléphone ⇒ permission `android.permission.CAMERA` runtime requise).
- **Vidéos mock en h.265** : Android ne transcode pas ; un `.mp4` h.264 sera rejeté par le mock.
- `Wearables.initialize(context)` avant tout autre appel ; `addCamera` seulement à `STARTED`.

## 5. Ce que le mock ne prouve PAS

- **Latence Bluetooth** réelle, débit, pertes de paquets, reprise de session (`DEVICE_DISCONNECTED`).
- **Optique** : pas de champ de vision, de distorsion, d'exposition, de rolling shutter ; la résolution
  annoncée (504×896 en MEDIUM) est un format de flux, pas une image réelle.
- **Audio vers les lunettes** : le mock ne fait pas de routage Bluetooth SCO/A2DP ; la voix SAM reste
  sur le haut-parleur du téléphone. Le retour micro des lunettes n'est pas simulé non plus.
- **Permissions réelles** de l'app Meta AI, app DAT sur les lunettes, versions firmware, mise à jour
  de l'app Meta (`DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED`).
- **Autonomie/thermique/batterie** réels, hinge folding, et l'UX du stream en port (`PAUSED` sur cap-touch).
- Le mock ne garantit pas la **détermination** des frames PCM audio (« Do not assume MockDeviceKit
  emits deterministic PCM frames », doc).
## 6. Écarts constatés dans notre scaffold (`glasses-spike/`)

Lu, **non modifié**. Priorité décroissante :

1. **`AndroidManifest.xml` : il manque `<intent-filter>` `VIEW`/`BROWSABLE` + `<data android:scheme="…"/>`
   sur `MainActivity`.** La doc DAT (AGENTS.md, § Step 2) l'exige pour les callbacks d'enregistrement ;
   `Wearables.handleIntent(...)` n'a donc rien à recevoir et l'enregistrement ne peut pas boucler.
   Le scaffold ne déclare que `MAIN`/`LAUNCHER`.
2. **`AndroidManifest.xml` : `android.permission.MODIFY_AUDIO_SETTINGS` absent**, alors que
   `GlassesVoice.kt` (l. 28 et l. 207) dit lui-même que le routage Bluetooth l'exige et que son absence
   fait retomber silencieusement sur le haut-parleur. Déclaration absente ⇒ `SecurityException` à l'appel.
3. **Aucune demande de permission runtime** dans `glasses-spike/` (ni `BLUETOOTH_CONNECT` — runtime
   sur API 31+ — ni `CAMERA`, pourtant requise par `setCameraFeed(CameraFacing)`). Le SDK ne la fait pas
   lui-même (aucune occurrence de `requestPermissions` dans `mwdat-core`).
4. **Permissions redondantes** : `BLUETOOTH` / `BLUETOOTH_CONNECT` sont déjà fusionnés par les AAR ;
   `RECORD_AUDIO` n'est utile que pour la reconnaissance live du mock (doc) et n'est jamais demandé.
   `INTERNET` figure dans l'exemple de manifest de la doc DAT mais n'est déclaré par aucun des trois AAR.
5. **Meta-data : corrects.** Les clés lues par le SDK (`DatConfiguration`, chaînes extraites du bytecode)
   sont `com.meta.wearable.mwdat.APPLICATION_ID` et `com.meta.wearable.mwdat.CLIENT_TOKEN` — exactement
   celles du manifest ; les placeholders sont bien définis à `"0"` dans `build.gradle.kts`.
   Optionnels non déclarés : `…mwdat.TOOLS_ENABLED`, `…mwdat.ANALYTICS_OPT_OUT`,
   `…mwdat.CRASH_REPORTING_OPT_OUT`.
6. **`build.gradle.kts` : correct sur le fond** — `minSdk = 29` (aligné sur les AAR), `compileSdk = 36`,
   `jvmTarget = 17` (les AAR sont en bytecode 11, donc compatibles), `mavenCentral()` déjà présent dans
   `settings.gradle.kts`, `mwdat = "1.0.1"` dans `libs.versions.toml`.
   Seule remarque : `implementation(libs.mwdat.mockdevice)` est livré dans l'APK de debug (TODO déjà noté
   dans le fichier) et `implementation(project(":core"))` n'est pas nécessaire à l'étape DAT.
7. **`GlassesApp.onCreate` appelle `Wearables.initialize(this)` sans vérifier le retour
   `NOT_INITIALIZED`/`ALREADY_INITIALIZED`**, et rien n'observe `Wearables.registrationState` : sans ça
   `createSession` part avant l'enregistrement.
