# SAM Vision / iSee — app Android native (Kotlin)

Photo → description par un LLM vision → questions/réponses sur la photo, et un **direct
vidéo** avec masques SAM 3.1, distances et annonces vocales. LLM via **InferHub**
(`https://api.inferhub.dev/v1`, compatible OpenAI — défauts `Settings.kt:56-57`, modèle
par défaut `ali/qwen3.8-omni-flash`). Segmentation **SAM 3.1** sur un endpoint Modal saisi dans
Réglages ; aucun code de backend n'est dans ce dépôt.

État vérifié le **2026-10-25** sur la base `8f9876a` (worktree détaché, 2 commits derrière
`origin/master`). Chaque affirmation porte sa preuve `fichier:ligne` ; ce qui n'a pas pu être
vérifié ici est marqué **non mesuré ici**.

## ⚠️ Ce qui n'est PAS vérifiable depuis ce dépôt (backend)

- Le conteneur déployé (`sam3-eu-a100`) tourne sur une image `sam3.1-fast`
  (`batch_prompts`, edge 518) **dont le source n'existe dans aucun dépôt connu**, et le dépôt
  backend ne contient pas `/live/ws`. Le contrat serveur du direct est donc connu côté client
  seulement : `core/net/WsLiveClient.kt:19` et `core/net/WsTransport.kt:27`.
- `core/net/HttpLiveClient.kt:25` pointe encore vers `docs/LIVE-SPEC.md`, **absent du dépôt**,
  et nomme l'ancienne app Modal `sam3-live` : commentaire périmé.
- Les chiffres de latence backend (`LATENCE.md` : serveur 82-95 ms, A/R PC 240 ms, réveil
  conteneur 23,6 s, TLS+DNS 67,4 ms, frame 512×206 = 34,1 Ko) viennent d'un autre dépôt :
  **non mesuré ici**. L'historique du 24/10 (294-298 ms serveur, A/R 472-503 ms en eu-west,
  1107-1115 ms en us-east) est **périmé** depuis le passage en WebSocket.
- Une seule GPU Modal à la fois (`sam3-eu-a100` **ou** `sam3-us-a100`) : règle confirmée côté
  app par `core/settings/SamRegion.kt:6-9` et `app/SamEndpoint.kt:10-11`. L'état réel du parc
  Modal n'est pas mesurable ici.

## Le direct est en WebSocket — `POST /live` n'est plus utilisé pour les frames

Depuis #137 (`98a1f08`, dans l'historique de `master`), **les frames du direct partent par
WebSocket `/live/ws`**, plus aucun `POST /live` :

- `core/net/WsLiveClient.kt:19` — client Live sur socket : un message JSON par frame, réponse
  portant le **même `id`** ; **une file d'attente par `id`** (et non FIFO), timeout, erreurs
  typées (`WsLiveClient.kt:14-17`).
- `core/net/WsTransport.kt:15` — interface de transport : `OkHttpWsTransport` en production
  (`WsTransport.kt:45`), un faux JVM en test (aucun réseau dans les tests) ; `https` → `wss`
  (`WsTransport.kt:29-33`).
- `core/net/LiveClientFactory.kt:6-9` — `liveClient()` ne fabrique **que** du WebSocket ; le
  client HTTP ne reste que pour `GET /health`, délégué (`WsLiveClient.kt:64`).
- `#136` (HEAD `8f9876a`) — la **sonde iSee** (`app/ihear/LiveProbe.kt:130,139`) utilise la
  même fabrique `liveClient()` : elle est donc en WebSocket. **Le repli HTTP sur les frames
  n'existe plus** : la pastille dit « WebSocket » ou « WebSocket KO »
  (`app/LiveMessages.kt:20,23`). Deux commentaires de code sont périmés là-dessus :
  `LiveProbe.kt:49` (« http = repli automatique ») et l'import inutilisé `LiveViewModel.kt:24`.
