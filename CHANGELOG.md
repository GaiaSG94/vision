# CHANGELOG — sam-vision-kotlin

## 2026-10-25 (2) — distances : rien sur un objet coupé, pastilles centrées (recette téléphone)

- **Recette sur le téléphone réel** (`2c6852ac`, Direct sur l'A100) : les pastilles
  tombent juste (« ≈ 2,0 m », « ≈ 2,1 m », « ≈ 2,3 m », « plus proche 2,0 m » dans la
  ligne d'état) mais deux défauts sont sortis de l'image.
- **Objet coupé par le bord haut ou bas ⇒ plus de distance** (`Pinhole.isVerticallyCut`,
  marge 2 px) : une couette segmentée à tort en « personne », collée aux deux bords,
  affichait **31 m**. La boîte ne mesurant plus la hauteur réelle, la valeur était fausse
  avec un bel aplomb. Gauche/droite restent acceptés (seule la hauteur porte la mesure).
- **Pastilles centrées sur la boîte** (ancre au milieu horizontal, ramenée dans le cadre,
  `graphicsLayer translationX`) : trois objets touchant le bord gauche empilaient leurs
  étiquettes au même endroit.
- Tests : 271 JVM verts (216 `core` + 55 `app`, dont deux coupes de boîte, centrage, cadre), `assembleDebug` vert,
  installée et revue sur le téléphone. Détail dans `docs/DISTANCES.md`.

## 2026-10-25 — clic « Direct (vidéo) » = réveil garanti de l'A100 40 Go sur Modal

- **Le réveil passait avant la fin du démarrage.** `/health` utilisait le client du direct,
  limité à 10 s (callTimeout compris) ; un conteneur Modal froid répond en ~33 s mesuré
  (jusqu'à ~150 s si l'image se reconstruit). Chaque essai échouait donc avant que la carte
  soit prête, et l'écran affichait une erreur réseau au lieu du GPU. `WAKE_TIMEOUT_SECONDS`
  (180 s) est dédié à `/health` : une seule requête monte avec le conteneur.
- **Frames jetées tant que le GPU n'a pas répondu** (`warm`) : plus de rafale d'erreurs
  « /live timeout » pendant le démarrage à froid ; le flux démarre dès que la carte est chaude.
- **Libellé d'état explicite** : « Réveil du GPU A100 40 Go sur Modal… » pendant le démarrage,
  puis la ligne de cadence avec le nom court du GPU (`shortGpu` : « NVIDIA A100-SXM4-40GB » →
  « A100-SXM4-40GB »).

## 2026-10-25 — distances caméra→objets sur le direct (sténopé, sans GPU)

- **Pastille « ≈ 3,2 m » sur chaque objet suivi** du Direct, dans la couleur de son
  masque, posée au-dessus de sa boîte ; distance de l'objet le plus proche dans la ligne
  d'état (« Direct · 1,5 ips · 620 ms · 3 objets · plus proche 1,4 m · A100 ») et une
  pastille par concept en bas (« personne 2,1 m »).
- **Aucun GPU, aucune requête** : `core/distance` calcule tout sur le téléphone.
  `Pinhole` applique `d = H réelle × focale(px) / hauteur(px)` ; la hauteur réelle vient de
  `KnownSizes` (table label → mètres, casse/accents/pluriel neutralisés, **aucune distance
  pour un concept inconnu**), la focale de `LensSpec` (champ de vision lu sur le capteur à la
  liaison par l'interop Camera2, sinon le champ saisi en Réglages, sinon 67°).
- **Lissage par piste** (`DistanceSmoother`) : EMA à deux vitesses pour rester lisible à
  1-2 ips, bond > 50 % pris presque tel quel, piste muette 2 s oubliée. `LiveTimeline.record`
  rend désormais l'identité de chaque détection **dans son ordre** (les distances s'appuient
  dessus, les autres appelants ignorent le retour).
- **Réglages** : bascule « Distances sur le direct » (active par défaut) et « Champ de vision
  caméra (°, 0 = lecture du capteur) ». Précision annoncée ±15-20 % (étiquette « ≈ »,
  format cm sous 1 m, décimètre jusqu'à 10 m) : détail dans `docs/DISTANCES.md`.
- Tests : 265 JVM verts (+28 `core` : `LensSpecTest`, `KnownSizesTest`, `DistanceMeterTest`,
  `DistanceTextTest`, un cas de plus dans `LiveTimelineTest` ; +7 `app` : `DistanceLayoutTest`),
  `assembleDebug` vert, `check-kotlin-limits` vert.
## 2026-10-24 — un tap lance la vidéo, un appui long parle (les deux ensemble)

- **Tap sur l'aperçu de Capture = mode vidéo, enregistrement lancé d'office.** Le bouton
  « Vidéo (tap) » reste l'entrée explicite (sans auto-démarrage, pour se placer). Le tap est
  classé au lever par `core/gesture/TapOrHold` avec les seuils de l'appui long (300 ms,
  ~8 dp) : un appui tenu ne déclenche donc jamais la vidéo, et les commandes au-dessus
  gardent leurs taps.
- **Appui long = voix, y compris pendant la vidéo.** Android n'autorisant qu'un `AudioRecord`
  par app, la vidéo renonce à sa piste audio (`TapConfig.VIDEO_WITH_AUDIO = false`) :
  l'analyse demandée à SAM 3.1 est visuelle, et le micro reste libre pour la dictée.
  `MicBus` continue d'arbitrer (le mode tap ne réserve le micro que si l'audio revient).
- **Rappel visible** sur l'accueil : « Tap : vidéo · Appui long : parler ».
- Recette sur téléphone réel (1220×2712, API 35) : tap → « Enregistrement » ; appui long
  pendant l'enregistrement → « J'ÉCOUTE » puis « DICTÉE : [clicking] » (le micro a bien
  capté pendant que la vidéo tournait) ; second tap → « Échec — endpoint SAM 3.1 vide »
  (aucun envoi, l'endpoint n'est pas configuré). 156 tests JVM verts (122 `core` + 34 `app`,
  +4 `TapOrHoldTest`), `assembleDebug` vert.

## 2026-10-24 — intro vocale d'accueil (voix Jade US), jouée une fois et sur appui

- **Intro embarquée.** `app/src/main/res/raw/intro_ihear.mp3` (voix **Jade US**
  `TOXPlnYC7z5SQSV2Zgc5`, MP3 44,1 kHz mono, ~22 s) est livrée dans l'APK : aucune clé
  ElevenLabs, aucun réseau. `IntroAudio` recopie l'asset une fois dans `cacheDir/intro`
  (`MediaPlayer` ne lit qu'un chemin).
- **Un seul lecteur.** L'intro passe par le `SpeechPlayer` du TTS (`playFile`) : toute
  lecture de réponse coupe l'intro, et inversement. Aucun second lecteur audio.
- **Premier lancement, sur geste explicite.** Écran de garde tactile tant que
  `Settings.introPending` (intro autorisée et pas encore entendue) ; l'appui joue l'intro
  et persiste `introPlayed`. Bascule **« Intro à la 1re ouverture »** dans Réglages
  (`introToggleLabel`, `introGateVisible` : logique pure testée). Les deux stores
  (`EncryptedSharedPreferences`, fichier privé AES/GCM) persistent `introEnabled` et
  `introPlayed` ; un réglage d'avant l'intro garde l'intro active.
- **Découpage en phrases réutilisable** (`core/audio/SentenceChunker`,
  `core/usecase/SpeechUseCase`, `SpeechCache` LRU 6 entrées) : une phrase par requête
  ElevenLabs, rejouer une réponse ne re-consomme pas de caractères. Testé en JVM.
- Tests : `settings.SettingsIntroTest` 6, `core.audio.SentenceChunkerTest`,
  `core.usecase.SpeechUseCaseTest`, `app.UiLogicTest` +2 → **185 `core` + 45 `app`** verts,
  `assembleDebug` OK, `tools/check-kotlin-limits.py` OK.
- Docs : `docs/ELEVENLABS-TTS.md` (contrat, Jade US, intro), `docs/TESTING.md` (recette
  d'intro), `README.md`.

## 2026-10-24 — hermes : un conflit ouvre un chat ZeroClaw (InferHub)

- **Le gate ne dit plus « intervention humaine » et s'arrête là.** Sur
  `mergeable_state = dirty`, `gate.py` saisit l'agent `hermes` du daemon ZeroClaw
  (provider `custom.inferhub_ci` → `https://api.inferhub.dev/v1`, modèle `combo/cheap`)
  via A2A `POST /a2a/hermes` sur la machine du runner, **en processus détaché** pour
  rester sous les 5 min de `ci-budget`.
- L'agent rebase la branche de la PR, résout en gardant les deux intentions, valide
  (`:core:test`, `:app:testDebugUnitTest`, `assembleDebug`), pousse en
  `--force-with-lease` sur la branche de la PR et commente son compte rendu. Ni merge,
  ni `master`, ni gardes. 3 tentatives puis `hermes/intervention-humaine`.
- **Garde-fous** : une saisine par tête de PR (empreinte + cooldown 20 min), plafond de
  3 saisines par période, fork ignoré, labels `hold`/`hermes/intervention-humaine`
  respectés, `vars.HERMES_AGENT_RESOLVE=0` coupe tout, tout échec d'agent se traduit par
  un commentaire et jamais par une CI rouge.
- Fichiers : `.github/hermes/conflict_agent.py` (nouveau, stdlib seule),
  `.github/hermes/gate.py` (escalade unifiée `escalate()`), `.github/workflows/hermes-lite.yml`
  (env), `docs/CI-QUEUE.md` (section « Conflit : l'agent hermes ouvre un chat ZeroClaw »).
## 2026-10-24 — hermes : un conflit ouvre un chat ZeroClaw (InferHub)

- **Le gate ne dit plus « intervention humaine » et s'arrête là.** Sur
  `mergeable_state = dirty`, `gate.py` saisit l'agent `hermes` du daemon ZeroClaw
  (provider `custom.inferhub_ci` → `https://api.inferhub.dev/v1`, modèle `combo/cheap`)
  via A2A `POST /a2a/hermes` sur la machine du runner, **en processus détaché** pour
  rester sous les 5 min de `ci-budget`.
- L'agent rebase la branche de la PR, résout en gardant les deux intentions, valide
  (`:core:test`, `:app:testDebugUnitTest`, `assembleDebug`), pousse en
  `--force-with-lease` sur la branche de la PR et commente son compte rendu. Ni merge,
  ni `master`, ni gardes. 3 tentatives puis `hermes/intervention-humaine`.
- **Garde-fous** : une saisine par tête de PR (empreinte + cooldown 20 min), plafond de
  3 saisines par période, fork ignoré, labels `hold`/`hermes/intervention-humaine`
  respectés, `vars.HERMES_AGENT_RESOLVE=0` coupe tout, tout échec d'agent se traduit par
  un commentaire et jamais par une CI rouge.
- Fichiers : `.github/hermes/conflict_agent.py` (nouveau, stdlib seule),
  `.github/hermes/gate.py` (escalade unifiée `escalate()`), `.github/workflows/hermes-lite.yml`
  (env), `docs/CI-QUEUE.md` (section « Conflit : l'agent hermes ouvre un chat ZeroClaw »).
## 2026-10-24 (voix)

- **Voix ElevenLabs dans l'app** (`feat/jade-tts`) : la description d'analyse et les
  réponses du chat se lisent à voix haute. Voix par défaut **Jade US**
  (`TOXPlnYC7z5SQSV2Zgc5`, ID du compte vérifié en réel), modèle `eleven_v3`,
  MP3 44,1 kHz. Nouveau `ElevenLabsTtsClient` (pur JVM), `SentenceChunker` (découpage en
  phrases), `SpeechUseCase` + cache LRU 6 réponses, lecture `MediaPlayer` avec cache
  disque, boutons « Lire / Stop » (Chat, Analyse), interrupteur « Haut-parleur »,
  champs Réglages (clé, voix, modèle) et « Tester la voix ElevenLabs ». Migration douce :
  l'ancien défaut Rachel (`21m00Tcm4TlvDq8ikWAM`, `eleven_turbo_v2`) et la première Jade
  essayée (`g7LVvkPWALzPxOQbF6OE`, voice library) deviennent Jade US,
  une voix choisie par l'utilisateur n'est pas touchée. 30 tests JVM ajoutés côté `:core`
  (109 core + 7 app au total), `assembleDebug` vert, garde-fou limites OK. Détail :
  `docs/ELEVENLABS-TTS.md`. Contrat HTTP vérifié en réel sur le compte du 24/10
  (HTTP 200 `audio/mpeg` pour `TOXPlnYC7z5SQSV2Zgc5`) ; pas de recette émulateur depuis
  ce serveur.

## Merge `master` → `longpress-muse-dictation` (2026-10-24)

Les deux lignes de features coexistent, sans doublon ni régression :
- **Mode tap + annonces + purge horaire** (master) et **dictée par appui long + repli Scribe**
  (branche) sont câblés dans le même `AppViewModel` : `Route.Tap` et le bandeau de dictée
  partagent la racine `AppNav`, `LongPressRootLayout` reste la vue racine de `MainActivity`
  (`onHoldStart`/`onHoldEnd`, permission micro au lancement, refus annoncé par `onMicDenied`).
- **Une seule classe par moteur ElevenLabs** : `ElevenLabsTtsClient` sert à la fois au chat
  (`speak` + `SpeechPlayer` version master, `play`/`playFile`) et aux bandeaux de dictée
  (`VoiceAnnouncer`). La clé des réglages alimente la voix **et** le repli Scribe
  (`FallbackSpeechClient`, `ApiError.AllProviders` quand les deux moteurs tombent).
- **Réglages** : section Voix ElevenLabs (clé, voix, autoplay, « 🔊 Tester la voix ») **et**
  section Dictée Meta (base URL, clé, modèle, « Tester la dictée Meta »). Les trois clés ASR
  traversent désormais aussi le codec du fichier privé (`SettingsAsrTest`, 2 tests).
- Découpage tenu : la dictée sort de `AppViewModel` dans `DictationFeature.kt` (163 l., ViewModel
  395 l.), helpers `RouteContent`, `BackendFields`, `SettingsActions` ; `tools/check-kotlin-limits.py` OK.
- Ce merge intègre aussi le correctif tap ci-dessous (envoi au relâchement) : la branche
  contient maintenant toute la pointe de `master`.

## Correctif tap court — envoi au relâchement (2026-10-24)

Signal user : « quand je lâche, ça n'envoie pas ». Reproduit et corrigé.

- **Le second tap était avalé** : `GestureDetector.onSingleTapConfirmed` attend le délai
  double-tap (~370 ms) et ne se déclenche pas sur deux taps rapides (Journal logcat :
  `onSingleTapUp` puis `onDoubleTap`, aucun `onSingleTapConfirmed`). L'action part désormais du
  **relâchement** (`ACTION_UP` court, non glissé, non appui long) — seuils `ViewConfiguration`
  par défaut conservés, appui long et glissement toujours sans effet.
- **Deux taps à ~200 ms produisaient une vidéo nulle** : CameraX finalise en erreur 8 avec 0
  octet sous ~300 ms. `MIN_RECORDING_MS = 350`, et un tap trop tôt est **remisé** puis exécuté
  par le battement dès la durée exploitable (rien n'est ignoré, rien n'est perdu).
- **Fichier lu trop tôt** : l'attente de `VideoRecordEvent.Finalize` (`stopAndAwait`, 4 s max)
  a été réparée — le `CompletableDeferred` de la prise est désormais passé à son propre callback,
  il n'était plus complété et l'envoi attendait le timeout puis déclarait « aucune vidéo à envoyer ».
- Recette émulateur rejouée : double tap rapide → « Envoyé » ; tap, pause 2 s, tap → « Envoyé » ;
  maintien 400 ms puis relâchement → enregistrement puis envoi ; appui long 900 ms, glissement et
  tap dans la barre de navigation → **rien**. 28 tests JVM `:app` + 82 `:core` + 6 instrumentés verts.

## Tap court vidéo + purge horaire (2026-10-24)

- **Mode tap court** (`app/.../tap/`, `docs/MODE-TAP.md`) : un tap n'importe où lance
  l'enregistrement caméra arrière (CameraX `VideoCapture`, `Quality.HD`, audio inclus), le
  second l'arrête et l'envoie à **SAM 3.1** en `multipart` (`POST {endpoint}/video`). Geste =
  `GestureDetector` aux seuils par défaut (tap court ≠ appui long ≠ glissement), barre de
  navigation système jamais déclenchante, TalkBack servi par `performClick()`.
- **Annonces ElevenLabs** : trois mots, rien d'autre — `Launched` / `Sent` / `Failed`
  (`AnnouncementPlayer`, liste blanche `announcementAllowed`). Clips pré-générés dans
  `filesDir/announcements/*.mp3` (vérifié sur émulateur) ; lecture locale sans focus audio,
  donc jamais sur la piste vidéo. Réseau > 500 ms ⇒ mot tu une fois, cache rempli en tâche de fond.
- **Purge horaire silencieuse** (`app/.../cleanup/`, `docs/NETTOYAGE.md`) :
  `CaptureCleanupWorker` (WorkManager, 1 h, `KEEP`, aucune contrainte réseau) supprime les
  images/vidéos du **seul** stockage privé de l'app (`CapturePaths`), plus les entrées
  MediaStore **tracées dans Room** (`traced_captures`, sélection `_ID` + `OWNER_PACKAGE_NAME`,
  jamais de plage de dates). Fichier en cours d'écriture (mtime < 60 s) reporté, dé-sync
  MediaStore ignorée sans planter, bilan journalisé (`cleanup.log`). Config : `CleanupConfig.kt`.
- **Captures recentrées dans le privé** : `newCacheFile` écrit désormais dans
  `getExternalFilesDir(PICTURES)` (`CapturePaths`), le cache n'est plus un angle mort du nettoyage.
- Dépendances ajoutées : `camera-video` 1.6.0, `work-runtime-ktx` 2.12.0, `room` 2.8.5 (+ KSP
  2.2.21-2.0.5), `androidx.test` 1.7.0/1.3.0. Schéma Room exporté dans `app/schemas/`.
- Tests : +8 client vidéo (`:core`, 82 au total), +16 JVM (`:app` tap/zones/scanner/machine, 26 au total), +6 instrumentés
  (`CaptureCleanupTest`, dont les 3 preuves demandées) — tous verts. `assembleDebug` APK 18 Mo.

## Voix ElevenLabs (2026-10-24)

- **Réponses lues automatiquement** : une réponse du chat est synthétisée puis jouée dès son
  arrivée (réglage `ttsAutoplay`, activé par défaut, interrupteur dans Réglages). Recette
  émulateur jouée : question → « voix en cours… » → « lecture en cours… » → « voix OK ».
- **Lecture à voix haute ElevenLabs (TTS)** : `core/net/ElevenLabsTtsClient.kt` (MP3 via
  `executeBytes`, erreurs typées dont `ApiError.PaymentRequired` sur 402), bouton « 🔊 » sur
  la description et les réponses du chat (`SpeakRow` partagé), section « Voix ElevenLabs »
  dans les Réglages, lecture `MediaPlayer` sans dépendance ajoutée.
  Réglages persistés (`tts_api_key`, `tts_voice_id`) avec rétro-compatibilité.
  Tests : 7 client + 2 persistance côté `:core`, 5 logique voix côté `:app` — tous verts.
  Voix par défaut **Sarah** (`EXAVITQu4vr4xnSDxMaL`), modèle `eleven_v4_turbo` : le TTS
  fonctionne sur le compte free, seules les voix de bibliothèque (ex. Rachel) répondent 402.

## 2026-10-24 — mémoire du direct : on peut interroger ce que la caméra a vu

- **Le flux filmé laisse une trace.** Chaque frame analysée par `/live` est consignée dans
  `core/.../live/LiveTimeline.kt` : anneau de 60 s (600 frames maximum), boîtes normalisées
  0..1 et **identité stable** — un objet qui reste à la même place garde son numéro d'une
  frame à l'autre (même label + centre à ±0,18 de largeur ; piste oubliée après 2,5 s sans
  apparition, donc un objet qui revient est une nouvelle piste).
- **Le journal devient le contexte du LLM.** `LiveTimeline.summary(from, to)` agrège une
  fenêtre (première/dernière vue, position, nombre d'images) et `TimelineText` la met en
  français avec des heures **relatives** : « « personne » (suivi n°1) : à gauche, vue la
  dernière fois il y a 3 s, apparue il y a 9 s, présente sur 5 image(s) ». `LlmClient.ask`
  et `AskUseCase.invoke` acceptent un `context` optionnel, envoyé en second message système
  avec une consigne dédiée : la question porte sur l'image présente **et** sur ce qui précède.
- **Écran Direct** : « Poser une question » ouvre le panneau mémoire (`LiveMemoryPanel.kt`) —
  pastilles des objets suivis (« personne · il y a 3 s », horloge rafraîchie chaque seconde),
  choix de l'instant (« maintenant », « il y a 5 s », « 15 s », « 30 s », fenêtre ±2,5 s),
  champ de question, attente, réponse. « Analyser la frame » garde l'ancien Chat photo.
- **Fenêtre envoyée au modèle** : 30 s de journal + la fenêtre demandée, plus la dernière
  image analysée (celle qui a produit la détection courante, et non la frame en attente).
- Tests : `LiveTimelineTest` 9, `TimelineTextTest` 7, `LiveMemoryFlowTest` 2,
  `UseCasesTest` +2 (contexte transmis / absent), `LiveStateTest` +4 → **108 core + 12 app**
  verts, `assembleDebug` OK, garde de taille OK.
- **`gradle.properties`** : le chemin en dur du JDK 17 (déjà refusé par la CI « pas de
  java.home codé en dur ») part — `JAVA_HOME` fait le travail, ici comme sur le worker.
- Reste ouvert : écrire le journal sur disque (revoir une session après coup), et brancher
  la mémoire sur le tracker (`TrackRunner`, ids serveur) quand l'endpoint `sam3-track` tourne.

## 2026-10-24

- **Voix 100 % ElevenLabs + repli de dictée** (`longpress-muse-dictation`) : le TTS système
  est supprimé (`SystemSpeech` → `VoiceAnnouncer`, MP3 ElevenLabs lu par MediaPlayer).
  La dictée essaie Meta puis, en cas d'échec, ElevenLabs Scribe (`ElevenLabsSttClient`,
  `FallbackSpeechClient`) ; la clé ElevenLabs des réglages sert aux deux. Téléphone :
  chaîne complète validée (Meta 402 → Scribe transcrit → voix).

- **Dictée par appui long Muse Transcript** (`longpress-muse-dictation`) : appui long
  ~300 ms n'importe où sur la surface de l'app (hors boutons et barre de navigation
  système) = enregistrement sans limite de durée ; au relâchement, l'audio part
  directement vers Meta `muse-voice-transcribe-1.0` et le texte s'affiche en gros
  caractères + lu par le TTS système. Machine à états `LongPressTrigger` (10 tests JVM),
  vue racine interceptante `LongPressRootLayout`, façade moteur `MuseTranscript`,
  permission micro demandée au 1er lancement (refus annoncé à la voix).
  Recette émulateur 7/8 points OK (le 8ᵉ, refus micro, à rejouer sur téléphone sans
  moteur TTS) — voir `docs/TEST-LONGPRESS-DICTEE.md`.

- **Dictée vocale Meta Muse dans le Chat** (`feat/muse-dictation`) : bouton « Dicter »,
  micro 16 kHz mono (60 s max, PCM en mémoire seulement), client `MetaAsrClient`
  (`POST https://api.meta.ai/v1/asr/transcribe`, multipart `request` + `audio`), texte inséré
  dans le champ de question. Réglages : base URL / clé / modèle ASR + « Tester la dictée Meta ».
  15 tests JVM ajoutés (79 core + 4 app), `assembleDebug` vert, recette émulateur jouée.
  **Blocage externe** : le compte Meta n'a pas de moyen de paiement → `402
  billing_not_configured` sur tout appel ; la trajectoire est validée jusqu'à cet code
  (plus de `400 Malformed multipart` — OkHttp ajoutait un `Content-Length` par part).
  Détail : `docs/META-ASR.md`.
- **Capture : le bouton « Direct (vidéo) » ouvre l'écran Direct** — un tap sur l'aperçu
  lançait le Direct dans la pile d'origine (`StartLiveOnTap`) ; master a depuis donné ce
  geste au mode vidéo tap, gardé ici. Le Direct reste accessible par son bouton dédié, à
  côté de « Vidéo (tap) » (`CaptureScreen.kt`).
- **Correctif : le mode Direct pointait sur une app Modal disparue** — l'URL
  `baptiste-faisy--sam3-live-sam3live-web.us-east.modal.run` répond `404 modal-http: invalid
  function call` sur tous les chemins (app `sam3-live` non déployée). Le backend vivant est
  l'app `sam3-eu-a100` (A100-40GB, eu-west) : `sam.base.url` corrigé, URL documentée dans le
  README avec la commande `modal` qui la donne. Vérifié sur téléphone : `Direct · 1,2 ips ·
  635 ms · 6 objets · NVIDIA A100-SXM4-40GB`.

- **Direct : un tap relance la session quand le flux est arrêté ou en erreur** — zone
  `RecoveryTap` sous les bandeaux de l'écran Direct, avec l'invite « Touche l'écran pour
  relancer le direct ». Jamais active pendant que le flux tourne (pas de coupure
  accidentelle) ; les concepts actifs sont conservés (`LiveViewModel.restartActive`).

- **L'app Expo est remplacée par une app Android native Kotlin/Compose**
  (`~/Bureau/sam-vision-kotlin`, modules `:core` pur-JVM testé + `:app` Compose/CameraX).
  L'app Expo part en `legacy-expo-app/` dans l'ancien dépôt.
- **Backend Modal fermé : 0 GPU, coût futur nul.** Plus de chez nous : le téléphone est
  client unique. `modal container list` vide, 16 apps stopped (0,97 $ metered compensés
  par crédits, facturé 0,00 $).
- **LLM branché directement sur InferHub** (`https://api.inferhub.dev/v1`, compatible
  OpenAI, `ali/qwen3.8-omni-flash`) : clés et facturation côté utilisateur, plus de proxy.
- **La segmentation SAM 3.1 redevient optionnelle** : `samBaseUrl` (Réglages) vide ⇒
  bandeau « Segmentation désactivée », la photo va au LLM sans masque. Coller l'URL de
  l'endpoint Modal redéployé (`deploy_backend.sh --skip-smoke`) la réactive ; contrat
  `/health` + `/segment` inchangé.
- `:core` : 31 tests JVM verts (clients InferHub/SAM, masques, réglages chiffrés,
  sanitizeur de texte, cas d'usage) via serveur HTTP maison, zéro réseau.
- `:app` : `assembleDebug` vert, APK ~21 Mo installé et testé sur emulator-5554
  (capture → description → chat).
- Docs et garde-fous ajoutés : `README.md`, `docs/TESTING.md`,
  `tools/check-kotlin-limits.py`, `scripts/build.sh`, `scripts/install-emulator.sh`.
- Reste ouvert : dépassement signalé `AnalysisScreen()` 64 lignes ; endpoint SAM non
  redéployé (défaut = désactivé) ; `security-crypto` API dépréciée (repli fichier privé).
