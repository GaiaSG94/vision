# NOTES — versions épinglées et décisions techniques

Base vérifiée le **2026-10-25** sur `8f9876a` (worktree `sam-wt-docs-app`, 2 commits derrière
`origin/master`). Les versions viennent des fichiers du dépôt, pas du souvenir d'un build :
`gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`,
`app/build.gradle.kts`, `core/build.gradle.kts`. **Aucun build Gradle n'a été lancé ici**
(machine partagée) : les verdicts d'exécution sont marqués non mesurés.

## Versions retenues (celles du dépôt)

| Élément | Version | Preuve |
|---|---|---|
| Gradle | **8.14.1** (wrapper) | `gradle/wrapper/gradle-wrapper.properties:3` |
| AGP | **8.11.1** | `gradle/libs.versions.toml:2` (`agp`) |
| Kotlin | **2.2.21** (+ plugin compose 2.2.21, même référence) | `libs.versions.toml:3` |
| KSP | **2.2.21-2.0.5** | `libs.versions.toml:14` |
| JDK | **Temurin 17 par `JAVA_HOME` — PAS épinglé dans le dépôt** | `gradle.properties:7-10` |
| compileSdk / targetSdk | **36** | `app/build.gradle.kts:39,44` |
| minSdk | **29 (`:app`)** · 26 (`:core`, lib) | `app/build.gradle.kts:43`, `core/build.gradle.kts:13` |
| Compose BOM | **2026.05.01** (activity-compose 1.13.0, lifecycle 2.10.0) | `libs.versions.toml:7,8,9` |
| CameraX | **1.6.0** (core, camera2, lifecycle, view, **video**) | `libs.versions.toml:10` |
| OkHttp | **4.12.0** | `libs.versions.toml:6` |
| coroutines | **1.10.2** | `libs.versions.toml:5` |
| Room | **2.8.5** (+ compiler via KSP) | `libs.versions.toml:12` |
| WorkManager | **2.12.0** | `libs.versions.toml:13` |
| security-crypto | **1.1.0** | `libs.versions.toml:11` |
| core-ktx | **1.18.0** | `libs.versions.toml:4` (`coreKtx`) |
| JUnit / androidx.test | **4.13.2** / ext-junit **1.3.0**, core-runner-rules **1.7.0** | `libs.versions.toml:15,16,17,18` |
| org.json (test only) | **20240303** | `libs.versions.toml:20`, `core/build.gradle.kts:40` |
| jump3r (MP3 pur Java) | **1.0.4** | `libs.versions.toml:19` |
| mwdat (lunettes Meta) | **1.0.1** (core, camera, inputs, mockdevice) | `libs.versions.toml:21` |

**Correction à cette note** : la version précédente écrivait « JDK épinglé dans
`gradle.properties` (`org.gradle.java.home`) ». **Faux** — `gradle.properties` ne contient que
`org.gradle.jvmargs=-Xmx4g`, parallel, caching, useAndroidX, nonTransitiveRClass, code.style, et
un **commentaire** disant de mettre `org.gradle.java.home` dans `~/.gradle/gradle.properties`
(non versionné). La CI bloque le retour de cette ligne (`.github/workflows/ci.yml:94-100`).

