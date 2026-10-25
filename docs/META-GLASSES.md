# Lunettes Ray-Ban Meta — SAM Vision

Doc de référence de l'intégration. Relecture **25/10/2026** dans le worktree `sam-wt-docs-app`
(base `origin/master`, HEAD `8f9876a`). Chaque affirmation non suivie de `fichier:ligne` est
soit une donnée externe datée, soit marquée **non mesuré**. Rien ici n'a été construit ni lancé
sur matériel : pas d'adb, pas de build, pas de Modal.

## 1. Ce qui est livré, et où

### Sur `master` (fusionné — donc « livré »)

| Fait | Preuve |
|---|---|
| `app/…/glasses/` : 7 fichiers Kotlin (controller, caméra, inputs, frames, mock, section, état) | `ls app/src/main/java/dev/sam3vision/app/glasses/` ; entrés par le commit `f98595e` (25/10 15:38, +1154 lignes) |
| Logique pure des appuis dans `:core` + son test JVM | `core/…/glasses/CaptureGesture.kt` (71 l.), `CaptureGestureTest.kt` (56 l.) |
| Les 4 artefacts DAT en dépendance de l'app | `app/build.gradle.kts:128-131` (`mwdat-core`, `-camera`, `-inputs`, `-mockdevice`), version `mwdat = "1.0.1"` (`gradle/libs.versions.toml`) |
| Manifeste DAT : Bluetooth, meta-data, deep-link | `app/src/main/AndroidManifest.xml:9-13`, `:32-37`, `:48-54` |
| `minSdk` relevé à 29 pour `:app` | `app/build.gradle.kts:43` (spike 29, `:core` 26, `compileSdk` 36 partout) |
| Bloc « Lunettes Ray-Ban Meta » dans les Réglages | `app/…/SettingsScreen.kt:97-99`, `app/…/glasses/GlassesSection.kt:37` |
| La photo des lunettes rejoint l'app comme une photo téléphone | `app/…/AppNav.kt:89-100` (`GlassesBridge`) |

### Seulement sur `feat/meta-glasses` (donc **non** livré)

