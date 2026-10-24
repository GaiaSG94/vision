# CHANGELOG — sam-vision-kotlin

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
