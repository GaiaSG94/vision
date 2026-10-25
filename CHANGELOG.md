# CHANGELOG — sam-vision-kotlin

> **Numérotation** : l'entrée **(11)** — « retour à 8 objets à l'écran par défaut » (`04e7f7c`,
> 25/10 19:34) — existe déjà sur `origin/master` mais pas dans la base de ce worktree (`8f9876a`) :
> elle n'est **pas dupliquée** ici. (12) à (18) = un chiffre par bullet, une sha par fait.

## 2026-10-25 (19) — la voix parle anglais (réglage « Langue de la voix »)

- **Demande user** : « dans l'app ihear, faire en sorte que la personne nous parle en anglais ».
  C'est un réglage, pas un fork : `SpeechLanguage` (`core/speech/`, `Fr` / `En`, **anglais par
  défaut**, lecture tolérante des valeurs inconnues), persisté par les deux stores
  (`speech_language` et `speechLanguage`). Une installation déjà en place bascule donc en anglais
  à la mise à jour — c'est le demandé ; « Voix en français (fr) » reste à un bouton des Réglages.
- **Ce que la langue pilote** : les annonces de scène (`PositionText` + `DistanceText.spoken`),
  les réponses du chat et du direct, la narration continue, le journal caméra joint comme contexte
  d'une question de mémoire (`TimelineText.context`), et la phrase de « Tester la voix ».
  **Ce qu'elle ne touche pas** : l'interface, qui reste en français — et la dictée, qui retranscrit
  la langue de celui qui parle.
- **Mots anglais, même géométrie** : `on the left` / `in the middle` / `in the top left` /
  `at the bottom`, distances rendues en `Locale.US` (`1.4 meters`, `85 centimeters`) — la voix lit
  le texte tel quel, une virgule décimale se prononcerait mal. Forme produite : `Person on the
  left, 1.4 meters away; chair in the middle, 3 meters away.`
- **La signature de scène reste française** (`PositionText.key`) : sinon basculer de langue ferait
  rebavarder toute la scène déjà annoncée.
- **Prompts LLM** : les quatre consignes codées en dur de `InferHubLlmClient` déménagent dans
  `core/net/LlmPrompts.kt`, une variante par langue, **mêmes clés JSON** (`description`, `tags`)
  pour que `parseAnalysis` continue de lire. Client factorisé dans `app/LlmFactory.kt` : iSee,
  Direct et photo reconstruisent le client LLM par le même chemin, donc avec la même langue.
- **Vérifié** : 30 tests JVM ajoutés (secteurs, phrases, distances, consignes, requête réelle
  contre faux serveur, aller-retour du codec, réglage), `assembleDebug` OK,
  `check-kotlin-limits` OK. Côté compte : Jade US (`TOXPlnYC7z5SQSV2Zgc5`) synthétise une phrase
  anglaise en `eleven_v4_turbo` → 200, 54 795 octets de MP3. Le modèle a été saturé pendant la
  recette (`429 capacity_saturated` sur `ali/qwen3.8-omni-flash`) : la consigne anglaise part bien
  dans `messages[0]`, démontré en JVM contre un faux serveur.
- Docs : `docs/VOIX-ANGLAIS.md` (table des secteurs par langue, ce qui change et ce qui ne change
  pas, recette).

## 2026-10-25 (18) — iSee : l'intro est dite avec le nouveau nom et annonce le balayage (#149)

- **MP3 régénéré** (`app/src/main/res/raw/intro_ihear.mp3`) : même voix Jade US
  (`TOXPlnYC7z5SQSV2Zgc5`), même modèle `eleven_v3`, **351 caractères** au lieu de 309,
  **24,08 s**, 386 237 octets, MP3 44,1 kHz mono 128 kb/s (`ffprobe`).
- **Texte** : « Welcome to iSee… », puis la phrase nouvelle « To listen to previous
  messages, swipe down. », dite **avant** « Disable iSee intro. » — le balayage vers le
  bas remonte aux bulles plus anciennes (`swipeStep`). `IHEAR_INTRO_TEXT` (bulle d'intro
  du chat) porte exactement le texte envoyé à ElevenLabs, caractère pour caractère.
- **Phrase de coupe** : « Disable iSee intro ». `isIntroDisablePhrase` compare désormais
  sans espaces ni ponctuation : la dictée écrit souvent « I see » en deux mots.
- Réglages, commentaires, `docs/ELEVENLABS-TTS.md` et `docs/TESTING.md` alignés ;
  `app.UiLogicTest` teste l'ordre swipe ↔ désactivation et la variante « I see ».
- **Non vérifié** : aucun contrôle d'écoute du nouveau MP3 ici (pas de sortie audio) ;
  `ffprobe` donne 24,08 s, mp3, 44,1 kHz, mono, 128 kb/s.

