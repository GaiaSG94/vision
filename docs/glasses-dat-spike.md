# Fiche de référence — DAT (Meta Wearables Device Access Toolkit) pour le spike lunettes

> **Doc d'intégration de l'app : [`META-GLASSES.md`](META-GLASSES.md).** Cette fiche est le
> **relevé d'API** fait pour le module `glasses-spike/` et le détail de ce qui manque à ce
> module. Elle ne décrit **pas** ce que fait `:app` aujourd'hui (voir §0).
>
> Relecture **25/10/2026** dans le worktree `sam-wt-docs-app` (base `origin/master`, HEAD
> `8f9876a`). Statut des preuves :
> - §1 à §5 = relevé fait le **20/10/2026** sur le bytecode extrait des AAR dans
>   `/tmp/datcheck/*/classes.jar` (`javap`) + doc DAT. **Cette extraction n'existe plus ici**
>   (mesuré : `ls /tmp/datcheck` → absent) et rejouer `javap` demanderait de télécharger et
>   dépaqueter les artefacts, ce que cette mission interdit. Ces symboles sont donc
>   **pertinents au 20/10 et non revérifiés** ; ils ne sont contredits par aucun appel du code
>   actuel.
> - §0, §6, §7 = relu dans l'arbre ce 25/10, avec `fichier:ligne`.

## 0. Deux codes, deux responsabilités (relu 25/10)

Le spike est un **module séparé et vivant** : `include(":glasses-spike")` dans
`settings.gradle.kts`, package `dev.sam3vision.glasses`, 9 fichiers `.kt` (7 `main`, 1 `test`,
1 `androidTest`), compilé par la CI (`docs/glasses-spike-ci.md`). `:app` a son propre paquetage
`dev.sam3vision.app.glasses`, entré sur `master` par `f98595e`. Ils **ne partagent pas** les
choix de flux :