Autres corrections : `KOTLIN-SPEC.md` (spec externe, écart compileSdk 35→36) **n'existe plus dans
le dépôt** — l'écart est aujourd'hui sans objet, compileSdk 36 partout. La section « Reste ouvert
(pour l'agent UI) » — « `:app` ne contient que `MainActivity` » — est **fausse depuis longtemps** :
48 entrées dans `app/src/main/java/dev/sam3vision/app/`, dont `ihear/`, `glasses/`, `tap/`,
`media/`, `cleanup/`. Les « 27 tests `:core` » et l'APK « 17 Mo » sont périmés.

## Tests : chiffres réels

`grep -rc '@Test'` (une annotation par méthode, aucune occurrence en commentaire) :

| Suite | `@Test` |
|---|---|
| `core/src/test` | **359** |
| `app/src/test` | **109** |
| `app/src/androidTest` (`cleanup/CaptureCleanupTest`) | **6** (instrumentés, appareil requis) |
| **Total JVM** | **468** |

Le nombre de tests réellement exécutés est **non mesuré ici** (aucun `gradlew` lancé sur cette
machine) ; `CHANGELOG.md:17` annonçait « 427 JVM verts » au commit « 2026-10-25 (10) », avant
l'arrivée des suites WebSocket.

## Décisions techniques toujours vraies

- **Un seul client HTTP** : `defaultHttpClient(timeoutSeconds)` dans `core/net/Http.kt:20,23-30`,
  60 s par défaut (`DEFAULT_TIMEOUT_SECONDS`, `Http.kt:20`), les quatre timeouts alignés à la
  même valeur. Un seul mapper JSON : `org.json` (`net/Json.kt:5-6`) — fourni par Android, donc
  **aucune dépendance de prod ajoutée pour le JSON**. Pas de Retrofit, pas de
  kotlinx-serialization.
- **`Result.flatMap` n'existe pas** dans la stdlib → helper interne `Result.then` (`net/Http.kt`).
- **Erreurs typées et centralisées** : `Throwable.toApiError()` et `httpError(code, body)`
  (`net/Http.kt:51-72`) — 401/403 → clé invalide, 402 → facture, 429 → limite, et un 404
  Modal « invalid function call » → `BackendStopped` (pas de nouvelle tentative sur une app
  arrêtée).
- **Tests JVM sans réseau** : serveur maison `core/src/test/java/dev/sam3vision/core/TestHttpServer.kt`
  (**129 lignes**, un thread par requête, `Content-Length` garanti, `delayMs` pour tester les
  timeouts). Motif d'origine : `MockWebServer` absent du cache Gradle — **non revérifié ici**.
- **`org.json` côté JVM de test** : le jar mockable d'Android renvoie des valeurs par défaut →
  `testImplementation("org.json:json:20240303")` (`core/build.gradle.kts:40`).
- **`javax.imageio` / `java.awt` masqués** par le mockable jar : les tests de masque travaillent
  sur des en-têtes PNG synthétiques et des `IntArray` (`core/mask/MaskCore.kt`), les wrappers
  `Bitmap` (`core/mask/Masks.kt`) restent fins et ne sont pas testables en JVM.
- **Segmentation non bloquante** : `AnalyzeUseCase` avale l'échec de SAM —
  `sam?.segment(..)?.getOrNull().orEmpty()` (`core/usecase/UseCases.kt:17,23`) — et décrit quand
  même l'image.
- **Réglages chiffrés, avec repli silencieux** : `security-crypto` en priorité, sinon
  `PrivateFileSettingsStore` (fichier privé AES-256/GCM, clé Keystore non exportable) —
  `core/settings/SettingsStoreFactory.kt:14-16`. L'API `EncryptedSharedPreferences` est marquée
  *deprecated* (warning de compilation seulement, constaté à l'époque ; **non revérifié ici**
  faute de build).
- **Jamais de clé en dur** : `llmApiKey` vide par défaut, message « clé API absente —
  renseigne-la dans Réglages », échec immédiat sans appel réseau.
- **Logique pure d'abord** : cadence (`FrameRate`), régulation (`FrameGate`, `PipelineGate`),
  journal (`LiveTimeline`), annonces (`PositionAnnouncer`, `LiveNarrator`), gestes
  (`IhearGestureBrain`, `LongPressTrigger`, `TapOrHold`), distances (`DistanceMeter`, `LensSpec`)
  vivent dans `:core` et se testent sans Android.

## Décisions récentes (25/10) — ce qui est sur `master`

- **Direct en WebSocket `/live/ws`, sans repli HTTP sur les frames** (#137 `98a1f08`, puis #136
  `8f9876a` pour la sonde iSee). Une seule fabrique `liveClient()`
  (`core/net/LiveClientFactory.kt:14-31`) sert **les deux écrans** (Direct et page caméra
  iSee) : c'est le point d'entrée unique, pas un `new` dispersé.
  `POST /live` reste écrit dans `HttpLiveClient.kt:48` **uniquement** pour `/health` délégué et
  pour les tests JVM : ne pas le reprendre pour des frames.
- **Corrélation par `id`, une file par `id`** (`WsLiveClient.kt:14-17,29`) : le serveur peut
  répondre dans le désordre, un message dont l'`id` n'est pas en vol est ignoré ; l'attente est
  enregistrée **avant** l'ouverture du socket, sinon un refus de connexion laisse la frame
  pendue (`WsLiveClient.kt:42-45`).
- **Transport chaud injectable** : `WsTransport` (`WsTransport.kt:15`) avec `OkHttpWsTransport`
  en prod (`:45`) et un faux JVM en test — le WebSocket est testable sans réseau ni GPU.
  Le socket vit toute la session ; **chaque frame** est bornée par son propre timeout.
- **Trois délais distincts, assumés** : frame 10 s (`HttpLiveClient.kt:11`), première frame /
  réveil côté sonde 60 s (`ihear/LiveProbe.kt:406`), `/health` 180 s (`HttpLiveClient.kt:19`).
  Motif mesuré : un transport figé à 10 s échouait pendant les 26-50 s de réveil du conteneur
  (`LiveClientFactory.kt:9-12`).
- **Pastille de transport honnête** : « WebSocket » / « WebSocket KO », sans prétendre à un repli
  qui n'existe plus (`app/LiveMessages.kt:19-23`, `LiveViewModel.kt:450-452`,
  `LiveProbe.kt:322-323`).