## 2026-10-25 (12) — iSee : la liaison caméra ne dépend plus de la bulle média (`9856d9e`, #139, 19:34)

- **Symptôme mesuré sur le téléphone** (build `1.0.139`) : « Lifecycle unavailable » puis « Camera not
  ready » au premier tap — `bind()` cherchait le cycle de vie **dans l'arbre de vues** de l'aperçu
  (`findViewTreeLifecycleOwner`), alors que la bulle média n'est composée qu'une fois l'enregistrement
  demandé : l'aperçu n'était pas encore à l'écran.
- **`bind(owner: LifecycleOwner)`** prend le cycle de vie de l'**activité** ; l'aperçu se montre quand
  la bulle apparaît (commentaire `IhearCameraPane.kt:95-106` **tel que publié par `9856d9e`** — la
  base de ce worktree, `8f9876a`, porte encore l'ancien `bind()` sans argument).
- **`CameraGlue` porte la `PreviewView`** (`glue.preview`, créée à la liaison) ; `IhearCameraWindow`
  ne se rappelle plus `newPreviewView`, la bulle se contente de la montrer. 2 fichiers, +28/−10
  (travail : `fccd744`).

## 2026-10-25 (13) — iSee : la sonde passe au WebSocket et ses stats cessent de mentir (`8f9876a`, #136, 19:20)

- **Fabrique unique `liveClient()`** (`core/…/net/LiveClientFactory.kt` + `LiveClientFactoryTest`) :
  `WsLiveClient` d'abord, repli `POST /live` au premier échec structurel — Direct et page caméra iSee
  partagent le même chemin (9 fichiers, +223/−25).
- **Le handshake reprend le délai de la frame** (session froide 60 s, `LiveProbe.kt:406`) : figé à 10 s
  il échouait pendant les 26–50 s de réveil et le repli HTTP devenait définitif pour la session
  (`564123d`, `d20f5db`, `7b6e41f`).
- **`FrameRate`** (nouveau, fenêtre glissante de 8 instants, `DEFAULT_WINDOW = 8`) : la cadence se
  compte **à l'envoi**, plus à la réponse — le panneau affichait « 40,0 ips · 17192 ms · 2 frames »
  après un réveil (deux frames finies à 25 ms d'écart). La frame de réveil (17 s en WS contre 40 s en
  HTTP, commentaire `LiveSession.kt:96-99`) sort des stats et le régulateur attend **3 mesures**
  (`REGULATE_MIN_SAMPLES`, `LiveSession.kt:147`).
- **`transport : WebSocket` / `WebSocket KO`** dans le détail, branché sur `LiveSession.socketOpen` →
  `WsLiveClient.isConnected` (`LiveProbe.kt:323`) : plus de libellé collé. **Trou de couverture** :
  `LiveProbeTextTest` n'assertionne pas cette ligne (vérifié à `8f9876a`).

## 2026-10-25 (14) — Direct : `/live/ws` seul, plus aucun POST pour les frames (`973d256` 17:37, `98a1f08` #137 19:01)

- `973d256` (13 fichiers, +725/−24) : **`WsTransport`** (interface + impl OkHttp, injectable → tests
  JVM sans réseau) et **`WsLiveClient`** (140 l.) — corrélation par **`id`**, **une file par `id` et
  non FIFO** : réponses dans le désordre tolérées, message sans `id` correspondant ignoré
  (`WsLiveClient.kt:14-16,39-56`) ; `parseLiveFrame` partagé avec le client HTTP ; pastille de transport.
- `98a1f08` = **#137**, demande user du 25/10 (« websocket de base et plus de post ») : `AutoLiveClient`
  et son repli HTTP supprimés avec leurs 9 tests (+88/−250). Sans repli la panne se voit : « WebSocket
  /live/ws injoignable » et pastille **« WebSocket KO »** (`LiveMessages.kt:20,23`). Seul appel HTTP
  restant : `GET /health` (réveil), délégué par `WsLiveClient`. **`close()` remet `isConnected` à zéro**
  (test `isConnected_reflete_l_etat_du_socket`) ; `LiveViewModel` 502 → 469 lignes annoncées (468 au
  `wc -l`), textes extraits dans `LiveMessages.kt` (garde 500 lignes). 427 tests verts annoncés.
- **Gains mesurés en prod le 25/10** : aller-retour médian **159 ms en WS contre 173 ms en POST**
  (message de `973d256`) ; **serveur 50,3 ms contre 81,4 ms, soit ~31 ms par frame**
  (`WsLiveClient.kt:11`, repris `LiveClientFactory.kt:7-8`). Sont **périmés** : les chiffres non datés
  encore lus à la base de ce worktree dans `docs/TESTING.md` (« 208 ms contre 435 ms », « p95 228 contre
  495 ») et l'historique du 24/10 (294–298 ms serveur, A/R 472–503 ms eu-west, 1 107–1 115 ms us-east).
- ⚠️ **Le conteneur déployé n'est pas dans les dépôts** : `/live/ws` ne figure dans **aucun** dépôt
  backend. `sam3-eu-a100` tourne sur un build `sam3.1-fast` (`levers.batch_prompts = true`, edge appliqué
  **518**) **dont le source n'existe sur aucune machine**, alors que `master` du backend répond
  `model: sam3.1` sans ces leviers. **Ne rien redéployer** : on perdrait ≈ **−235 ms de serveur**
  (340 ms → 90–95 ms) **et le WebSocket** (`docs/LATENCE.md` de `75a6267`, `docs/TESTING.md`). Le WS est
  donc un contrat serveur **non versionné** : à réécrire dans le dépôt avant toute reconstruction.

## 2026-10-25 (15) — iSee : les chiffres posés par #135 sans être écrits (`3e61a57`, 18:43, 30 fichiers +1480/−104)

Les entrées (4), (9) et (10) disent les masques, les distances et la voix ; la cadence et la frame de
cette même fusion n'étaient pas chiffrées.

- **Frame iSee 448 px / q55** (`IhearCameraPane.kt:37,40`) : l'encodage et l'upload sont le vrai coût,
  pas le serveur ; le Direct garde **512 px / q65** (`LiveScreen.kt:47-48`). **Coquille relevée** :
  l'entrée (4) ci-dessous annonce « 512 px, q65 » pour la page caméra d'iSee — le code de `3e61a57`
  comme celui de HEAD disent 448 / q55 ; le texte historique n'est pas réécrit.
- **`Cadence.Fast`** (`LiveSession.kt:41-47`) : plus de plancher (`fastMs = 0`), 2 créneaux possibles
  (`FrameGate.capacity` réglable), **budget de latence 200 ms** avec hystérésis (`RELAX_RATIO = 0,5`) —
  mesuré 115 ms de GPU mais 344 ms de latence à deux en vol : le backend sérialise, donc le régulateur
  referme à 1 créneau (carte à ~55 % avec un seul créneau, d'où l'essai à 2).
- **Le REC réveille la carte** (`LiveProbe.wake()` : `/health` 6 essais espacés de 10 s) et dit pourquoi
  il n'y a pas encore de masque (« réveil du GPU SAM… », « en attente de la première analyse… ») ;
  **premier `/live` patient à 60 s** ; encodage JPEG **pendant** la requête précédente (cycle =
  `max(encodage, requête)` au lieu de la somme) ; plafond d'objets pris des Réglages.
- **Huit concepts par défaut** (`LivePrompts.Default`) : mesure du 25/10 sur `/live` (5 tirs par point,
  même image, 4 objets max) — 2 concepts = 118 ms médian, 8 concepts = 118 ms : l'app déployée batche
  les prompts, les huit sont gratuits (`LivePrompts.kt:13-19`) ; le panneau affiche « suivis (N) » en
  plus de « reconnus ».

## 2026-10-25 (16) — iSee : la caméra vit dans une bulle du chat (`7c9af29`, 18:31, 8 fichiers +222/−94)

- L'aperçu n'est plus une fenêtre flottante : **une bulle du chat** (aperçu vidéo, photo + pastilles),
  avec liaison composée en permanence. C'est ce changement d'ordre de composition que #139 répare
  (entrée (12)).
- **Intro** : l'écrit reste toujours premier message, seule sa voix se coupe ; à bulle unique le swipe
  vertical **relit** la bulle, sans vibration, avec son icône.
- **Statuts** : les échecs SAM sur photo s'expliquent en statut au lieu du silence ; pendant une
  lecture le micro est jeté proprement avec son statut (fini le micro bloqué) ; textes en anglais.
- Porté deux fois : `8d36556` est le même travail sur la branche (doublon, message identique).

## 2026-10-25 (17) — latence : livré **en branche**, pas sur master (`e7b95b0` app, `75a6267` backend)

À lire comme du travail livré sur `feat/latency-ms`, **non fusionné** : aucune de ces briques n'est
dans ce worktree (vérifié à `8f9876a` : pas de `LiveTimings.kt`, pas de `bits` / LUT / `ALPHA_8` /
`canAcceptFrame` dans `core/…/mask`). Ne pas les documenter comme acquises.

- **app `e7b95b0`** (25/10 19:09, 28 fichiers, +1561/−136, 396 tests annoncés) : cadence
  `(averageMs / 4).coerceIn(50, 250)` sans plancher à 500 ms (−164 ms/cycle au poste fixe, −42 ms au
  téléphone) ; OkHttp **partagé** (connect 3 s, write 5 s, read 60 s, `callTimeout` 0 — réveil mesuré
  26,8 s), corps **one-shot** (fin du re-POST) ; masques par table de 256 niveaux, PNG décodé en
  `ALPHA_8`, codec **`bits`** (M1 + zlib) avec repli PNG ; admission de frame **avant** conversion
  (`canAcceptFrame` : 28 frames sur 30 étaient converties puis jetées) ; `LiveTimings` / `TimingsText`
  poste par poste — la latence affichée ne sous-estime plus d'~80 ms.
- **backend `75a6267`** (25/10 19:09 ; `master` backend = `9c40bb3`) : codec `bits` serveur (0,165 ms
  et 166 o par masque contre 1,015 ms et 504 o en PNG), `mask_encoding` (défaut `png`, contrat des APK
  installés), tri et troncature avant encodage, dédup des alias FR/EN, cache d'embeddings texte,
  découpe `prepare`/`encode_vision`/`run_concepts`/`finalize`, décodage **hors** verrou GPU,
  `@modal.concurrent(max_inputs = 2)`, `scaledown_window` 120 s (les 23,6 s de réveil après pause),
  `timings` JSON + en-tête `Server-Timing`, `scripts/bench_latency.py`, `test_units.py` scindé (153 OK).
- **Référence des chiffres : `docs/LATENCE.md`**, présent **seulement** dans `75a6267` (absent du
  working tree `master`) — serveur **82–95 ms (p50 91)**, A/R PC **p50 240,1 ms** (min 142,6),
  réseau + file **p50 150,1 ms**, frame **34,1 Ko** (27 ↑ + 7 ↓), téléphone **600–710 ms dont ~80 ms
  non comptés**, TLS + DNS 67,4 ms, réveil **23,6 s**. Leviers serveur : « code livré, **non déployé** » ;
  le cumul annoncé (−250 à −300 ms perçus, dont −235 ms serveur) attend un déploiement interdit
  (⚠️ entrée (14) et règle « une seule GPU Modal »).
- **Faux gains mesurés, à ne pas refaire** (`docs/LATENCE.md`) : gzip/zstd/br sur la réponse
  (−40 % d'octets = 0,12 ms à 192 Mbit/s), réduire le payload montant (27 Ko = 1,15 ms), PNG 1 bit /
  RLE / masques à résolution réduite (`bits` est sans perte), `work_edge` client sur le build déployé
  (refusé, edge figé 518), `?timing=1` (sans effet), JPEG natif CameraX (absent de CameraX 1.6.0).

## 2026-10-25 (10) — tout dans une seule app : masques, distances et voix dans la fenêtre caméra

- **Fusion** de `origin/master` (lunettes Ray-Ban Meta, mode US, narration, chat iSee) avec la
  branche `feat/ihear-rec-distances` (masques SAM, distances, voix, latence).
- **Fenêtre caméra flottante** : l'aperçu passe dans `CameraSurface` (aperçu + masques dans la
  même surface native, sinon les masques passent derrière), les pastilles « ≈ 1,2 m » sont
  composées après (donc au-dessus), et le panneau de debug passe en mode dense (9 sp, sans
  endpoint) pour tenir dans la demi-fenêtre : cadence, latence, objets, plus proche, p95,
  frames/masques, concepts, distances, phrase dite, erreur.
- **Voix** : `ProximityVoice` dit une fois chaque nouvelle phrase de `LiveProbe` pendant le
  REC — jamais par-dessus la réponse du chat, jamais sans clé ElevenLabs.
- **Réglages** : endpoint SAM résolu par `resolveSamEndpoint` (saisie manuelle > région EU/US >
  URL du build), bascule « Annoncer les objets et leur distance », bascule « Distances »,
  champ de vision caméra.
- Tests : **427 JVM verts**, `assembleDebug` et `check-kotlin-limits` OK.

## 2026-10-25 (4) — la page caméra montre les masques SAM et les stats de debug

- **Ce qui manquait** : l'accueil iSee (le parcours principal) filmait sans rien montrer de
  SAM — ni masque, ni chiffre. Un tap démarrait un MP4, le second l'archivait, point.
- **Masques en direct** : pendant l'enregistrement, chaque frame (512 px, q65) part à `/live`
  une à la fois (`LiveSession`, cadence adaptative) et revient en masques colorisés,
  superposés à l'aperçu au cadrage de la `PreviewView` (`MaskOverlayStack`, partagé avec le
  Direct). Hors enregistrement, aucune frame ne part : le GPU n'est pas réveillé pour regarder
  l'écran.