- Gains **mesurés côté serveur** et consignés dans le code : 50,3 ms en WebSocket contre
  81,4 ms en POST, soit ~31 ms par frame (`WsLiveClient.kt:11`, `LiveClientFactory.kt:7`).
  L'A/R téléphone (159 ms contre 173 ms en POST, avancé le 25/10) n'est consigné nulle part
  dans ce dépôt : **non mesuré ici**.

Délais réellement câblés : frame 10 s (`core/net/HttpLiveClient.kt:11`), réveil du conteneur
60 s pour la sonde (`LiveProbe.kt:406`), `/health` 180 s (`HttpLiveClient.kt:19`).

## Ce que voit l'utilisateur

- **Accueil iSee** (`app/ihear/IhearHome.kt:12-19`) : un seul écran, **chat plein écran**, la
  caméra vit dans une bulle en bas du fil. **Gestes** (`IhearViewModel.kt:214-227`) : tap
  simple = démarre/arrête la vidéo (point d'enregistrement **blanc** sur l'angle haut-droit,
    `app/ihear/IhearCameraWindow.kt:109,237-243`) ;
  double tap = photo immédiate, masquée de **pastilles de distance**, puis évaporation en fondu ;
  balayage vertical = une bulle à la fois ; appui long = dictée. Retour système = Réglages.
  Rien n'atteint les anciens écrans depuis l'accueil : la porte est « Autres écrans » dans les
  Réglages (`SettingsScreen.kt:152-153`).
- **Pendant l'enregistrement iSee** : chaque frame part au direct SAM et revient en **masques
  colorisés** dans la même surface que l'aperçu (`app/CameraSurface.kt`), avec un **panneau de
  latence** dense (cadence, latence moyenne, p95, durée serveur, taille de frame, frames/masques,
  concepts, erreur — `app/ihear/LiveDebugPanel.kt:21-30`) et la **voix** qui annonce objet,
  position et distance, une fois par nouvelle phrase (`app/ihear/ProximityVoice.kt:8-16`).
  Frame iSee mesurée dans le code : **448 px, JPEG q55** (`ihear/IhearCameraPane.kt:37,40`).
- **Capture** : titre « SAM Vision », aperçu caméra, quatre boutons — « Photographier »,
  « Vidéo (tap) » (vert), « Galerie », « Réglages » (`CaptureScreen.kt:232-238`) ; un tap sur
  l'aperçu lance le mode vidéo avec `autoStart` (`AppNav.kt:113`).
- **Analyse** : JPEG bord max 1024 / q85 (`ImageSource.kt:15-16`), tags sous le titre, bloc
  « Description », masques togglables et liste label/score quand la segmentation répond, bouton
  « Poser une question » → Chat.
- **Chat** : fil de bulles sur la photo courante, champ de question, bouton « Dicter » (devient
  « Stop »), « Envoyer », « 🔊 Écouter » sous chaque réponse, état « en cours… » et réessai.
- **Direct (écran dédié)** : masques en superposition, distance « ≈ 3,2 m » au-dessus de chaque
  boîte et objet le plus proche dans la ligne d'état
  « Direct · 1,4 ips · 780 ms · 8 objets · plus proche 1,4 m · A100-SXM4-40GB »
  (`LiveState.kt:89,105-108`), concepts éditables à chaud (chip « Suivre », `LiveBars.kt:206`),
  questions pendant le flux, mode « vision LLM » sans détecteur (`LiveState.kt:80`).
  Frame du Direct : **512 px, JPEG q65** (`LiveScreen.kt:47-48`) ; bord de travail serveur 640 et
  plafond d'objets **4** par défaut (`core/live/LiveSession.kt:149,151`,
  `core/model/ObjectLimits.kt:13` — relevé à **8** sur `origin/master`, commit `04e7f7c`).
  Cadence adaptative réellement câblée : **350 / 500 / 900 ms** (`LiveSession.kt:33`,
  `intervalMsAdaptive()` `LiveSession.kt:129-133`).