La branche porte 2 commits que `master` n'a pas, et est 95 commits derrière (`git merge-base
--is-ancestor 21d6e34|0dca012 HEAD` → non ; `git rev-list --left-right --count
origin/master...feat/meta-glasses` → `95 2`, mesuré ici) :

- `21d6e34` « intégration Device Access Toolkit » : **le même travail**, rejoué et complété
  depuis sur `master` par `f98595e` (mêmes 7 fichiers `app/…/glasses/`, même `CaptureGesture` ;
  cette doc comptait 109 lignes sur la branche, elle a été réécrite depuis). Rien de neuf côté
  lunettes sur la branche.
- `0dca012` « Direct : deux concepts par défaut + `LivePrompts.Wide` » : **`LivePrompts.Wide`
  n'existe pas sur `master`** (seuls `Default` et `normalize`, `core/…/live/LivePrompts.kt:7-20`).
  Sans rapport avec les lunettes.
- La branche ne contient **ni** `glasses-spike/`, **ni** `docs/glasses-dat-spike.md`, **ni**
  `docs/MODE-TAP.md` / `MODE-US.md` (le diff `master → feat/meta-glasses` les supprime) : elle
  est en retard, pas en avance.

**À ne pas vendre comme livré** : le routage audio Bluetooth vers les lunettes (section 9) et
l'audio dans le flux vidéo : ces codes n'existent que dans `glasses-spike/`.

## 2. Contrat DAT réellement appelé

Surface SDK effectivement touchée par `:app` (relue dans le code, pas dans la doc Meta) :

| Appel | Où |
|---|---|
| `Wearables.initialize(context)` (une fois, au `onCreate`) | `GlassesController.kt:61-72`, appelé par `MainActivity.kt:57-60` |
| `Wearables.isDevMode`, `registrationState`, `devices`, `devicesMetadata` | `GlassesController.kt:66`, `:74-108` |
| `Wearables.startRegistration(activity)` / `startUnregistration(activity)` | `GlassesController.kt:111-120` |
| `Wearables.handleIntent(intent) { it.continueRegistration(activity) }` | `GlassesController.kt:123-126`, `MainActivity.kt:74-77` |
| `Wearables.createSession(AutoDeviceSelector())` puis `session.start()` | `GlassesController.kt:128-146` |
| `session.state`, `session.errors` | `GlassesController.kt:143-144`, mapping UI `:248-254` |
| `Wearables.checkPermissionStatus(Permission.CAMERA)` | `GlassesController.kt:166-174` |
| `session.addCamera(StreamConfiguration(...))` / `removeCamera()` (extensions du module camera) | `GlassesCamera.kt:27`, `:73` |
| `stream.start()`, `stream.state`, `stream.errorStream`, `stream.capturePhoto()`, `stream.stop()` | `GlassesCamera.kt:32`, `:52`, `:55`, `:62`, `:72` |
| `session.addInputs(InputsConfiguration(sources = {CAPTURE_BUTTON}, consumeBack = false))` / `removeInputs()` | `GlassesInputs.kt:41-42`, `:81` |
| `PhotoData.Bitmap` / `PhotoData.HEIC` → `Bitmap` | `GlassesFrames.kt:40-46` |

Signatures exactes du SDK (types, valeurs par défaut, enums d'erreur) : relevées sur le bytecode
des AAR le **20/10**, consignées dans `docs/glasses-dat-spike.md`. L'extraction `/tmp/datcheck`
**n'existe plus ici** (mesuré : `ls /tmp/datcheck` → absent) : ces signatures sont donc
**non revérifiées** ce 25/10. Les coordonnées d'appel ci-dessus, elles, sont relues dans le code.

## 3. Le flux, bout en bout

`Réglages → Lunettes → Enregistrer` (confirmation côté Meta AI) → `Démarrer la session` →
`createSession` + `start()` → état `STARTED` → `attachCapabilities()` : caméra **puis** inputs
(`GlassesController.kt:148-164`) → appui sur le bouton capture → `CaptureAction` → `capture()` →
`stream.capturePhoto()` → JPEG écrit dans le cache (`GlassesFrames.kt:49-53`) →
`GlassesEvent.PhotoTaken` → `AppViewModel.onPhoto` → écran d'analyse ou chat
(`AppNav.kt:92-99`).

La permission caméra vérifiée là est celle **de l'app Meta AI** (côté SDK), pas une permission
Android : `ensureCameraPermission()` ne fait qu'un `checkPermissionStatus`
(`GlassesController.kt:166-174`) et, si elle manque, dit exactement où cliquer
(`:171`). Le flux caméra des lunettes n'a pas besoin de `android.permission.CAMERA` sur le
téléphone (celle-ci n'est demandée que pour la capture du téléphone, `CaptureScreen.kt:99`, et
pour le mode tap, `TapCaptureScreen.kt:94-102`).

Depuis la fusion iSee, l'accueil n'est plus l'écran photo : c'est le chat plein écran avec une
fenêtre caméra (`AppNav.kt:41-53`). Les lunettes n'ont pas d'écran dédié : leur photo suit le
même chemin qu'une capture téléphone (ci-dessus), et le point d'entrée reste Réglages.

## 4. Contraintes Bluetooth, 15 i/s

- `StreamConfiguration(audioCodec = null, videoQuality = MEDIUM, frameRate = 15,
  compressVideo = true)` — `GlassesCamera.kt:43-48` et `FRAME_RATE = 15` à la ligne 80. Le
  commentaire du code pose la règle : « au-delà, le lien Bluetooth Classic casse l'image ».
  Cette limite est un **constat matériel non remesuré ici** (aucun essai sur lunettes).
- Dimensions annoncées par le SDK pour `MEDIUM` (504×896) : elles viennent du relevé bytecode du
  20/10, donc **non revérifiées** ; et c'est un format de flux, pas une image optique.
- `compressVideo = true` : le flux demandé est compressé, donc moins lourd sur le lien. Mais
  **l'app ne consomme aucune trame vidéo** — aucun abonnement à `videoStream` (mesuré : zéro
  occurrence dans `app/src`), et `VideoFrame.toBitmapOrNull()` (`GlassesFrames.kt:22-37`) n'est
  appelé nulle part ; seul l'overload `PhotoData.toBitmapOrNull()` sert (`GlassesCamera.kt:63`).
  Les trames compressées ne sont donc jamais décodées par l'app : c'est un choix de bande
  passante, pas un chemin d'image.
- ⚠️ Le **spike fait l'inverse** : `StreamConfiguration()` par défaut (`compressVideo = false`,
  donc YUV décodé sur le téléphone) à 24 i/s, avec un convertisseur YUV écrit pour lui
  (`glasses-spike/…/GlassesSession.kt:214-216`, `:337` ; `MockGlassesTest.kt:91` ;
  `FrameTypes.kt:64`). Les deux chemins coexistent : **ne rien conclure sur l'app à partir du
  spike**, et réciproquement.
- Cadence réelle sur le lien, perte de trames, latence Bluetooth : **non mesuré**.

## 5. Permissions, manifeste, plancher API

Ce que déclare `app/src/main/AndroidManifest.xml` (relu intégralement) :

| Ligne | Déclaration | Note |
|---|---|---|
| 4-7 | `INTERNET`, `CAMERA`, `RECORD_AUDIO`, `VIBRATE` | fonctions téléphone |
| 10-12 | `BLUETOOTH` avec `android:maxSdkVersion="30"` | ancienne API |
| 13 | `BLUETOOTH_CONNECT` | runtime à partir de API 31 |
| 15-20 | `uses-feature` caméra et Bluetooth `required="false"` | l'app reste installable sans |
| 32-37 | meta-data `com.meta.wearable.mwdat.APPLICATION_ID` / `.CLIENT_TOKEN` = `${placeholder}` | **pas** des « 0 » en dur : placeholders injectés par Gradle, valeur `local.properties` (`mwdat.application.id` / `mwdat.client.token`), défaut `"0"` si absent (`app/build.gradle.kts:54-56`) — le mode Developer Mode est donc le comportement par défaut, pas une constante du manifeste |
| 48-54 | `intent-filter` `VIEW` + `DEFAULT` + `BROWSABLE`, schéma `samvision` | retour d'enregistrement / invocation ; `onNewIntent` le relais (`MainActivity.kt:68-77`) |

**Point ouvert (non mesuré)** : `:app` ne demande jamais `BLUETOOTH_CONNECT` en runtime — les
seules demandes runtime de l'app sont le micro (`MainActivity.kt:51`, `:113-117`), la caméra de
l'écran de capture (`CaptureScreen.kt:99`) et le couple caméra + micro du mode tap
(`TapCaptureScreen.kt:94-102`). Le lien Bluetooth pourrait donc être refusé au premier appairage
sur API 31+. Le spike, lui, le demande (`glasses-spike/…/MainActivity.kt:185-193`). À vérifier sur
matériel avant d'affirmer.

`minSdk` 29 vient des AAR (ils déclarent `android:minSdkVersion="29"`, relevé 20/10) : l'app
exige **Android 10+**, un téléphone en API 26-28 ne peut plus installer l'APK
(`INSTALL_FAILED_OLDER_SDK`), lunettes appairées ou non. Ce que le SDK impose, pas la paire.

## 6. Prérequis, une fois (donnée externe, datée)

Valeurs lues dans la doc Meta le **20/10/2026**, **non revérifiées** depuis (pas d'accès au
compte ni aux lunettes ici) :

| Élément | Minimum (constat 20/10) | Où le vérifier |
|---|---|---|
| Téléphone | Android 10+ (API 29) | imposé par l'APK, voir section 5 |
| App Meta AI | v292 | Settings → App Info |
| Firmware lunettes | v128 | Devices → réglages → General → About → Version |
| Developer Mode | activé | Settings → App Info → taper 5× le numéro de version → Enable |

Piège signalé par la doc Meta (constat 20/10) : après l'OTA v128, la capture ne fonctionne pas
tant que les lunettes n'ont pas redémarré. Redémarrer avant de conclure à une régression.

Aucune clé, aucun compte partenaire, aucun projet Developer Center n'est nécessaire dans ce mode :
l'attestation n'est pas utilisée en Developer Mode (placeholders à `"0"`).

## 7. Activer, dans l'ordre

1. Appairer les lunettes dans l'app Meta AI, et la garder ouverte pendant l'enregistrement.
2. SAM Vision : **Réglages → Lunettes Ray-Ban Meta → Enregistrer auprès de Meta AI**
   (`GlassesSection.kt:41-46`, `GlassesController.kt:111-116`).
3. Dans Meta AI : accepter. L'app apparaît dans *Settings → App connections → Developer mode apps*.
4. Toujours dans Meta AI : caméra en **« Always allow »** pour SAM Vision (sinon la permission est
   redemandée à chaque session).
5. Revenir : **Démarrer la session** (bouton d'état réel : « Session en cours de démarrage… » puis
   « Arrêter la session », `GlassesSection.kt:47-56`), puis **Prendre une photo**.

Quatre lignes d'état, en français, lisibles sans logcat (`GlassesSection.kt:71-83`) : SDK (+
mention « dev mode »), enregistrement, appareil / batterie / portées, session + caméra + flux.

## 8. Gestes du bouton capture

| Geste | Action | Preuve |
|---|---|---|
| Appui court | photo → analyse (masques SAM + description) | `CaptureGesture.kt:46-61` → `PHOTO` ; `GlassesController.kt:177-182` |
| Appui long (`HOLD`) | photo → **chat** (la question se pose sur le téléphone) | `CaptureGesture.kt:38-41` → `ASK` ; `AppNav.kt:96` |
| Double appui | rien de lancé : message d'explication | `CaptureGesture.kt:42-45` → `LIVE` ; texte exact `GlassesController.kt:180` : « Mode Direct : pas encore porté sur lunettes, il reste sur le téléphone. » |
| Tap / tap-long du pavé | *jamais écoutés* | `InputsConfiguration(sources = {CAPTURE_BUTTON})` (`GlassesInputs.kt:42`) ; réservés par le système (pause / arrêt de session, commentaire `:25-26`) |

Le SDK beta émet `SHORT_PRESS` **puis** `DOUBLE_PRESS` pour un double appui (known issue, doc Meta
20/10). `CaptureGestureBuffer` (`:core`, logique pure testée en JVM) retient donc l'appui court
350 ms (`DEFAULT_GRACE_MS`, `CaptureGesture.kt:70`) et le jette si un double arrive. Un tick à
80 ms confirme l'appui survécu (`GlassesInputs.kt:68-76`, `TICK_MS = 80L` ligne 87).

## 9. La voix : ce qui marche aujourd'hui

- **Branché** : le TTS ElevenLabs de l'app lit la description, **sur la sortie du téléphone**
  (`SpeechPlayer` = `MediaPlayer`, aucun routage). Le bouton capture est le seul déclencheur
  branché : zéro configuration Meta.
- **Non branché dans `:app`** : l'audio des lunettes dans le flux vidéo — `audioCodec = null`
  (`GlassesCamera.kt:44`) ; `getAudioStream()` n'est appelé nulle part dans l'app.
- **Non branché dans `:app`** : le routage Bluetooth de la réponse. Le routage SCO
  (`setCommunicationDevice`, `MODE_IN_COMMUNICATION`) n'existe **que dans le spike**
  (`glasses-spike/…/GlassesVoice.kt:197-204`), avec son avertissement : sans
  `MODIFY_AUDIO_SETTINGS` il retombe silencieusement sur le haut-parleur. Le manifeste de `:app`
  ne déclare pas cette permission.
- **Piste non réalisée** : le micro des lunettes comme source d'audio (`AudioCodec.PCM(RATE_16000, 1)`,
  capacité beta du SDK, relevé 20/10 — **non revérifiée**), qui garderait la sortie en A2DP ; en
  contrepartie le profil HFP tombe la sortie en mono 16 kHz (les deux profils sont exclusifs).
- **Piste non réalisée** : « Hey Meta, start SAM Vision ». **Aucune** occurrence de
  `VoiceInvocation` dans `app/`, `core/`, `glasses-spike/` (mesuré ce 25/10) : le cold-launch
  vocal demande un projet dans le Wearables Developer Center, une config mobile
  (`dev.sam3vision.app`), un nom d'app = mot-clé vocal et **une version** portant la permission
  *Voice Invocation* — donc un cran au-dessus du Developer Mode `0/0`.

## 10. Sans lunettes, l'app reste complète

Le bloc Lunettes est une option : aucune fonction du téléphone n'en dépend.

| Fonction | Où | Doc |
|---|---|---|
| iSee (accueil : chat + fenêtre caméra, photo du double tap, distances) | accueil par défaut | `README.md`, `docs/TESTING.md` |
| Direct : flux vidéo → LLM/SAM, masques, distances, narration | Réglages → Autres écrans → « Mode Direct (vidéo en continu) » (`SettingsScreen.kt:152`) | `docs/DISTANCES.md` |
| Voix ElevenLabs (positions, lecture des réponses) | Réglages → Voix | `docs/ELEVENLABS-TTS.md` |
| Dictée Meta (Muse) par appui long | partout sur l'accueil iSee | `docs/META-ASR.md` |
| Mode tap (vidéo → SAM 3.1, trois mots) | Accueil classique (`SettingsScreen.kt:153`) | `docs/MODE-TAP.md` |
| Nettoyage des captures (Room + WorkManager) | silencieux | `docs/NETTOYAGE.md` |

## 11. Démo sans matériel (mock)

- Les commandes de simulation n'apparaissent **qu'en debug** :
  `GlassesSection(debugBuild = BuildConfig.DEBUG)` (`SettingsScreen.kt:97-99`) et
  `if (debugBuild) { MockControls(...) }` (`GlassesSection.kt:63-65`) — « Simuler des lunettes »,
  « Simuler un appui sur le bouton capture », « Retirer les lunettes simulées ».
- `GlassesMock.start()` : `MockDeviceKitConfig(initiallyRegistered = true,
  initialPermissionsGranted = true)`, `permissions.set(Permission.CAMERA, Granted)`,
  `pairGlasses(GlassesModel.RAYBAN_META)`, puis une scène synthétique
  `mock-scene.jpg` en **640×480** posée à la fois comme flux et comme photo
  (`GlassesMock.kt:30-62`, `WIDTH/HEIGHT` lignes 88-89). Un appui s'injecte par
  `services.input.capture(type)` (`GlassesMock.kt:44-48`).
- ⚠️ Nuance qui compte avant un build public : `mwdat-mockdevice` est en `implementation` dans
  `:app` (`app/build.gradle.kts:131`) — il est donc **embarqué aussi dans l'APK de release**, seul
  l'affichage des boutons est gated en debug. Le `TODO(spike)` qui l'interdit n'existe que dans le
  spike (`glasses-spike/build.gradle.kts`, section dépendances). À trancher avant publication.

Ce que le mock ne prouve pas (latence Bluetooth, optique, permissions réelles de l'app Meta AI,
thermique/autonomie, routage audio) : détaillé dans `docs/glasses-dat-spike.md`, §5.

## 12. Ce qui bloque, et ce qui reste noir

| Blocage | État |
|---|---|
| Paire physique + app Meta AI + Developer Mode + firmware ≥ v128 | requis, **non testé** ici (aucun téléphone, aucune lunettes dans ce worktree) |
| Enregistrement : une seule app tierce à la fois en Developer Mode | règle doc Meta 20/10, non revérifiée |
| Artefacts Maven Central | **non bloqueurs** : `com.meta.wearable:mwdat-*:1.0.1` mesurés disponibles le 25/10 (10,64 Mo pour les 3 du spike, détail dans `docs/glasses-spike-ci.md`) ; `RepositoriesMode.FAIL_ON_PROJECT_REPOS` et `mavenCentral()` sont dans `settings.gradle.kts` |
| Permission runtime `BLUETOOTH_CONNECT` jamais demandée par `:app` | ouvert, voir section 5 |
| `mockdevice` dans l'APK de release | ouvert, voir section 11 |
| Qualité d'image à 15 i/s vs 24 i/s sur le lien réel | **non mesuré** ; seul le choix du code est établi |
| Durées de build / de session | **aucun build relancé ici** ; les chiffres de la CI restent des constats du 24/10 consignés dans `ci.yml` et repris dans `docs/glasses-spike-ci.md` |

## 13. Fichiers

| Fichier | Rôle |
|---|---|
| `core/…/glasses/CaptureGesture.kt` (71 l.) | lissage des appuis, logique pure + test JVM |
| `app/…/glasses/GlassesController.kt` (253 l.) | enregistrement, session, permissions, état, routage des gestes |
| `app/…/glasses/GlassesCamera.kt` (81 l.) | flux 15 i/s MEDIUM `compressVideo = true`, `capture()` |
| `app/…/glasses/GlassesInputs.kt` (95 l.) | bouton capture → actions, tick 80 ms |
| `app/…/glasses/GlassesFrames.kt` (52 l.) | frame/photo → Bitmap → JPEG du cache |
| `app/…/glasses/GlassesState.kt` (38 l.) | `GlassesUiState` / `GlassesEvent` |
| `app/…/glasses/GlassesMock.kt` (90 l.) | Mock Device Kit + scène 640×480 |
| `app/…/glasses/GlassesSection.kt` (99 l.) | bloc « Lunettes » des Réglages |
| `app/…/AppNav.kt:89-100` | `GlassesBridge` : photo → `onPhoto` → analyse ou chat |
| `docs/glasses-dat-spike.md` | relevé d'API du SDK (bytecode 20/10) et écarts du spike |
| `docs/glasses-spike-ci.md` | le module `:glasses-spike` dans la CI, budget 5 min |

`glasses-spike/` est un **module séparé** (`include(":glasses-spike")` dans
`settings.gradle.kts`, package `dev.sam3vision.glasses`) : il dépend de `project(":core")`,
`:app` ne dépend pas de lui. Ce que fait le spike ne préjuge pas de ce que fait l'app.

## 14. Passer en production plus tard

1. Projet dans le Wearables Developer Center : récupérer *Application ID* et *Client Token*,
   justifier l'accès caméra/micro (revue interne Meta).
2. Les mettre dans `local.properties` (`mwdat.application.id`, `mwdat.client.token`) — jamais
   commités : le manifeste les lit via `manifestPlaceholders` (`app/build.gradle.kts:54-56`).
3. Créer une **version**, l'assigner au canal privé Beta, inviter les testeurs par e-mail (un
   Managed Meta Account n'est pas accepté). C'est aussi la condition de l'invocation vocale (§9).
4. Sortir `mockdevice` de l'APK public (§11).