- **Stats de debug** (`LiveDebugPanel`) : « SAM · 1,4 ips · 640 ms · 8 objets · A100-40GB »,
  puis endpoint, taille de frame, latence moyenne + p95 + durée serveur, frames et masques
  analysés, concepts reconnus, dernière erreur.
- **`LiveProbe`** (`app/…/ihear/LiveProbe.kt`) : la brique qui relie caméra, `/live` et
  masques. Endpoint = `samBaseUrl` des Réglages, sinon `SAM_BASE_URL` du build ; nom du GPU
  lu une fois via `/health`.
- **Bug des masques invisibles** (Direct compris) : un `AndroidView` est une vraie `View`,
  dessinée par le `ViewGroup` **après** le canevas Compose — les masques composés au-dessus
  de l'aperçu passaient *derrière* lui. Symptôme : « 6 objets » côté backend, rien à l'écran.
  `CameraSurface` (`app/…/CameraSurface.kt`) met la `PreviewView` et la couche de masques dans
  le même `FrameLayout` (fusion `CENTER_CROP`, même cadrage que l'aperçu) ; utilisé par la page
  caméra d'iSee **et** par le Direct.
- Panneau de debug : en haut de l'écran (il cachait la scène), lignes coupées à une ligne,
  et « réveil du GPU en cours… » au lieu d'un échec sec quand le conteneur Modal dort.
- Tests : +9 `app` (`LiveProbeTextTest`) → **348 JVM verts**, `assembleDebug` et
  `check-kotlin-limits` verts. Détail : `docs/MASQUES-DEBUG.md`.
## 2026-10-25 (9) — page caméra iSee : distances affichées et dites pendant le REC

- **Pastilles de distance** sur l'aperçu de la page caméra (`LiveDistanceLayer`, composé
  après l'`AndroidView` : au-dessus de l'aperçu, jamais derrière) et **distance du plus
  proche** dans la ligne d'état : `SAM · 1,5 ips · 622 ms · 7 objets · plus proche ≈ 1,1 m`.