- **Distances** : calculées sur le téléphone (sténopé + hauteurs connues), ±15-20 %, 0 GPU,
  aucune requête en plus (`DistanceSettings.kt:31-34`, `docs/DISTANCES.md`).
- **Mémoire du direct** : panneau qui garde les **60 dernières secondes** et une identité stable
  par objet (`core/live/LiveTimeline.kt:147-148,12`) ; chips « personne · il y a 3 s », instants
  « maintenant / il y a 5 s / 15 s / 30 s » (`LiveMemoryPanel.kt:101-109`), question libre ;
  le LLM reçoit la dernière image **et** le journal traduit en français.
- **Vidéo (tap)** : depuis Capture ou l'accueil ; un tap lance l'enregistrement, un second l'arrête
  et l'envoie à SAM 3.1 (`core/net/HttpSam31VideoClient.kt:19` — `POST /video` multipart, **lui
  toujours en HTTP**). Attention : le mode tap n'utilise **pas** l'endpoint des Réglages mais
  `BuildConfig.SAM31_ENDPOINT` injecté au build (`tap/TapConfig.kt:35`), avec une inférence
  serveur (`ON_DEVICE_INFERENCE = false`, `TapConfig.kt:48`) et **sans piste audio** — un seul
  `AudioRecord` par app, le micro reste à la dictée. Détail : `docs/MODE-TAP.md`.
- **Dictée** (Meta Muse `muse-voice-transcribe-1.0`, repli ElevenLabs Scribe) : bouton « Dicter »
  **ou appui long n'importe où** (seuil 300 ms, sous le seuil système —
  `core/gesture/LongPressTrigger.kt:101-102`) ; micro 16 kHz mono, sans limite de durée ; le texte
  s'insère dans le champ ou s'affiche en gros caractères et se lit à voix haute.
  Contrat, facturation 402 : `docs/META-ASR.md`.
- **Voix ElevenLabs** : lecture automatique des réponses du chat + annonces de position ;
  bouton « 🔊 Écouter » et « Tester la voix ». Une seule classe par moteur
  (`core/net/ElevenLabsTtsClient.kt`). Voix **premade** seules passent sur un compte free
  (Sarah `EXAVITQu4vr4xnSDxMaL`, George `JBFqnCBsd6RMkjVDRZzb`) ; voix de bibliothèque → 402
  (rappelé par `TtsSettings.kt:38-42`, détail `docs/ELEVENLABS-TTS.md`).
- **Langue de la voix** : la personne qui parle — annonces de position, réponses du chat et du
  direct, narration — parle **en anglais** par défaut (« Person on the left, 1.4 meters away. »),
  et en français si l'on choisit « Voix en français (fr) » dans Réglages. Boutons
  **« Langue de la voix »** (`app/VoiceLanguageSettings.kt:24`), réglage
  `Settings.speechLanguage` (`core/settings/Settings.kt:60`, défaut anglais :
  `core/speech/SpeechLanguage.kt:32`), annonces (`core/live/PositionText.kt:101`,
  `core/distance/DistanceText.kt:36`) et consignes du modèle (`core/net/LlmPrompts.kt:14`).
  L'interface reste en français ; la dictée retranscrit la langue de celui qui parle.
  Détail : `docs/VOIX-ANGLAIS.md`.
- **Intro iSee** : l'audio embarqué (`app/src/main/res/raw/intro_ihear.mp3`, voix Jade US) est
  **rejoué à chaque ouverture complète** tant que la bascule est « on » — il n'y a **aucun marqueur
  persisté** (`ihear/IhearIntro.kt:30-33`). Il se coupe par la voix : dictée
  « Disable iSee intro » (`IntroLogic.kt:31-32`). Libellé réel de la bascule :
  **« Intro à chaque ouverture : on/off »** (`IntroLogic.kt:9-10`). Aucun écran « Touchez l'écran
  pour commencer » : cette phrase n'existe que dans le mode tap (`tap/TapCaptureScreen.kt:135`).