| | Spike `glasses-spike/` | App `app/…/glasses/` |
|---|---|---|
| `frameRate` | **24** (`GlassesSession.kt:214-216`, `FRAME_RATE` ligne 337) | **15** (`GlassesCamera.kt:43-48`, `:80`) |
| `compressVideo` | défaut `false` → YUV décodé sur le téléphone (`StreamConfiguration()` nu, `MockGlassesTest.kt:91`) | explicite `true` → trames compressées décodées par `BitmapFactory` (`GlassesCamera.kt:47`, `GlassesFrames.kt:26`) |
| Décodage | convertisseur YUV420 écrit pour lui (`FrameTypes.kt:30-154`, test JVM `YuvConverterTest.kt`, 144 l.) | aucun : `isCompressed` passe au décodeur JPEG (`GlassesFrames.kt:22-37`) |
| Étranglement | 1 frame / ~1,5 s (`FRAME_THROTTLE_MS = 1_500L`, `GlassesSession.kt:340`) | photo à la demande (`capturePhoto()`), pas de flux consommé en continu |
| Audio | routage SCO vers les lunettes, spike uniquement (`GlassesVoice.kt:197-204`) | aucun : `audioCodec = null` (`GlassesCamera.kt:44`), TTS sur la sortie du téléphone |
| Clés / endpoint | `BuildConfig` uniquement, **pas de store de réglages** : `Settings(...)` construit à la volée, et le SAM vient de `SAM31_ENDPOINT` (`MainActivity.kt:168-181`) | store chiffré + `seededFromBuild` ; endpoint = résolution de région (`docs/MODE-US.md`) |
| UI | un écran, un journal, trois boutons (`MainActivity.kt:42`) | bloc « Lunettes » des Réglages (`GlassesSection.kt`) |
| Mock | `MockGlassesHarness` (vidéo `.mp4` en asset d'androidTest, ou caméra arrière du téléphone, `MockGlassesHarness.kt:85`, `:107`) | `GlassesMock` (scène synthétique 640×480, `GlassesMock.kt:58-90`) |
| `:core` | dépendance `implementation(project(":core"))` — utilisé pour `AnalyzeUseCase`, `HttpSamClient`, `InferHubLlmClient`, `ElevenLabsTtsClient` | idem |

Conséquence de lecture : **une conclusion prise sur le spike ne s'applique pas à l'app**
(24 i/s et YUV ne sont pas ce que l'app envoie), et l'inverse non plus.

## 1. Ce que le bytecode prouvait (constat 20/10, non revérifié)

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

`Permission` = enum `CAMERA`, `MICROPHONE` ; `PermissionStatus` = `Granted` / `Denied`.
Ces six derniers points sont **revérifiés par les sites d'appel** dans `:app` :
`Wearables.initialize` (`GlassesController.kt:64`), `startRegistration` (`:114`),
`startUnregistration` (`:119`), `handleIntent` avec callback de continuation (`:125`),
`createSession(AutoDeviceSelector())` (`:132`), `registrationState` (`:76`), `devices` /
`devicesMetadata` (`:84`, `:91`), `checkPermissionStatus(Permission.CAMERA)` (`:167`),
`isDevMode` (`:66`).

### 1.2 Session — `com.meta.wearable.dat.core.session.DeviceSession`

- `StateFlow<DeviceSessionState> getState()` → `IDLE`, `STARTING`, `STARTED`, `PAUSED`,
  `STOPPING`, `STOPPED` ; `SharedFlow<DeviceSessionError> getErrors()` ; `start()` ; `stop()`.
  Rejouable dans le code : mapping des six états vers l'UI à `GlassesController.kt:248-254`,
  `errors` collecté à `:144`.
- `addCamera` **n'est pas une méthode de session** : extension Kotlin du module camera,
  `com.meta.wearable.dat.camera.addCamera` (`SessionCameraExtensionsKt`), avec
  `DatResult<Camera, DeviceSessionError> addCamera(DeviceSession, StreamConfiguration)` (second
  paramètre par défaut → `addCamera(session)` existe) et `removeCamera(DeviceSession)`.
  Preuve d'usage : import `GlassesCamera.kt:6-7`, appels `:27` et `:73` ; côté spike,
  `GlassesSession.kt:8` et `MockGlassesTest.kt:10`.

### 1.3 Caméra — `com.meta.wearable.dat.camera`

- `Camera` : `getState()` (`STARTING`, `STARTED`, `STOPPING`, `STOPPED`), `getStream()`,
  `getPhoto()`, `stop()`.
- `Stream` : `getState()` (`STOPPED`, `STARTING`, `STARTED`, `STREAMING`, `STOPPING`, `PAUSED`,
  `CLOSED`), `getVideoStream()`, `getAudioStream()`, `getErrorStream()`, `start()`,
  `capturePhoto()` (suspend), `stop()`. Usage app : `GlassesCamera.kt:32` (`start`), `:52`
  (`state`, comparaison à `StreamState.STREAMING`), `:55` (`errorStream`), `:62`
  (`capturePhoto`), `:72` (`stop`). `getAudioStream()` n'est appelé **nulle part** dans l'app.
- `StreamConfiguration(audioCodec: AudioCodec? = null, videoQuality: VideoQuality = MEDIUM,
  frameRate: Int = 24, compressVideo: Boolean = false)` — défauts lus dans le constructeur
  synthétique le 20/10. Cohérent avec le code : le spike appelle `StreamConfiguration()` nu
  (`MockGlassesTest.kt:91`) et l'app passe les quatre paramètres nommés (`GlassesCamera.kt:43-48`).
- `VideoQuality` : `HIGH` = 720×1280, `MEDIUM` = 504×896, `LOW` = 360×640 (constantes
  `StreamImpl.getVideoResolution`). **Non revérifié** ; dimensions de flux, pas image optique.
- Cadences acceptées : `2, 7, 15, 24, 30` (`VideoFormat.SUPPPORTED_FRAME_RATES` =
  `[30, 24, 15, 7, 2]`, arrondi sur cette liste). **Non revérifié** ; 15 et 24 sont les deux
  valeurs réellement utilisées par nos codes.

### 1.4 `VideoFrame` — le point sensible

```
public final class com.meta.wearable.dat.camera.types.VideoFrame {
  VideoFrame(java.nio.ByteBuffer, int width, int height, long presentationTimeUs,
             boolean isCompressed, boolean isCodecConfig)
  java.nio.ByteBuffer getBuffer();  int getWidth();  int getHeight();
  long getPresentationTimeUs();     boolean isCompressed();  boolean isCodecConfig();
}
```

Le buffer est un **`java.nio.ByteBuffer`**, jamais un `Bitmap` ni un `ByteArray`. YUV ou
compressé selon `compressVideo`, prouvé le 20/10 dans `StreamImpl.configureCoordinator` :

- `compressVideo = false` (défaut, donc **le cas du spike**) → `registerToReceiveDecodedVideo(true)`
  : le SDK décode le H.265 **sur le téléphone** et publie des frames `isCompressed = false` ;
  le buffer contient des pixels **YUV bruts**. Format exact (I420 vs NV12 semi-planaire) :
  **non vérifié** — le SDK ne l'expose pas ; c'est pour ça que `FrameTypes.kt:134-154`
  *infère* la disposition depuis la taille du buffer plutôt que de la supposer.
- `compressVideo = true` (**le cas de `:app`**) → `registerToReceiveEncodedVideo(true)` :
  buffer = **flux H.265**, `isCodecConfig` signale la trame de configuration. Dans `:app`, ce
  chemin **n'est jamais emprunté** : aucun abonnement à `videoStream` (zéro occurrence dans
  `app/src`, mesuré), et `VideoFrame.toBitmapOrNull()` (`GlassesFrames.kt:22-37`, qui écarte les
  `isCodecConfig` puis appelle `BitmapFactory.decodeByteArray`) n'est appelé nulle part. Si ce
  code était branché, `decodeByteArray` ne saurait pas décoder du H.265 (il décode
  JPEG/PNG/WebP) : **piège à connaître avant de brancher**, jamais testé sur matériel.
- `PhotoData` : type scellé → `PhotoData.Bitmap(android.graphics.Bitmap)` ou
  `PhotoData.HEIC(ByteBuffer)` ; les deux branches sont traitées (`GlassesFrames.kt:40-46`).

## 2. Squelette d'appel minimal (vérifié le 20/10, conforme aux sites d'appel actuels)

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
Deux écarts assumés avec ce squelette, à lire avant de comparer :
`frameRate = 24` (choix du spike) vs `15` + `compressVideo = true` (choix de `:app`, §0) ;
et l'app n'abonne **pas** `videoStream` en continu — elle ne prend qu'une photo à la demande.

## 3. Erreurs typées à gérer (constat 20/10)

- `DatResult<T, E : DatError>` : `isSuccess()`, `isFailure()`, `getOrNull()`, `errorOrNull()`,
  `onSuccess { }`, `onFailure { error, cause -> }` (2 args), `fold(onSuccess, onFailure)`,
  `onSpecificError<E> { }`, `getOrElse { throwable -> }` (**reçoit une `Throwable`, pas un
  `DatError`**), `getOrThrow()` (à éviter en prod). Le code utilise `fold`, `onFailure`,
  `getOrNull` et, dans les tests du spike, `getOrThrow` (`MockGlassesTest.kt:84`, `:91`, `:93`).
- `DatError` : `getDescription(): String`, `getLocalizedDescription(Context): String` —
  l'affichage passe par `description` (`GlassesCamera.kt:32`, `GlassesController.kt:136`).
- `DeviceSessionError` : `SESSION_IDLE` (capacité ajoutée trop tôt), `SESSION_ALREADY_STOPPED`,
  `NO_ELIGIBLE_DEVICE`, `CAPABILITY_DENIED`, `CAPABILITY_ALREADY_ADDED`, `CAPABILITY_NOT_FOUND`,
  `DEVICE_DISCONNECTED`, `SESSION_ENDED_BY_DEVICE`, `THERMAL_CRITICAL`, `THERMAL_EMERGENCY`,
  `PEAK_POWER_SHUTDOWN`, `BATTERY_CRITICAL`, `DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED`,
  `DWA_UNAVAILABLE`, `INSUFFICIENT_SDK_VERSION`, `UNEXPECTED_ERROR`.
- `StreamError` : `STREAM_ERROR` (informatif, le flux continue), `CRITICAL_STREAM_ERROR`
  (→ démonter le flux), `HINGE_CLOSED`, `PERMISSIONS_DENIED`, `THERMAL_HOT`, `BATTERY_LOW`,
  `PEAK_POWER_LIMIT`, `TIMEOUT`. Traitement spike : `GlassesSession.kt:236`
  (`stream.errorStream`) → `onStreamError`.
- `PermissionError` : `NO_DEVICE`, `NO_DEVICE_WITH_CONNECTION`, `META_AI_NOT_INSTALLED`,
  `CONNECTION_ERROR`, `REQUEST_IN_PROGRESS`, `REQUEST_TIMEOUT`, `INTERNAL_ERROR`.
- `CaptureError` : `CaptureInProgress` (une seule capture à la fois), `CaptureFailed`,
  `NotStreaming`, `DeviceDisconnected`.

## 4. Prérequis (déclaration, état relu 25/10)

- **Developer Mode** activé dans l'app Meta AI (sinon l'attestation est exigée) ; placeholders
  `mwdat_application_id` / `mwdat_client_token` à `"0"` acceptés uniquement dans ce mode.
  Spike : codés à `"0"` (`glasses-spike/build.gradle.kts`, `manifestPlaceholders`) —
  `:app` : valeur `local.properties` avec défaut `"0"` (`app/build.gradle.kts:54-56`).