- **Voix** : la phrase `PositionText` (objets + distances) est dite par ElevenLabs pendant
  l'enregistrement, une fois par scène, jamais par-dessus la réponse du chat.
- **`LiveProbe`** porte la mesure (`DistanceMeter` + `LensSpec.fromSettings`) et l'annonce
  (`PositionAnnouncer`), comme le Direct : même brique, aucun appel réseau en plus.
- **Panneau de debug inchangé** (latence moyenne/p95/serveur, frames, masques) et enrichi :
  ligne `distances : …` et `dit : …`.
- **Pastilles qui se chevauchaient** (`spreadLabels`) : deux objets voisins posaient deux
  étiquettes au même endroit (« ≈ 2,6 m » caché par « ≈ 2,5 m ») ; la seconde glisse vers le
  bas. Fonction pure, testée.
- **404 Modal « invalid function call »** : message actionnable (« backend arrêté (404) —
  relance-le : sam3-gpu on ») au lieu d'« erreur HTTP 404 ».
- Tests : +5 JVM (`LiveProbeTextTest` ×4 et `LensSpec.fromSettings`) → **362 verts**
  (292 core + 70 app). `assembleDebug` et `check-kotlin-limits` verts.

## 2026-10-25 (5) — la voix dit les distances (« Personne à gauche, à 1,4 mètre »)
## 2026-10-25 (9) — iSee : une pastille de distance par objet sur la photo