- **Réglages** (`SettingsScreen.kt:68`) : titre « Réglages du backend », mention « Aucune clé
  n'est écrite dans le code. », **10 champs** (Base URL LLM, Clé API LLM, Modèle LLM (vision),
  Base URL SAM 3.1 — `SettingsScreen.kt:113-116` ; base URL ASR, clé Meta, modèle ASR —
  `SettingsScreen.kt:173-179` ; clé ElevenLabs, ID de voix — `TtsSettings.kt:36-37` ;
  champ de vision caméra — `DistanceSettings.kt:38-42`), champs secrets masqués **révélables**,
  **4 interrupteurs** (« Lire les réponses automatiquement », « **Annoncer les objets et leur
  distance** », « Distances sur le direct », « Intro à chaque ouverture »), section
  **« GPU SAM 3.1 — région »** avec un bouton **pleine largeur** par région et l'endpoint actif
  affiché (`GpuRegionSettings.kt:22-39`), boutons « Enregistrer », « Tester le LLM »,
  « Tester SAM », « Tester la dictée Meta », « Tester la voix », plus « Autres écrans ».
  Le champ SAM vide ne coupe plus rien : l'endpoint est toujours résolu par la région
  (`core/settings/SamRegion.kt:50-55`, `Settings.kt:112`), donc le bandeau « Segmentation
  désactivée » (`AppViewModel.kt:179`) n'est plus atteignable avec les presets.

## Arborescence réelle

```
rootProject = "sam-vision-kotlin" (settings.gradle.kts:16-19)
├── core/                     # :core — JVM pur + wrappers Android fins
│   └── src/main/java/dev/sam3vision/core/
│       ├── model/ net/ mask/ distance/ settings/ text/ usecase/   # socle
│       ├── live/             # LiveSession, Cadence, FrameRate, LiveTimeline, PositionAnnouncer…
│       ├── gesture/          # TapOrHold, LongPressTrigger, IhearGestureBrain
│       ├── glasses/          # CaptureGesture (appui court/long des lunettes)
│       ├── predict/          # BoxPredictor, MotionModel, ObjectAssociator (suivi local)
│       └── audio/            # Wav, SentenceChunker
│   net/ : Http.kt, InferHubLlmClient, HttpSamClient, HttpLiveClient,
│          WsLiveClient + WsTransport + LiveClientFactory, TrackClient,
│          MetaAsrClient, ElevenLabsTts/SttClient, FallbackSpeechClient, Json.kt
├── app/                      # :app — Compose + CameraX, `dev.sam3vision.app`
│   ├── AppNav.kt AppViewModel.kt MainActivity.kt SamApp.kt Theme.kt SharedUi.kt
│   ├── CaptureScreen / AnalysisScreen / ChatScreen / SettingsScreen / LiveScreen
│   ├── LiveBars LiveState LiveViewModel LiveMessages LiveMemoryPanel LiveVoice
│   ├── Distance* ImageSource IntroAudio IntroLogic LongPressRootLayout MicBus MuseTranscript
│   ├── SamEndpoint GpuRegionSettings TtsSettings TtsState SpeechClients SpeechPlayer
│   ├── SettingsSeed Dictation* CameraLens CameraSurface
│   ├── ihear/    # IhearHome, IhearChatPane, IhearCameraPane/Window, IhearViewModel,
│   │             # LiveProbe (sonde WS), LiveDebugPanel, ProximityVoice, ProbeWiring…
│   ├── glasses/  # GlassesController/Camera/Frames/Inputs/Mock/Section/State (Ray-Ban Meta)
│   ├── tap/      # TapMachine, TapCaptureScreen, TapVideoSession, AnnouncementPlayer…
│   ├── media/    # MediaCacheStore, PcmToMp3Encoder, CacheCleanerManager, MessageMultimedia
│   ├── cleanup/  # purge horaire (docs/NETTOYAGE.md)
│   └── src/{test,androidTest}/java/…   # + schemas/ (Room `traced_captures`, v1)
├── glasses-spike/            # :glasses-spike — prototype DAT, non production
├── tools/check-kotlin-limits.py        # garde < 500 lignes/fichier, < 50 lignes/fonction
├── scripts/build.sh · install-emulator.sh · verify-tap-voix.sh
├── docs/  TESTING.md · CI-QUEUE.md · NETTOYAGE.md · DISTANCES.md · MODE-TAP.md · MODE-US.md
│          META-ASR.md · META-GLASSES.md · ELEVENLABS-TTS.md · MASQUES-DEBUG.md
│          TEST-LONGPRESS-DICTEE.md · glasses-dat-spike.md · glasses-spike-ci.md
├── .github/  workflows (ci, ci-budget, couche0, hermes-lite, stale-base-guard) · hermes/gate.py
└── gradle/libs.versions.toml · gradle.properties · CHANGELOG.md · AGENTS.md · NOTES.md
```