- **Android 10+ (minSdk 29)** : les AAR déclarent `android:minSdkVersion="29"` (relevé 20/10) ;
  le module pose `minSdk = 29` (relu), idem `:app`.
- **MockDeviceKit** (`mwdat-mockdevice`) : `MockDeviceKit.getInstance(context)`, `enable()`,
  `pairGlasses(GlassesModel.RAYBAN_META)`, `permissions.set(Permission.CAMERA, …)`,
  `services.camera.setCameraFeed(Uri)` ou `setCameraFeed(CameraFacing)` (dans le code :
  `MockGlassesHarness.kt:85` caméra arrière, `:107` fichier vidéo, `:137` appairage ; côté app,
  `GlassesMock.kt:31-36`). `setCameraFeed(CameraFacing)` requiert `android.permission.CAMERA`.
- **Vidéos mock en H.265** : Android ne transcode pas, un `.mp4` H.264 est rejeté par le mock —
  l'asset du spike s'appelle `mock_feed.mp4` (`MockGlassesTest.kt:132`) et le doc du harnais
  insiste sur le H.265 (`MockGlassesHarness.kt:97-101`). **Contenu du fichier non vérifié**
  (lecture binaire = hors mission).
- `Wearables.initialize` avant tout autre appel ; `addCamera` seulement à `STARTED`.