- **Le manque** : les distances n'existaient que sur l'écran Direct ; l'accueil iSee
  (chat + fenêtre caméra) n'en affichait aucune — le module `ihear/` ne touchait même
  pas au calcul. Or c'est l'écran par défaut de l'app.
- **Double tap = photo mesurée** : la photo part au `/segment` de SAM, les boîtes
  reviennent en pixels, et le sténopé sur les hauteurs connues (`DistanceMeter`) en
  tire une pastille par objet — **préfixée du concept** (« personne ≈ 3,9 m »), les
  masques n'existant pas dans cette fenêtre. Photo maintenue 6 s au lieu de 2,6 s
  quand des pastilles arrivent (`PHOTO_MEASURED_MS`), effacées avec elle.
- **Nouveau** `ihear/IhearPhotoDistances.kt` (69 l) : segmentation + mesure, calibrage
  réglage utilisateur → capteur lié (`IhearCameraActions.lensSpec`, lu par
  `lensSpecOf` au bind) → grand-angle de repli. Silencieux si la bascule « Distances »
  est coupée, si SAM est injoignable ou si aucun concept n'a de hauteur connue.
- **Réemploi** : `LiveDistanceLayer` accepte `topInset` (0 dp dans la fenêtre) et
  `withLabel` ; `screenLabels`/`labelText` restent purs et testés en JVM.