## Modules et tests

| Module | Rôle | `@Test` comptés |
|---|---|---|
| `:core` | domaine + réseau + logique pure (JVM, minSdk 26 — `core/build.gradle.kts:13`) | **359** |
| `:app` | Compose + CameraX, écrans, ViewModels (minSdk 29 — `app/build.gradle.kts:43`) | **109** |
| `:app` `androidTest` | 1 classe instrumentée `cleanup/CaptureCleanupTest` | **6** (appareil requis) |
| `:glasses-spike` | prototype lunettes, `include` dans `settings.gradle.kts:19` | 0 |

Méthode : `grep -rc '@Test' <module>/src/test` (une annotation par méthode de test, aucune
occurrence dans un commentaire). **Total JVM : 468 annotations.** Le nombre de tests réellement
exécutés est **non mesuré ici** (build Gradle interdit sur cette machine partagée) ; le
`CHANGELOG.md:17` annonce 427 JVM verts au commit « 2026-10-25 (10) », avant les suites WS
(`WsLiveClientTest` 10, `LiveClientFactoryTest` 2, `LiveProbeTextTest` 17).

## Prérequis

- **JDK 17** (Temurin) via **`JAVA_HOME`** — `gradle.properties` ne fait **pas** d'épinglage :
  lignes 7-10, c'est un commentaire qui dit de mettre `org.gradle.java.home` dans
  `~/.gradle/gradle.properties` (non versionné) si le `java` système est un JRE sans `javac`.
  Une ligne `org.gradle.java.home=` versionnée **fait échouer la CI** (`.github/workflows/ci.yml:94-100`).
- **Android SDK** : `~/Android/Sdk`. Vérifié **sur le r630** : seul `platforms/android-36` est installé, `build-tools` 35.0.0 et 36.0.0. Le poste de travail annonce platforms 34→36 et build-tools jusqu'à 37 : **non revérifié ici**.
- **Cache JDK du r630** : `~/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2` présent (vérifié par `ls`).
- **Gradle 8.14.1** au wrapper (`gradle/wrapper/gradle-wrapper.properties:3`),
  **AGP 8.11.1**, **Kotlin 2.2.21**, compileSdk/targetSdk **36**, **minSdk 29** côté app
  (exigé par le SDK des lunettes Meta), `:core` en 26.
- Tout est supposé déjà en cache Gradle : les commandes passent `--offline`. Vérifié le 25/10 :
  **non mesuré ici**.

## Build, test, install

```bash
export JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2 ANDROID_HOME=$HOME/Android/Sdk
./gradlew :core:testDebugUnitTest --offline     # logique pure, 0 réseau
./gradlew :app:testDebugUnitTest --offline      # écrans et règles d'UI
./gradlew assembleDebug --offline               # app/build/outputs/apk/debug/app-debug.apk
python3 tools/check-kotlin-limits.py            # < 500 lignes/fichier, < 50 lignes/fonction
bash scripts/build.sh                           # idem, environnement inclus
EMULATOR_SERIAL=emulator-5554 bash scripts/install-emulator.sh
```

