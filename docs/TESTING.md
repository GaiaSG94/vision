# TESTING — sam-vision-kotlin

État vérifié le **2026-10-25** dans le worktree `sam-wt-docs-app` (HEAD détaché sur `origin/master` = `8f9876a`,
contenant #136 *et* #137 — `98a1f08` est ancêtre de HEAD). Les chiffres non revérifiés dans le code ou les
comptages sont marqués « non mesuré ».

## Compter les tests (ce worktree, 2026-10-25)

```bash
grep -rc "@Test" core/src/test --include="*.kt" | awk -F: '{s+=$2} END {print s}'   # 359
grep -rc "@Test" app/src/test --include="*.kt"  | awk -F: '{s+=$2} END {print s}'   # 109
grep -rc "@Test" app/src/androidTest --include="*.kt" | awk -F: '{s+=$2} END {print s}'  # 6
grep -rc "@Test" glasses-spike/src --include="*.kt" | awk -F: '{s+=$2} END {print s}'  # 11 (10 JVM + 1 instrumenté)
```

- **`:core` : 359 @Test JVM** · **`:app` : 109 @Test JVM** → **468 tests JVM** (réseau simulé, jamais d'appel réel).
- `:app` **androidTest : 6** (`cleanup/CaptureCleanupTest`, instrumenté) — seul module instrumenté hors glasses.
- `glasses-spike` : 10 JVM + 1 instrumenté (`MockGlassesTest`) — module du spike lunettes, hors recette app.
- ⚠️ Le commit #137 annonce « 427 tests verts » : c'était un comptage Gradle de **son** instant (déjà à `98a1f08`,
  le comptage d'annotations donne 353 + 107 = 460, pas 427). Depuis, #136 a ajouté des tests. **Le comptage
  Gradle à l'exécution n'a pas été refait dans ce worktree (build interdit par la consigne) : non mesuré ici.**
  Les chiffres ci-dessus sont des comptages d'annotations `@Test`, reproductibles par les commandes plus haut.
- Les totaux datés du fichier précédent (« 348 = 282 + 66 », ligne « 230 tests, 0 échec ») sont **périmés** (24/10).

## Commandes exactes

```bash
export JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2 ANDROID_HOME=$HOME/Android/Sdk
./gradlew :core:testDebugUnitTest --offline     # ~2 s avec cache ; BUILD SUCCESSFUL attendu
./gradlew :core:testDebugUnitTest :app:testDebugUnitTest --offline   # la commande du CI (.github/workflows/ci.yml:125)
./gradlew :glasses-spike:assembleDebug :glasses-spike:testDebugUnitTest --offline  # CI :127
bash scripts/build.sh                            # assembleDebug --offline, timeout 600 s, vérifie l'APK
```

Le JDK 17 Temurin est obligatoire : le JRE système 21 n'a pas de `javac` (`scripts/build.sh`, commentaires
d'en-tête). Détail des résultats : `core/build/test-results/testDebugUnitTest/*.xml`.

**Install téléphone réel (`2c6852ac`) : jamais `adb` nu** — passer par le verrou :
`~/bin/phone run --task "…" -- adb -s 2c6852ac install -r app/build/outputs/apk/debug/app-debug.apk`
(sortie 2 = occupé). Les `adb -s emulator-…` de ce doc visent l'émulateur, non concernés.
**GPU Modal : une seule carte à la fois** — `~/bin/sam3-gpu status|busy` avant toute manoeuvre ; ne jamais
allumer une 2ᵉ carte, ne rien redéployer (voir ⚠️ plus bas).

## Direct : WebSocket `/live/ws` uniquement (#137, fusionné dans master)

- Le direct parle **exclusivement** à `/live/ws` : un message JSON par frame, corrélation par **`id`**
  (file par id, pas FIFO — réponses dans le désordre acceptées, message sans id correspondant ignoré,
  `core/…/net/WsLiveClient.kt:28-29,107-108`). `isConnected` expose l'état réel du socket (`:61`) ;
  `close()` remet cet état à zéro (**bug couvert par `isConnected_reflete_l_etat_du_socket`**, `:66-70`).
- **Plus aucun `POST /live` pour les frames** : `AutoLiveClient` et son repli HTTP ont été supprimés avec
  leurs 9 tests (#137). Si le socket ne s'ouvre pas, la frame échoue sur « WebSocket /live/ws injoignable »
  (`:130`) — voulu : la panne doit se voir, pas être masquée par un transport plus lent. Le **seul appel
  HTTP restant est `GET /health`** (réveil du conteneur), délégué au client HTTP par `WsLiveClient` (`:64`).
- **Pastille de transport** : « WebSocket » (vert) / « WebSocket KO » (rouge), textes dans
  `app/…/LiveMessages.kt:18-21`, rendu par `TransportChip` (`app/…/LiveBars.kt:77-84`) ; côté iSee, la ligne
  `transport : …` du détail vient de `LiveProbeText.kt:35` + `LiveProbe.kt:322-323` (via `LiveSession.socketOpen`,
  `LiveSession.kt:83`). La ligne `transport :` n'est **pas assertée** par `LiveProbeTextTest` — trou de couverture
  signalé le 25/10, à rejoindre dans un prochain lot.
- La fabrique partagée `liveClient()` (`core/…/net/LiveClientFactory.kt`) règle le **handshake à la durée de la
  frame** : un transport figé à 10 s échouait pendant les 26–50 s de réveil (piège (c)). Le direct l'utilise
  (`LiveViewModel.kt:119-122`, 468 lignes au `wc -l` du 25/10 — l'annonce « 469 » de #137 est à ±1) ; iSee
  l'utilise aussi, avec une session froide à 60 s (`LiveProbe.kt:406`) + boucle de réveil `/health` 6 × 10 s
  (`:409-410`) ; au-delà du premier appel, retour au délai court 10 s.
- `LiveState.kt:51` dit encore « HTTP (repli) » dans son commentaire : **commentaire périmé**, le repli n'existe
  plus depuis #137. Corrigible uniquement dans un lot code, pas ici.
- **Gains WS mesurés (2026-10-25, prod)** : aller-retour **médian 159 ms contre 173 ms en POST** ; serveur
  **50,3 ms contre 81,4 ms** (~31 ms gagnés par frame). Les chiffres antérieurs du doc (« 208 ms contre 435 ms,
  p95 228 contre 495 ») n'étaient pas datés : à tenir pour **périmés** au profit de ceux de 2026-10-25.
- **Latence de référence** (dépôt backend, `docs/LATENCE.md` — pas dans ce dépôt) : serveur 82–95 ms (p50 91) ;
  A/R PC p50 240 ms (min 142,6) ; réseau+file ≈ 150 ms ; frame 512×206 = 34,1 Ko (27 ↑ + 7 ↓) ; téléphone
  600–710 ms **dont ~80 ms non comptés** (capture + encodage + cadence : `stats.record` démarre après
  l'encodage et s'arrête avant le rendu) ; TLS+DNS 67,4 ms ; réveil conteneur 23,6 s. Les 423/703 ms de
  `LIVE-PERF.md` (backend) sont périmés. Historique 24/10 : serveur 294–298 ms, A/R 472–503 ms eu-west ;
  1107–1115 ms us-east.
- **Première frame** : le direct et la sonde sortent la première mesure du calcul de moyenne (elle paie le
  démarrage du conteneur — mesuré 17 s en WebSocket contre 40 s en HTTP, commentaires `LiveProbe.kt:291-293`,
  `LiveSession.kt:99-106`).
- ⚠️ **Conteneur déployé** : `sam3-eu-a100` tourne sur un build `sam3.1-fast` (`batch_prompts:true`, edge
  appliqué 518) **dont le source n'existe sur aucune machine**, et `/live/ws` n'est pas dans le dépôt backend.
  **Ne redéploie rien** : tu ne peux pas reconstruire ce qui tourne.
- **Branche `feat/latency-ms`** : ses commits « pastilles qui glissent », « gpu dans le détail », « distances
  dites pendant le REC », « BackendStopped » ont **déjà atterri sur master** via #135/#136/#137 (vérifié dans le
  code de HEAD). Ce qui **n'est pas sur master** : `e7b95b0` (masques en bits + LUT, capture gatée, chronologie)
  — rien d'équivalent dans ce worktree (aucune LUT dans `core/…/mask/`). Ne pas documenter ce contenu comme
  acquis.

### Classes de test liées au direct / WS (comptages du 25/10)

- `net.WsLiveClientTest` **10** : contrat du message envoyé, réponse → `LiveFrame`, **id non corrélé ignoré**,
  erreur serveur → `BadResponse` typé, délai dépassé, socket fermé → frame en vol échouée, socket qui ne
  s'ouvre pas → `Network`, `close` ferme le transport, **health délégué au HTTP**, `isConnected` remis à zéro
  par `close()`. Transport factice `TestLiveClients`/`FakeTransport`, zéro réseau.
- `net.LiveClientFactoryTest` **2** : le délai demandé s'applique au handshake comme à la frame ; défaut court.
- `live.LiveSessionTest` **18** : dont la régulation de créneaux (2 créneaux en cadence rapide, retour à 1
  au-delà du budget, hystérésis à 3 mesures) et l'absence de plancher de cadence. `socketOpen` n'a **pas** de
  test dédié : l'état du socket est couvert par `WsLiveClientTest` (`isConnected`, y compris la remise à zéro
  par `close()`).
- `LiveMessages.kt` n'a **pas de classe `LiveMessagesTest`** : ses règles pures sont couvertes par
  `app.LiveMemoryContextTest` (3 — `timelineContextOrNull`) et `app.LiveStateTest` (17 — lignes d'état,
  réveil GPU, « vision seule »). Le dire tel quel évite de chercher un fichier qui n'existe pas.

### Recette manuelle du direct (nécessite l'endpoint Modal réveillé)

1. Bouton « Direct (vidéo) » sur l'accueil → écran Direct ; attendre « Réveil du GPU A100 40 Go sur Modal… »
   (`WAKING_LINE`, `app/…/LiveState.kt:83` ; le réveil mesuré le 25/10 est de **23,6–26,8 s**, le budget code
   dit 30–50 s), puis la ligne d'état. Un tap sur l'aperçu, lui, relance la session (et en dehors du direct,
   lance le mode vidéo tap).
2. Vérifier la **pastille « WebSocket »** en vert ; la couper réseau Wi-Fi un instant ⇒ la pastille passe
   « WebSocket KO » **et y reste** (aucun repli) ; rétablir ⇒ la relance de session rouvre le socket.
3. Bouger devant la caméra : les pastilles de labels changent, `X ips` reste entre 0,3 et 3.
4. « Poser une question » → pastilles « label · il y a N s » ; choisir « il y a 5 s ». Demander « qu'est-ce qui
   était à gauche il y a 5 s ? » : la réponse doit nommer un objet réellement passé, pas l'état présent.
5. Sortir de l'écran : le direct s'arrête, la caméra est relâchée, la mémoire est vidée.
   Avant ce 25/10 le doc notait « Modal fermé, recette en attente de redéploiement » : le conteneur est
   déployé mais **non redéployable** (⚠️ ci-dessus) ; état GPU du jour = `~/bin/sam3-gpu status`, non mesuré ici.

## iSee REC — recette et attendus du panneau (textes exacts de `LiveProbeText.kt`)

Fenêtre caméra iSee : un tap démarre l'enregistrement (REC), un second l'arrête
(`IhearViewModel.kt:217`) ; hors REC **aucune frame ne quitte le téléphone** (`IhearCameraWindow.kt:80-81`).
Attendus, mot pour mot (formats, pas valeurs littérales) :

- ligne d'état : `SAM · X ips · Y ms · N objets · plus proche ≈ Z m` — le « plus proche » n'apparaît que si
  une distance est mesurée (`LiveProbeText.kt:12,20-21`) ; avant la première frame : `SAM · … ips · … ms · … objets`
  avec des points de suspension, et `erreur : SAM · <message>` si erreur sans frame.
- détail (`LiveProbeText.kt:29-45`, lignes dans cet ordre) : `endpoint : …` (raccourci à 46 caractères, milieu
  coupé), `frame : L×h`, `latence : Y ms · p95 P ms · serveur S ms`, `F frames · M masques`,
  `transport : WebSocket` (ou `WebSocket KO`), `suivis (N) : …`, `reconnus : …`, `gpu : …` **sur sa ligne**,
  `distances : chaise ≈ 1,1 m · personne ≈ 3,2 m` (trié du plus proche, 3 max, `:9,51-56`), `dit : <phrase>`,
  `erreur : …`. En mode dense (petite fenêtre), `endpoint`/`frame`/`gpu` sont retirés (`LiveDebugPanel.kt:56-71`).
- avant la première frame analysée, au centre de la fenêtre : `réveil du GPU SAM… (≈ 30 s au premier lancement)`
  (state `warming`) ou `en attente de la première analyse…` (`LiveProbeText.kt:75-80`) ; à un timeout en REC :
  `réveil du GPU en cours… (nouvel essai à la frame suivante)` (`LiveProbe.kt:356-358`).
- pastilles de distance sur l'aperçu : `≈ 1,2 m` (REC, la couleur suit le masque) ; sur la photo du double tap :
  `personne ≈ 3,9 m` (avec concept, `IhearCameraWindow.kt:179`).

Déroulé : Réglages → endpoint ou région EU/US (voir plus bas) → fenêtre caméra → tap → la ligne se remplit en
1–3 s une fois le conteneur chaud (premier appel : 23,6–26,8 s mesurés 25/10) → vérifier `transport : WebSocket`,
`ips` (pas de plancher de cadence côté iSee, 2 créneaux possibles sous budget 200 ms — `LiveSession.kt:36-46`),
p95, `distances :` et `dit :` si la voix est active → second tap : les masques restent, la vidéo est archivée.
Compte d'objets jamais remis à zéro sur erreur (`LiveProbe.kt:278-283`) : les derniers masques restent affichés.

## Vérification région EU/US et voix

- Réglages → boutons de région GPU, pleine largeur. Ordre de résolution de l'endpoint
  (`core/…/settings/SamRegion.kt:49-58`, câblé dans `ihear/ProbeWiring.kt:19-27` et le direct) :
  **saisie manuelle (hors preset) > preset de la région > URL injectée au build** (`sam.base.url` /
  `sam.us.base.url` de `local.properties` → `BuildConfig.SAM_BASE_URL`/`SAM_US_BASE_URL`, `app/build.gradle.kts:68-70`).
- La région **n'allume aucune carte** : elle n'élit que l'endpoint (`SamRegion.kt:4-9`) ; GPU Modal = une seule à
  la fois, toutes régions confondues.
- Test de la bascule : mettre la région sur US sans rien saisir d'autre → `/health` doit répondre côté `us-east`
  (la ligne `gpu :` du panneau le confirme). Historique 24/10 : us-east 1107–1115 ms d'A/R contre 472–503 eu-west.
- Couverture JVM : `settings.SamRegionTest` **8** (presets reconnus, défaut EU, bascule US, saisie manuelle gagne,
  preset hérité ne fige plus, injection build par région, nom inconnu → EU, presets distincts).
- **Voix** : l'annonce vocale exige la bascule « Annoncer les objets et leur distance » (`TtsSettings.kt:73`,
  défaut activée, `Settings.kt:27`) **ET** une clé ElevenLabs (`Settings.announcePositions`, `Settings.kt:124`).
  Couverture : `settings.SettingsAnnounceTest` **4** (dont « sans clé ElevenLabs rien n'est annoncé »).
  Recette test-voix : Réglages → « 🔊 Tester la voix » (§ « Voix » plus bas).

## Pièges à connaître (vérifiés dans le code le 25/10)

- **(a) z-order** : un overlay composé **avant** l'`AndroidView` passe **derrière** l'aperçu caméra → invisible.
  `LiveDistanceLayer` et les masques sont composés **après** : `IhearCameraWindow.kt:162-167`,
  `LiveScreen.kt:191-196` ; explication en dur dans `CameraSurface.kt:10-22` (masques = 2ᵉ enfant du
  `FrameLayout`, fusionnés en `CENTER_CROP`).
- **(b) voix muette sans erreur visible** : si la bascule « Annoncer les objets et leur distance » est coupée
  **ou** si la clé ElevenLabs est absente (`Settings.kt:124`) — rien ne le dit à l'écran, aucun message d'erreur.
- **(c) premier appel après réveil** : « réveil du GPU en cours… ». Le `callTimeout` 10 s tuait la 1ʳᵉ frame
  alors que le réveil mesure 23,6–26,8 s (25/10 ; budget code 30–50 s) — d'où handshake aligné sur la frame
  (`LiveClientFactory.kt:9-12`), session froide 60 s côté iSee (`LiveProbe.kt:135-143`) et réveil `/health`
  en tâche de fond (direct : 3 × 4 s, `LiveViewModel.kt:459-460` ; iSee : 6 × 10 s).
- **(d) APK d'un autre agent** : un build installé par un autre agent remplace le tien (verrou `phone` expiré
  entre-temps) ; symptôme : des comportements disparus reviennent. Vérifier `~/bin/phone run` et l'horodatage de
  l'APK installé avant d'incriminer son propre build.
- **(e) URL preset effacée** : une URL SAM qui n'est **que** le preset d'une région est effacée au chargement
  (`app/…/SettingsSeed.kt:24` `dropPresetUrl` + `SamRegion.isPreset`), sinon la région EU/US ne pourrait jamais
  s'appliquer. Une URL saisie à la main est conservée. Ne pas « corriger » cet effacement.
- **(f) pastilles qui se cachent** : deux objets voisins donnaient deux pastilles au même endroit ;
  `spreadLabels` (`app/…/DistanceLayout.kt:116`, dans master via #135) les fait glisser vers le bas
  (seuils `PILL_MIN_X_GAP = 150` / `PILL_MIN_Y_GAP = 76`, `:105,108`).

## Recette annonce vocale des positions **sans GPU** — ⚠️ partiellement périmée (25/10)

Écrite le 25/10 avec un faux serveur HTTP `python3 /tmp/mock_sam.py 8099` (`/health` + `/live`) :
**depuis #137 il n'y a plus de repli HTTP pour les frames — un mock qui ne sert que `POST /live` ne donne plus
rien.** Il faut un mock écoutant `GET /live/ws` (WebSocket, un message JSON portant l'`id` par frame) ; la recette
historique (sous-titre `🔊 Personne à gauche, chaise en bas à droite.` + `dumpsys media.player` en `audio/mpeg`,
coupable par la bascule Réglages) reste valide sur le principe. Ne pas oublier de remettre le vrai endpoint
après la recette.

## Tests JVM par domaine (comptages 2026-10-25 ; ancien total « 348 = 282+66 » périmé)

Réseau (`core.net`) : `WsLiveClientTest` 10 · `LiveClientFactoryTest` 2 · `LiveClientTest` 10 (client HTTP,
`/health` et consorts, via `TestHttpServer`) · `VideoClientTest` 8 · `LlmClientTest` 9 · `SamClientTest` 6 ·
`TrackClientTest` 12 · `ElevenLabsTtsClientTest` 7 · `ElevenLabsSttClientTest` 5 · `MetaAsrClientTest` 8 ·
`FallbackSpeechClientTest` 4 · `LiveNarrationClientTest` 3 · `HttpErrorTest` 3 (dont le 404 Modal
« invalid function call » → `BackendStopped`).
Direct/flux (`core.live`) : `LiveSessionTest` 18 · `TrackRunnerTest` 12 · `PositionTextTest` 10 ·
`LiveTimelineTest` 10 · `PipelineGateTest` 8 · `LivePromptsTest` 8 (dont **mesure 25/10 : 2 concepts = 118 ms
médian, 8 concepts = 118 ms** — les 8 concepts par défaut sont gratuits, `LivePrompts.kt:14-20`) ·
`PositionAnnouncerTest` 8 · `TimelineTextTest` 7 · `LiveNarratorTest` 7 · `LiveStatsTest` 5 · `FrameGateTest` 5 ·
`PrewarmGateTest` 4 · `FrameRateTest` 4 · `LiveMemoryFlowTest` 2.
Distances (`core.distance`) : `DistanceMeterTest` 12 · `LensSpecTest` 7 · `DistanceTextTest` 7 ·
`KnownSizesTest` 5.
Autres `:core` : `usecase.UseCasesTest` 10 · `predict.BoxTest` 10 · `predict.BoxPredictorTest` 10 ·
`gesture.LongPressTriggerTest` 10 · `SpeechUseCaseTest` 8 · `settings.SamRegionTest` 8 · `predict.MotionModelTest` 7 ·
`predict.BlockShiftTest` 7 · `settings.SettingsIntroTest` 6 · `glasses.CaptureGestureTest` 6 ·
`settings.SettingsCodecTest` 5 · `predict.ObjectAssociatorTest` 5 · `audio.SentenceChunkerTest` 5 ·
`mask.MaskCoreTest` 6 (base64 data-prefix, IHDR, luminance, colorisation, masque 255) ·
`usecase.DictationUseCaseTest` 4 · `text.TextSanitizerTest` 4 · `settings.SettingsAnnounceTest` 4 ·
`gesture.TapOrHoldTest` 4 · `settings.SettingsTtsTest` 3 · `model.ObjectLimitsTest` 3 ·
`gesture.IhearGestureBrainTest` 3 · `audio.WavTest` 3 · `settings.SettingsAsrTest` 2.
`:app` (109) : `LiveStateTest` 17 · `ihear.LiveProbeTextTest` 17 · `DistanceLayoutTest` 13 ·
`ihear.SwipeStepTest` 12 · `UiLogicTest` 8 · `tap.TapMachineTest` 8 · `TtsLogicTest` 6 · `tap.TapZonesTest` 6
(les 2 tests de l'ancienne `TapReleaseTest` y ont été absorbés) · `MicBusTest` 5 · `ihear.IhearDistancesTest` 5 ·
`cleanup.CaptureScannerTest` 5 · `LiveVoiceTest` 4 · `LiveMemoryContextTest` 3.
Aucun test ne touche le GPU ni le réseau réel ; `/live` et `/live/ws` sont simulés (`TestHttpServer`,
`TestLiveClients`).

## Module de nettoyage (instrumenté, 6 tests)

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --offline
adb -s emulator-5558 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5558 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5558 shell am instrument -w \
  -e class dev.sam3vision.app.cleanup.CaptureCleanupTest \
  dev.sam3vision.app.test/androidx.test.runner.AndroidJUnitRunner    # OK (6 tests), ~3 s
```

Preuves : capture de l'app supprimée · fichier hors périmètre intact · trace MediaStore dé-synchronisée oubliée
sans erreur · capture en cours d'écriture reportée · périmètre limité au privé · job unique sans réseau.

## Recette manuelle du mode tap (émulateur, jouée 2026-10-24)

```bash
adb -s emulator-5558 shell pm grant dev.sam3vision.app android.permission.CAMERA
adb -s emulator-5558 shell pm grant dev.sam3vision.app android.permission.RECORD_AUDIO
adb -s emulator-5558 shell am start -n dev.sam3vision.app/.MainActivity
adb -s emulator-5558 shell input tap 270 2045   # « Vidéo (tap) »
adb -s emulator-5558 shell input tap 540 1200   # tap court : « Launched » → Enregistrement
sleep 4
adb -s emulator-5558 shell input tap 540 1200   # second tap : « Sent » → Envoyé
```

Vidéo MP4 de 590 ko → 1,09 Mo envoyée en multipart (champs `model`, `video`, `prompt`), écran
« Envoyé — Deux mains montent une etagere. ». Sans endpoint : « Échec — endpoint SAM 3.1 vide » + annonce
`Failed`. Clips `launched/sent/failed.mp3` dans `files/announcements/` dès l'ouverture. (Mode tap = envoi vidéo
`HttpSam31VideoClient`, **inchangé par la bascule WS du direct**.)

## Recette manuelle émulateur (1080×2400 @ 420 dpi — emulator-5554)

```bash
EMULATOR_SERIAL=emulator-5554 bash scripts/install-emulator.sh   # fait tout ci-dessous
# ou à la main :
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell pm grant dev.sam3vision.app android.permission.CAMERA
adb -s emulator-5554 shell pm grant dev.sam3vision.app android.permission.RECORD_AUDIO
adb -s emulator-5554 shell am start -n dev.sam3vision.app/.MainActivity
```

Parcours validé (coordonnées px centrées sur les cibles uiautomator) :

1. Capture : « Photographier », « Direct (vidéo) », « Vidéo (tap) », « Galerie », « Réglages ». Un tap sur
   l'aperçu lance le mode tap, pas le Direct : viser par libellé. Endpoint injoignable → « Touche l'écran pour
   relancer le direct ».
2. Analyse (~5–15 s, LLM réel) : bandeau « Segmentation désactivée — renseigne un endpoint SAM dans Réglages. »,
   tags, description ; retour `input tap 160 95`.
3. Réglages : Base URL LLM (540 452), Clé (540 688), Modèle (540 924), URL SAM (540 1160), « Enregistrer »
   (540 1331), « Tester le LLM » (540 1499), « Tester SAM » (540 1667). Le mode tap réutilise la clé ElevenLabs
   des Réglages (sinon `local.properties`).
4. Dictée : champ « Dictée Meta — clé API » (540 1150) (échapper le `|` : `input text "LLM\|…"`), « Tester la
   dictée Meta » (540 2061) ⇒ « ASR KO — paiement requis (402) » = trajectoire bonne ; repli ElevenLabs Scribe
   automatique. Dans le Chat : « Dicter » (540 1978) ⇒ « Enregistrement… » ⇒ « Stop » ⇒ texte dans le champ.
   Appui long 300 ms partout ⇒ « J'écoute » (ElevenLabs) ⇒ « Transcription ». Recette : `docs/TEST-LONGPRESS-DICTEE.md`.
5. Screenshot : `adb -s emulator-5554 shell screencap /sdcard/s.png && adb -s emulator-5554 pull /sdcard/s.png
   /tmp/samvision_manual.png`. Clé visible nulle part (champ masqué `•••`).

## Voix (ElevenLabs)

1. Réglages → « Voix ElevenLabs » : clé et ID de voix (masqués).
2. « 🔊 Tester la voix » : voix premade (défaut Sarah) + compte free → phrase prononcée, « voix OK » (~2 s).
   Voix de bibliothèque (ex. Rachel `21m00Tcm4TlvDq8ikWAM`) → « voix ElevenLabs refusée (402) : voix de
   bibliothèque payante ou compte sans crédits… ». Aucun crash, aucun état « en cours » qui reste.
3. Clé vidée + « Enregistrer » → « clé ElevenLabs absente — renseigne-la dans Réglages » **sans appel réseau**.
4. Analyse d'une photo → « 🔊 Écouter » sous la description et sous chaque réponse du chat.
5. **Lecture automatique** (jouée 24/10, emulator-5556) : « Photographier » → description → « Poser une
   question » → réponse → « voix en cours… » → « lecture en cours… » (~18 s d'audio pour une réponse longue)
   → « voix OK ». Capture : `docs/chat-voix.png`. ⏹ Stop → retour à l'état inactif sans erreur.

## Intro d'accueil (première ouverture, sur appui)

1. `adb shell pm clear dev.sam3vision.app` puis relancer.
2. Écran de garde « SAM Vision / Touchez l'écran pour commencer », **aucun son** avant l'appui (écouter 5 s).
3. Appui → intro embarquée (`res/raw/intro_ihear.mp3`, voix Jade US, ~24 s) **sans réseau ni clé** ; caméra.
4. `force-stop` + relance : pas de garde, pas de lecture (drapeau `Settings.introPlayed`).
5. Réglages → couper « Intro à la 1re ouverture », `pm clear`, relancer : plus rien.
6. JVM : `settings.SettingsIntroTest` (6) + `app.UiLogicTest` (`introToggleLabel`, `introGateVisible`).

## Non-régression jouée après le mode tap (2026-10-24, emulator-5558)

Chaîne photo d'origine revalidée de bout en bout (`SpeechPlayer` factorisé, `newCacheFile` dans le stockage
privé, `Application` déclarée, 4ᵉ bouton) : « Photographier » → description LLM (`abstrait · pixelisé · …`) →
« Poser une question » → réponse du chat → **lecture automatique ElevenLabs** jusqu'à « voix OK ». Aucun crash
à froid (0 `FATAL EXCEPTION`), `cleanup.log` écrit, photo/vidéo dans `files/Pictures` et `files/Movies` avec
`.nomedia`, `assembleRelease` OK. **Cette chaîne ne couvre pas le WS du direct** : la refaire sur le direct
après toute retouche transport.

## Limites connues

- L'app reprend l'écran où elle était après `am start` sans `force-stop` ; pour rejouer depuis Capture :
  `am force-stop dev.sam3vision.app` avant l'étape 1.
- Coordonnées propres à 1080×2400/420 dpi et à l'état courant des écrans ; re-mesurer via `uiautomator dump`
  si l'UI bouge. `KEYCODE_BACK` peut sortir de l'app (préférer le bouton « Retour »).
- `tools/check-kotlin-limits.py` (heuristique accolades, faux positifs possibles) signalait le 24/10 :
  `AnalysisScreen()` 64 lignes — non corrigé, fichier réservé à l'agent UI.
- `install -r` échoue si un build antérieur a changé de signature : désinstaller d'abord.
- Les comptages de cette page sont des comptages d'**annotations** ; le nombre de tests exécutés par Gradle
  peut en différer (aucun test ignoré recensé au 25/10, mais l'exécution n'a pas été revérifiée ici).