- **Tests** : `IhearDistancesTest` (5) + `DistanceLayoutTest` (+1) — 746 tests, 0 échec ;
  `assembleDebug` vert.

## 2026-10-25 (8) — lunettes Ray-Ban Meta (DAT) + fusion de `master`

- **Device Access Toolkit** `com.meta.wearable:mwdat-{core,camera,inputs,mockdevice}:1.0.1` :
  enregistrement auprès de l'app Meta AI, session Bluetooth, flux caméra 15 fps (MEDIUM), photo
  poussée dans l'app par le même chemin qu'une capture téléphone (`AppNav.GlassesBridge`).
  Mode dégradé complet : aucune fonction du téléphone ne dépend des lunettes.
- **Bouton capture, trois gestes** : appui court → photo + analyse (masques SAM), appui long →
  photo + chat (la question se pose sur le téléphone), double appui → message d'explication
  (le mode Direct existe dans l'app mais n'est pas porté sur les lunettes). Lissage des appuis
  dans `CaptureGestureBuffer` (`:core`, 350 ms de grâce, tests JVM).
- **minSdk 26 → 29** (exigence des AAR Meta) : **Android 10+ requis**, un appareil en Android 8
  ou 9 ne peut plus installer l'APK. Rien d'autre ne bouge pour le téléphone.
- **Fusion de `origin/master`** dans la branche (elle ne portait qu'un commit, master ~77 de
  plus) : accueil iSee (chat + fenêtre caméra), mode Direct avec narration LLM continue, voix
  ElevenLabs, dictée Meta (ASR), prédiction locale des boîtes, objets à l'écran réglables,
  nettoyage des captures (Room), `versionCode` = date du commit.
- **Doc** : `docs/META-GLASSES.md` mis à jour (accueil iSee, gestes réels, minSdk 29, fonctions
  du téléphone sans lunettes) ; `docs/glasses-dat-spike.md` renvoie désormais à cette doc.

## 2026-10-25 — Réglages : la clé InferHub embarquée se relit (bouton « Afficher »)

- **Constat** : la clé InferHub entre bien dans l'app au build (`local.properties` → `BuildConfig`
  → `seededFromBuild`), mais le champ « Clé API LLM » était masqué **sans moyen de la relire** :
  impossible de vérifier sur le téléphone que la bonne clé est en place, ni de la copier.
- **`LabeledField`** : les champs secrets (clé LLM, clé ElevenLabs, clé Meta) sont toujours
  masqués par défaut — capture d'écran, voisin — mais portent un bouton **Afficher / Masquer**.
- **Bug corrigé au passage** : `IhearViewModel` chargeait `prefs.load()` **sans**
  `seededFromBuild`, là où `AppViewModel` et `LiveViewModel` le faisaient. Conséquence : avant
  le premier « Enregistrer », l'accueil iSee (chat, photo) pouvait répondre « LLM non
  configuré » alors que les Réglages affichaient une clé. Les deux chemins sèment maintenant.
- Rien n'est écrit dans le dépôt : la clé reste dans `local.properties` (gitignoré) et dans le
  store chiffré de l'app. Couche 0 refuse de toute façon une clé `sk-…` dans le diff.

## 2026-10-25 — Réglages : mode US (GPU SAM 3.1 aux États-Unis)

- **Nouveau réglage `samRegion`** (`core/settings/SamRegion.kt`) : deux régions, **EU**
  (`sam3-eu-a100`, routage `eu-west`) et **US** (`sam3-us-a100`, routage `us-east`), chacune
  avec son preset d'endpoint. Section des Réglages « GPU SAM 3.1 — région » : deux boutons, la
  région active en couleur pleine, l'endpoint réellement appelé affiché dessous.
- **Résolution unique** (`resolveSamEndpoint`) : Base URL saisie à la main > preset de la
  région > URL injectée au build (`sam.base.url` / nouveau `sam.us.base.url`). Branchée sur le
  le Direct (`liveBaseUrl`), l'analyse photo (`AppViewModel`) et « Tester SAM » (qui affiche la
  région testée).
- **Migration douce** : une URL SAM qui n'est qu'un preset (injectée par le build ou persistée
  par une version antérieure) est effacée au chargement — sinon elle gagnerait sur la région et
  le mode US semblerait sans effet. Une URL tapée à la main est conservée.
- **Persistance** : `samRegion` ajouté aux deux stores (`sam_region` / `samRegion`), valeur
  inconnue ⇒ EU.