Taille d'APK (21 Mo avancés) : **non mesuré ici**. Le 25/10, `tools/check-kotlin-limits.py` passe
**OK — aucun dépassement** sur 237 fichiers `.kt`/`.kts` : le plus gros fichier est
`ihear/IhearViewModel.kt` (492 lignes), puis `LiveViewModel.kt` (468). L'exception « `AnalysisScreen()`
64 lignes » signalée ici auparavant **n'existe plus**.

Téléphone : **jamais `adb` nu**, passer par `~/bin/phone run --task "…" -- <cmd>`
(sortie 2 = téléphone occupé).

## Brancher les clés (jamais de clé commitée)

1. **Écran Réglages** (recommandé) : coller la clé, Enregistrer ; stockée chiffrée
   (`EncryptedSharedPreferences`/Keystore, repli `PrivateFileSettingsStore` AES-256/GCM —
   `core/settings/SettingsStoreFactory.kt`).
2. **`local.properties`** (gitignoré, debug uniquement) → injecté dans `BuildConfig` comme
   **défaut**, la saisie Réglages prime :

```properties
sdk.dir=/home/<vous>/Android/Sdk
llm.base.url=https://api.inferhub.dev/v1
llm.api.key=sk-…votre-cle…
llm.model=ali/qwen3.8-omni-flash
tts.api.key=…   tts.voice.id=EXAVITQu4vr4xnSDxMaL
```

**Dictée Meta** : « Dictée Meta — clé API », base et modèle ayant des défauts
(`https://api.meta.ai/v1`, `muse-voice-transcribe-1.0` — `Settings.kt:74-75`), puis « Tester la dictée Meta ».
Prérequis compte : facturation Meta réglée, sinon `402 billing_not_configured` affiché tel quel
(`docs/META-ASR.md`).

## Endpoint du direct — régions EU / US

L'endpoint est résolu dans cet ordre : **saisie manuelle > preset de la région > URL injectée au
build** (`core/settings/SamRegion.kt:50-55`, `app/SamEndpoint.kt:13`). Presets :

| Région | App Modal | Endpoint (`SamRegion.kt:21,26`) |
|---|---|---|
| EU (défaut) | `sam3-eu-a100` | `https://baptiste-faisy--sam3-eu-a100-sam3live-web.eu-west.modal.run` |
| US | `sam3-us-a100` | `https://baptiste-faisy--sam3-us-a100-sam3live-web.us-east.modal.run` |

- `GET /health` reste HTTP et sert au réveil du conteneur (~30 s annoncées, « Réveil du GPU
  A100 40 Go sur Modal… » — `LiveState.kt:83`).
- **Une seule GPU allumée à la fois**, toutes régions confondues ; `routing_region` est figé au
  premier déploiement d'une fonction et ne se change plus (`docs/MODE-US.md:14-15,62`).
  Allumage : `~/bin/sam3-gpu on --reason "…"` (ou `sam3-gpu-us`), jamais une deuxième carte.
- L'ancienne URL `baptiste-faisy--sam3-live-sam3live-web.us-east.modal.run` est donnée pour
  morte (`404 modal-http: invalid function call`) : **non revérifié ici** ; le 404 est bien
  traduit en erreur `BackendStopped` côté client (`core/net/Http.kt:60-72`).
- Retrouver l'URL courante sans deviner le slug : `modal app list`, puis
  `modal.Cls.from_name("sam3-eu-a100", "Sam3Live")().web.get_web_url()` (à faire depuis une
  machine qui a `modal`, pas ici).

Redéployer l'endpoint se fait depuis le dépôt backend (nommé `sam3-vision-app`, hors de cette
machine — le chemin `~/Bureau/…` cité d'ordinaire n'existe pas sur le r630) : contrat `/health`,
`/segment`, `/video` HTTP et `/live/ws` en WebSocket.

## ⚠️ Sécurité — clé embarquée en debug