## 5. Ce que le mock ne prouve PAS

- **Latence Bluetooth** réelle, débit, pertes de paquets, reprise de session (`DEVICE_DISCONNECTED`).
- **Optique** : pas de champ de vision, de distorsion, d'exposition, de rolling shutter ; la
  résolution annoncée (504×896 en MEDIUM) est un format de flux, pas une image réelle.
- **Audio vers les lunettes** : le mock ne fait pas de routage Bluetooth SCO/A2DP ; la voix SAM
  reste sur le haut-parleur du téléphone. Le retour micro des lunettes n'est pas simulé non plus.
- **Permissions réelles** de l'app Meta AI, app DAT sur les lunettes, versions firmware, mise à
  jour de l'app Meta (`DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED`).
- **Autonomie/thermique/batterie** réels, hinge folding, et l'UX du flux en port (`PAUSED`).
- Le mock ne garantit pas la **détermination** des frames PCM audio (« Do not assume MockDeviceKit
  emits deterministic PCM frames », doc).
- Et, pour `:app` spécifiquement : le mock ne valide pas `compressVideo = true` — il ne simule
  pas le comportement du décodeur H.265 sur le téléphone (§1.4).

## 6. Écarts du scaffold — relecture 25/10 dans ce worktree

État de chacun des points relevés le 20/10, relu dans les fichiers actuels :