- **Outil** : `~/bin/sam3-gpu-us` (enrobage `SAM3_APP=sam3-us-a100 SAM3_REGION=us
  SAM3_ROUTING=us-east` de `sam3-gpu`) — même bail, même watchdog, même règle « une seule GPU
  à la fois ». L'app ne choisit que l'endpoint : elle n'allume aucune carte.
- Docs : `docs/MODE-US.md` (déploiement, coût ×1,15 de la région large `us`, pièges
  `routing_region` figé au 1er déploiement et 404 « invalid function call »).
- Tests : +8 core (`SamRegionTest`) → **293 core + 66 app verts**, `assembleDebug` et
  `check-kotlin-limits` OK.

## 2026-10-25 (7) — Direct : narration LLM continue des frames

- **`describeFrame` + `LiveNarrator`** : une frame éligible toutes les 2 s, jamais deux en
  vol, part au LLM vision avec les objets suivis **et la description précédente** — le
  modèle dit ce qui a changé au lieu de répéter la même phrase.
- **Carte de narration** dans le direct (3 lignes, erreur affichée sans effacer la dernière
  description connue).
- Fonctionne **sans détecteur SAM** : la narration porte sur la frame gardée pour les
  questions, donc elle démarre même quand le GPU est injoignable.
- Tests : `LiveNarratorTest` (cadence, continuité, échecs, bornes) et
  `LiveNarrationClientTest` (contrat HTTP `describeFrame`) — `:core:test` +
  `:app:testDebugUnitTest` verts, `check-kotlin-limits` OK.

## 2026-10-25 (6) — pas de script d'installation (demande user) : seul le versionCode reste

- **Retiré** : `scripts/install-phone.sh`, `docs/PHONE-INSTALL.md`, la règle « seule porte
  d'installation » d'`AGENTS.md`, les renvois du README et de `docs/TESTING.md`, et le cron
  du poste. L'installation reste donc `adb install -r`, sans outil maison.
- **Gardé** : `versionCode` = date du commit HEAD (`app/build.gradle.kts`), `versionName` =
  `1.0.<commits>-<sha>`. Android refuse par défaut une build plus ancienne
  (`INSTALL_FAILED_VERSION_DOWNGRADE`) : un APK périmé ne peut plus écraser celui du
  téléphone, sans une ligne de script.

## 2026-10-25 — le plafond d'objets est bien persisté (correctif)

- **Bug trouvé à la recette téléphone** : le réglage « Objets à l'écran » changeait bien
  dans l'UI, mais revenait à 4 après redémarrage. `EncryptedSettingsStore` (le store
  réellement utilisé) ne lisait ni n'écrivait `max_objects` — seul le repli fichier
  chiffré le faisait. Les deux stores sont désormais alignés.
- **Parité documentée** dans `EncryptedSettingsStore` : tout champ ajouté à `Settings`
  doit être écrit dans `save` et relu dans `load`, sinon il retombe sur son défaut
  silencieusement à chaque lancement.

## 2026-10-25 — nombre d'objets à l'écran réglable (Réglages)

- **Réglage « Objets à l'écran »** : section dédiée dans Réglages (deux gros boutons − / +,
  valeur affichée en gros), de 1 à 12, **4 par défaut**. Il pilote les deux modes d'un coup :
  le direct envoie ce plafond à `/live` (`max_objects`) et la photo tronque la liste de
  `/segment` — donc moins de masques, moins d'overlays, description LLM alignée.
- **Une seule source de vérité** : `ObjectLimits` (`DEFAULT_ON_SCREEN = 4`, bornes 1..12,
  `clamp`, `step`). Le réglage est borné à l'enregistrement, à la lecture du fichier de
  réglages et à l'usage : un fichier ancien ou trafiqué ne peut pas sortir de l'intervalle.
- **Persistance** dans les deux implémentations (`EncryptedSettingsStore`, codec du fichier
  chiffré) ; clé absente = 4, donc aucune migration.
- Tests : `ObjectLimitsTest` 3, `SettingsCodecTest` 4, `UseCasesTest` (+plafond réglé),
  `LiveSessionTest` (+plafond transmis).

## 2026-10-25 — 4 objets maximum à l'écran