Un APK debug construit avec `llm.api.key` dans `local.properties` **contient la clé en clair**
dans son `BuildConfig`. Ne jamais distribuer cet APK ni le pousser sur un store ; tester « clé
absente » en retirant la ligne. En release : clé vide au build, saisie utilisateur chiffrée
uniquement. Les champs secrets sont masqués à l'écran et révélables sur appui.

## Protection du dépôt (CI + file d'attente)

- Checks **`ci`**, **`ci-budget`**, **`couche0`** obligatoires, merge par la **file Mergify**
  (`.mergify.yml`, contrat `docs/CI-QUEUE.md`) — pas de push direct sur `master`.
  `timeout-minutes: 5` sur tous les jobs (`.github/workflows/*.yml`).
- `ci` échoue si : `local.properties` versionné, clé `sk-…`/`sk_…`/`ghp_…` quelque part, ligne
  `org.gradle.java.home=` dans `gradle.properties` (`ci.yml:94-100`), fichier `.kt` ≥ 500 lignes
  ou fonction ≥ 50 lignes (`ci.yml:104-105` → `tools/check-kotlin-limits.py`).
- **Périmètre de PR** : le corps déclare `## Scope` avec une puce `` - `chemin` — raison `` par
  fichier touché ; chaque fichier touché est déclaré, chaque déclaration sert.
- `app/DEPS.txt` liste les artefacts attendus (Room, KSP, WorkManager, `camera-video`,
  `androidx.test` y sont) : une dépendance non listée = téléchargement froid, et le budget est
  de 5 minutes.

## Branches et base de référence

- **Base de référence = `origin/master`.** Le dépôt distant s'appelle désormais
  **`BaptisteFaisy/testt`** ; l'URL du remote de ce worktree est encore
  `https://github.com/BaptisteFaisy/sam-vision` (`git remote -v`) — le 301 n'a pas pu être
  revérifié ici (`gh` absent de cette machine, API GitHub en 404 sans auth).
- Ce worktree est détaché sur `8f9876a`, **2 commits derrière** `origin/master` (`9856d9e` #139,
  `04e7f7c` « 8 objets à l'écran par défaut »).
- `feat/latency-ms` (`e7b95b0`) n'est **pas fusionnée** : cadence sans plancher
  (`averageMs/4` borné 50-250 ms), client OkHttp partagé et timeouts 3/5/60/0 s, corps one-shot,
  parsing en octets, LUT 256 niveaux, PNG masque décodé en `ALPHA_8`, codec de masques `bits`,
  `canAcceptFrame`, `LiveTimings`/`TimingsText` (`git diff --stat master...feat/latency-ms`,
  45 fichiers). **Rien de tout cela n'est sur `master`.**
- `feat/ihear-2-concepts` (défaut à 2 concepts, « person, chair ») n'est **pas fusionnée** :
  `master` est à **8 concepts** (`core/live/LivePrompts.kt:20`) avec mesure à l'appui
  — 2 concepts 118 ms médian, 8 concepts 118 ms médian sur `/live` le 2026-10-25
  (`LivePrompts.kt:12-17`). Autres branches non fusionnées : `feat/merge-tout`,
  `feat/ihear-masques-debug`, `feat/voix-objets`, `feat/jade-tts`, `feat/live-memoire`, etc.
  (`git branch -r --no-merged origin/master`).

## Limites connues

- `docs/TESTING.md` § « Limites connues » : recette émulateur et points vigilants.
- `TrackClient` / `TrackRunner` / `core/predict/*` (suivi local entre deux frames serveur) sont
  dans `:core` mais **aucun écran de `:app` ne les appelle** à `8f9876a` (vérifié par
  `git grep`) : brique écrite, branche à venir.
- Deux commentaires de code restent faux après #137/#136 : `HttpLiveClient.kt:25` (`docs/LIVE-SPEC.md`
  absent, app `sam3-live` périmée) et `LiveProbe.kt:49` (repli HTTP inexistant).