| # | Écart (20/10) | État (25/10, preuve) |
|---|---|---|
| 1 | `<intent-filter>` `VIEW`/`BROWSABLE` absent → `handleIntent` ne recevait rien | **Corrigé** : filtre `VIEW` + `DEFAULT` + `BROWSABLE`, schéma `samglasses` (`glasses-spike/src/main/AndroidManifest.xml:45-50`), et `handleIntent` branché sur `onCreate`/`onNewIntent` (`MainActivity.kt:195-199`). `:app` a son propre schéma `samvision` (`app/src/main/AndroidManifest.xml:48-54`) |
| 2 | `MODIFY_AUDIO_SETTINGS` absente alors que le routage l'exige | **Corrigée** dans le spike : déclarée (`AndroidManifest.xml:11-12`). ⚠️ Pas déclarée dans `:app`, qui n'a de toute façon aucun routage (§0) |
| 3 | Aucune demande de permission runtime | **Partiellement corrigé** : le spike demande `BLUETOOTH_CONNECT` (API 31+) et `CAMERA` (`MainActivity.kt:185-193`). `RECORD_AUDIO` reste **déclaré sans jamais être demandée** (`AndroidManifest.xml:10` ; la seule occurrence de `RECORD_AUDIO` du module est cette ligne — mesuré). Dans `:app`, `BLUETOOTH_CONNECT` n'est **jamais** demandée en runtime (voir `docs/META-GLASSES.md` §5) |
| 4 | Permissions redondantes (`BLUETOOTH`/`BLUETOOTH_CONNECT` déjà fusionnés par les AAR) | **Toujours là, sans conséquence** : le spike les redéclare (`:5-6`) ; `:app` ajoute `maxSdkVersion="30"` sur `BLUETOOTH` (`AndroidManifest.xml:10-12`), ce qui est la forme correcte. `INTERNET`, qu'aucun AAR ne déclare, l'est par les deux modules (`:4` de chaque manifeste) |
| 5 | Meta-data `APPLICATION_ID` / `CLIENT_TOKEN` | **Conforme** : mêmes clés dans les deux manifestes (`spike :25-30`, `app :32-37`), placeholders définis dans les `build.gradle.kts` respectifs |
| 6 | `build.gradle.kts` du spike | **Inchangé** : `minSdk = 29`, `compileSdk = 36`, `jvmTarget = 17`, `mavenCentral()` dans `settings.gradle.kts` (`RepositoriesMode.FAIL_ON_PROJECT_REPOS`), `mwdat = "1.0.1"` (`gradle/libs.versions.toml`). Le `TODO(spike)` sur `mockdevice` livré dans l'APK de debug **court toujours**, et `implementation(project(":core"))` est en fait utilisé (pipeline `AnalyzeUseCase`, §0) |
| 7 | `GlassesApp.onCreate` ignorait le retour d'`initialize`, rien n'observait `registrationState` | **Corrigé** : `onFailure` journalisé (`GlassesApp.kt:18-20`) ; `registrationState` collecté et pilote la machine à états (`GlassesSession.kt:37`, `:122`, `:133-145`) |

## 7. Taille des artefacts et coût CI

Aucun build n'a été relancé ici (interdiction de la mission) : **les durées restent des constats
du 24/10** consignés dans `ci.yml` et détaillées dans `docs/glasses-spike-ci.md` (job `ci` : 46 s
à chaud / 1 min 00 en CI ; spike : +18 à 33 s). Ce qui est **mesuré le 25/10** sur Maven Central
(`com.meta.wearable`, `mwdat = "1.0.1"` dans `gradle/libs.versions.toml`) :

| Artefact | Octets | Qui le prend |
|---|---|---|
| `mwdat-core-1.0.1.aar` | 8 979 777 | spike + app |
| `mwdat-camera-1.0.1.aar` | 784 799 | spike + app |
| `mwdat-mockdevice-1.0.1.aar` | 871 060 | spike + app |
| **somme des 3 du spike** | **10 635 636 ≈ 10,64 Mo** (10,14 MiB) | |
| `mwdat-inputs-1.0.1.aar` | 146 878 | **`:app` seulement** (`app/build.gradle.kts:130`) |
| **les 4 artefacts, côté `:app`** | **10 782 514 ≈ 10,78 Mo** | |

Débit : 8,98 Mo ÷ 0,53 s ≈ 16,9 Mo/s (**constat 24/10**, depuis le r630). Les « ~11 Mo de `.so`
à empaqueter dans l'APK » ne sont **pas vérifiables sans build** : à recontrôler au prochain run
qui le permet.

## 8. Ce qui bloque une reprise du spike

- **Un appareil** : `MockGlassesTest` est un test d'instrumentation (`androidTest`, avec asset
  vidéo) — il ne tourne pas dans la CI actuelle, qui ne fait que `assembleDebug` +
  `testDebugUnitTest` du module (`docs/glasses-spike-ci.md`).
- **Matériel réel** pour ce que le mock ne prouve pas (§5), et donc l'appairage + Developer Mode
  + firmware (voir la table des prérequis de `docs/META-GLASSES.md` §6, constat 20/10).
- **Disponibilité des artefacts** : ce n'est **pas** un bloqueur, ils sont sur Maven Central
  (§7) ; mais le SDK reste en *developer preview* et `FAIL_ON_PROJECT_REPOS` interdit tout dépôt
  ajouté par un module — une version hors Maven Central bloquerait la CI.
- **Le format de frame** : §1.4 — le spike consomme du YUV (format non exposé par le SDK) et
  l'app demande du compressé **sans jamais lire de trame**. Le jour où l'app voudra exploiter le
  flux, il faudra un vrai décodeur H.265 (`decodeByteArray` n'en décode pas) : c'est la première
  chose à trancher sur matériel.