- **Plafond unique `ObjectLimits.MAX_ON_SCREEN = 4`** (core) : le direct envoie
  `max_objects = 4` à `/live` (le serveur élague les scores les plus faibles, donc moins
  de masques à décoder et d'overlays à dessiner) et la photo tronque la liste rendue par
  `/segment` dans `AnalyzeUseCase`, labels LLM compris.
- Tests : `LiveSessionTest` (plafond par défaut) et `UseCasesTest` (9 objets → 4).

## 2026-10-25 — réveil préventif du GPU à l'ouverture de l'écran caméra

- **Le délai de réveil passe derrière la préparation.** `AppViewModel.prewarmGpu()` lance un
  `/health` dès que l'écran caméra s'affiche : le conteneur Modal A100 (30-40 s à froid)
  démarre pendant que l'on cadre, au lieu d'attendre le tap sur « Direct (vidéo) ».
- **Un seul réveil par fenêtre de 2 min** (`shouldPrewarm`, pur et testé) : pas de carte
  rallumée à chaque navigation, et le tap garde son propre réveil patient (`wakeGpu`).

## 2026-10-25 (3) — distances : pastilles toujours visibles (bords et bandeau du haut)

- **Recette v2 sur le téléphone** (même A100, déjà chaude) : `Direct · 2,6 ips · 54 ms ·
  2 objets · plus proche 1,2 m`, pastilles « personne 1,4 m » / « chaise 1,2 m », plus aucune
  distance absurde sur un objet coupé. Restaient deux défauts d'affichage.
- **Pastille ramenée dans le cadre** (`pillTranslationX`) : un objet collé au bord droit
  sortait à moitié de l'écran (« ≈ 1, » tronqué).
- **Pastille hors du bandeau du haut** (`pillTranslationY`, 230 dp) : un objet dans le haut de
  l'image voyait sa distance cachée derrière « Arrêter / Réglages ».
- Tests : 273 JVM verts (+2 `app`), `assembleDebug` et `check-kotlin-limits` verts.

## 2026-10-25 — le Direct et l'accueil classique rouvrés (ils étaient inatteignables)

- **Bug de navigation** : la refonte iSee (accueil gestes-only) n'ouvrait plus ni le
  **Direct** (flux SAM en continu, annonces de position) ni l'**accueil classique**
  (photo → analyse avec masques) : `Route.Live` et `Route.Capture` n'étaient poussées
  par personne, donc **inatteignables** — l'annonce vocale des positions n'était
  testable que par le code.
- Nouvelle section **« Autres écrans »** dans les Réglages (seul écran à boutons
  encore accessible depuis l'accueil iSee, via le retour système) : « Mode Direct
  (vidéo en continu) » et « Accueil classique (photo + analyse) ».

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
## 2026-10-25 — la voix dit où sont les objets (« Personne à gauche, chaise à droite »)

- **Annonce vocale des positions** (ElevenLabs) : dès que la scène change, en mode **Direct**
  comme après l'**analyse d'une photo**, la voix dit où sont les objets —
  « Personne à gauche, chaise en bas à droite. » Secteurs gauche/centre/droite et
  haut/bas, ordre de lecture gauche → droite, label dédoublonné, 3 objets au plus puis
  « et N autres objets ». Nouveau `core/live/PositionText.kt` (pur) et
  `core/live/PositionAnnouncer.kt` (anti-bavardage : 2 frames de stabilité, jamais deux fois
  la même scène, 4 s minimum entre deux annonces).
- **Sous-titre sur le Direct** : la phrase annoncée s'affiche sous la barre d'état (🔊) — la
  recette se lit à l'écran, sans écouter.
- **Bascule « Annoncer la position des objets détectés »** dans Réglages
  (`Settings.ttsAnnouncePositions`, vrai par défaut, persistée par les deux stores). Coupée :
  plus aucune annonce, la voix du chat reste intacte.
- **Jamais deux voix en même temps** : `Mutex` dans `LiveViewModel`, dossier audio dédié
  (`cacheDir/positions`), silence pendant une question sur la mémoire du direct.
- **Bug corrigé au passage** : seule la vue photo complétait les réglages avec les valeurs du
  build (`local.properties`) ; `LiveViewModel` lisait le store brut, donc le **Direct restait
  muet** quand la clé ElevenLabs venait du build. `seededFromBuild()` est désormais partagé
  (`app/SettingsSeed.kt`) et appliqué par les deux vues.
- Tests : `core.live.PositionTextTest` 8, `core.live.PositionAnnouncerTest` 7,
  `core.settings.SettingsAnnounceTest` 4 → **235 `:core` + 55 `:app`** verts,
  `assembleDebug` OK, `tools/check-kotlin-limits.py` OK (`TtsSettings` découpé en
  `VoiceSwitches`). Recette émulateur sans GPU jouée (faux `/live`) : sous-titre + MediaPlayer
  MP3 actif, silence bascule coupée. Détail : `docs/ELEVENLABS-TTS.md`, `docs/TESTING.md`.


## Historique antérieur au 2026-10-25

Les entrées du 24/10 et avant sont dans [`docs/CHANGELOG-2026-10.md`](docs/CHANGELOG-2026-10.md) (archive, texte inchangé).