- **`LiveMessages.kt`** : messages d'état, libellés de transport et contexte du journal extraits
  de `LiveViewModel` pour tenir sous les 500 lignes et rester testable en JVM — `LiveViewModel`
  fait 468 lignes à `8f9876a`. Même raison d'extraction pour `app/ihear/LiveProbeText.kt`.
- **8 concepts par défaut** (`core/live/LivePrompts.kt:20`), et non 2 : mesure 2026-10-25 sur
  `/live`, 5 tirs par point, même image — 2 concepts = 118 ms médian, 8 concepts = 118 ms médian,
  les prompts étant **batchés** côté serveur (`levers.batch_prompts = true`,
  `LivePrompts.kt:12-17`). La branche `feat/ihear-2-concepts` (person, chair) est en attente.
- **Cadence Direct réellement câblée : 350 / 500 / 900 ms** (`core/live/LiveSession.kt:33`) et
  régulation par **hystérésis** (`LiveSession.kt:91-105`, `RELAX_RATIO` `:144`, `REGULATE_MIN_SAMPLES` `:147`) : on ne rouvre un
  créneau que sous la moitié du budget, et seulement après trois mesures (`REGULATE_MIN_SAMPLES`),
  la première frame payant le démarrage du conteneur. La sonde iSee utilise `Cadence.Fast`
  (`LiveSession.kt:41-44`, cadence 100 ms introduite par #135 `3e61a57`).
- **Région = choix d'endpoint, jamais allumage de carte** : `resolveSamEndpoint()` dans l'ordre
  saisie manuelle > preset de région > URL injectée au build (`core/settings/SamRegion.kt:50-55`,
  `app/SamEndpoint.kt:13`) ; choisir une région efface une URL qui n'était que le preset
  (`app/GpuRegionSettings.kt:70`). `routing_region` est figé au 1ᵉʳ déploiement
  (`docs/MODE-US.md:62`). **Une seule GPU à la fois, toutes régions confondues.**
- **Masques dans la même surface que l'aperçu** (`app/CameraSurface.kt`) : un `AndroidView` est
  une `View` dessinée **après** le canevas Compose, donc les masques composés au-dessus passaient
  derrière l'aperçu — piège consigné (`docs/MASQUES-DEBUG.md`, `CHANGELOG.md` « 2026-10-25 (4) »).
- **Distances sur le téléphone** : sténopé + hauteurs connues, ±15-20 %, 0 GPU et aucune requête
  (`core/distance/DistanceMeter.kt`, `docs/DISTANCES.md`).
- **Intro sans marqueur persisté** : l'audio se rejoue à chaque ouverture froide et ne se coupe
  que par la voix (`app/ihear/IhearIntro.kt:30-33`, `IntroLogic.kt:31-32`) — choix assumé plutôt
  que « une seule fois à vie ».

## Ce qui n'est PAS sur `master`

`feat/latency-ms` (`e7b95b0`, 45 fichiers, **non fusionnée**) : cadence sans plancher
(`averageMs/4` borné 50-250 ms), client OkHttp **partagé** avec timeouts différenciés
(connect 3 s, write 5 s, read 60 s, callTimeout 0), corps de requête one-shot, parsing en
**octets**, LUT 256 niveaux pour la colorisation, PNG de masque décodé en `ALPHA_8`, codec de
masques `bits`, `canAcceptFrame` (admission avant conversion), `LiveTimings` + `TimingsText`.
Tous les gains chiffrés qui y sont attachés (8,50→0,66 ms par 12 masques, −12,8 Mo/frame) :
**non mesurés sur `master`**, et non mesurés ici.

À noter aussi : `TrackClient`/`HttpTrackClient`, `TrackRunner` et `core/predict/*` (suivi local
entre deux frames serveur) sont écrits dans `:core`, **aucun écran de `:app` ne les appelle** à
`8f9876a`. Deux commentaires de code sont périmés depuis le passage en WS :
`HttpLiveClient.kt:25` (`docs/LIVE-SPEC.md` absent du dépôt, ancienne app `sam3-live`) et
`LiveProbe.kt:49` (« http = repli automatique »).

## Côté backend — rappel pour qui parlerait du direct ici

Le conteneur déployé (`sam3-eu-a100`) tourne sur une image `sam3.1-fast` (`batch_prompts`, edge
518) **dont le source n'existe nulle part**, et le dépôt backend ne contient pas `/live/ws`. Rien
de ce qui est serveur n'est vérifiable depuis ce dépôt ; les références de latence du backend
(`LATENCE.md` : 82-95 ms serveur, A/R PC 240 ms, réveil 23,6 s, TLS+DNS 67,4 ms, frame 34,1 Ko)
sont **non mesurées ici**, et l'historique du 24/10 (294-298 ms serveur, A/R 472-503 ms eu-west,
1107-1115 ms us-east) est périmé depuis le WebSocket.
