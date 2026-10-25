# SAM Vision — app Android native (Kotlin)

Photo → description par un LLM vision → questions/réponses sur la photo. Client unique :
pas de backend à nous. LLM via **InferHub** (`https://api.inferhub.dev/v1`, compatible OpenAI,
modèle par défaut `ali/qwen3.8-omni-flash`). Segmentation **SAM 3.1 optionnelle** : un endpoint
HTTP (Modal redéployé) saisi dans Réglages ; vide ⇒ bandeau « Segmentation désactivée » et la
photo part directement au LLM. Modal est fermé (0 GPU) — voir l'ancien dépôt
`~/Bureau/sam3-vision-app/docs/MODAL-CLOSED.md`.

## Ce que voit l'utilisateur (captures décrites, 1080×2400)

- **Accueil iSee** : chat plein écran + fenêtre caméra flottante. **Lunettes Ray-Ban Meta** :
  bloc « Lunettes Ray-Ban Meta » des Réglages — appui court sur le bouton capture → photo
  analysée, appui long → photo + chat (`docs/META-GLASSES.md`).
- **Capture** : plein écran sombre, titre « SAM Vision », zone d'aperçu caméra, trois
  boutons : « Photographier » (large, centre bas), « Galerie » (à gauche), « Réglages » (à droite).
  **Un tap n'importe où sur l'aperçu (hors boutons) lance le Direct (vidéo).**
- **Direct** : aperçu + masques SAM en superposition, **distance estimée sur chaque objet**
  (« ≈ 3,2 m » au-dessus de sa boîte, plus proche dans la ligne d'état), barre d'état
  (`Direct · 1,4 ips · 780 ms · 8 objets · plus proche 1,4 m · A100`), concepts éditables à
  chaud, questions pendant le flux. Flux arrêté ou en erreur : un tap relance la session
  (concepts conservés), sans risque de coupure accidentelle pendant que ça tourne.
  Distances calculées sur le téléphone (sténopé + hauteurs connues, ±15-20 %, 0 GPU) :
  `docs/DISTANCES.md`.
- **Analyse** : photo traitée (JPEG bord max 1024, q85), bandeau « Segmentation désactivée —
  renseigne un endpoint SAM dans Réglages. » quand `samBaseUrl` est vide (sinon : overlays de
  masques togglables + liste label/score), tags en petits caractères sous le titre, puis bloc
  « Description » en texte libre, bouton « Poser une question » → Chat.
- **Chat** : fil de bulles user/assistant sur la photo courante, champ « Ex. : qu'y a-t-il à
  gauche ? », bouton « Dicter » (dictée, devient « Stop » pendant l'enregistrement),
  bouton « Envoyer », bouton « 🔊 Écouter » sous chaque réponse de l'assistant, état
  « en cours… » et réessai.
- **Vidéo (tap)** : depuis Capture, quatrième bouton vert « Vidéo (tap) ». Un tap court
  n'importe où lance l'enregistrement caméra arrière (« Launched »), un second l'arrête et
  l'envoie à SAM 3.1 (« Sent », ou « Failed »). Barre système jamais déclenchante, TalkBack
  compris. Détail : `docs/MODE-TAP.md`.
- **Dictée (Meta Muse, repli ElevenLabs Scribe)** : bouton « Dicter » du chat **ou appui long
  n'importe où** (300 ms, sur n'importe quel écran). Le micro (16 kHz mono, sans limite de
  durée) est transcrit par `muse-voice-transcribe-1.0`, à défaut par Scribe ; le texte s'insère
  dans le champ de question (bouton) ou s'affiche en gros caractères dans un bandeau plein
  écran, lui même lu à voix haute. Contrat, pièges et facturation : `docs/META-ASR.md`.
- **Direct (vidéo)** : aperçu plein écran, frames 512 px/q65 poussées à `/live` une à la fois
  (cadence adaptative 350–900 ms), masques SAM superposés, ligne d'état
  « Direct · 1,4 ips · 780 ms · 8 objets · plus proche 1,4 m · A100 », concepts éditables à
  chaud (« Suivre »), distances par objet (`docs/DISTANCES.md`).
- **Annonce vocale des positions** : à chaque fois que la scène change, la voix ElevenLabs dit
  **où sont les objets** — « Personne à gauche, chaise en bas à droite. » (Direct et analyse
  photo). La phrase s'affiche aussi sous la barre d'état du Direct (🔊). Anti-bavardage :
  scène stable 2 frames, jamais deux fois la même scène, 4 s minimum entre deux annonces.
  Bascule « Annoncer la position des objets détectés » dans Réglages (activée par défaut).
- **Mémoire du direct** : bouton « Poser une question » dans l'écran Direct → panneau qui garde
  les **60 dernières secondes** de flux avec un identifiant stable par objet. Pastilles
  « personne · il y a 3 s », choix de l'instant (« maintenant », « il y a 5 s », « 15 s »,
  « 30 s »), question libre. Le LLM reçoit la dernière image **et** le journal traduit en
  français (« à gauche, vue la dernière fois il y a 3 s, apparue il y a 9 s, 5 images ») : on
  peut demander ce qui est passé, pas seulement ce qu'on voit. Chip « Analyser la frame »
  conserve le Chat photo sur la frame courante.
- **Intro d'accueil (première ouverture seulement)** : MP3 embarqué
  (`app/src/main/res/raw/intro_ihear.mp3`, voix Jade US rendue par ElevenLabs) joué **une seule
  fois**, au **premier lancement** et **seulement après un appui sur l'écran** : l'app s'ouvre
  silencieusement sur « Touchez l'écran pour commencer », le tap lance la voix puis ne revient
  jamais. Coupée par la bascule **« Intro à la 1re ouverture : on/off »** des Réglages.
- **Réglages** : titre « Réglages du backend », mention « Aucune clé n'est écrite dans le code. »,
  9 champs (Base URL LLM, Clé API LLM masquée `•••`, Modèle LLM, Base URL SAM, clé ElevenLabs
  masquée, ID de voix, base URL ASR, clé Meta masquée, modèle ASR), la section
  **« GPU SAM 3.1 — région »** (boutons **EU** / **US** = mode US, endpoint actif affiché),
  les interrupteurs « Lire les
  réponses automatiquement », « Annoncer la position des objets détectés » et « Intro à la
  1re ouverture », boutons « Enregistrer »,
  « Tester le LLM » (mini-image 16×16, statut « LLM OK — … »), « Tester SAM », « 🔊 Tester la
  voix », « Tester la dictée Meta » (1 s de silence).

## Arborescence

```
sam-vision-kotlin/
├── core/                    # module :core — domaine + réseau, pur JVM (185 tests)
│   └── src/{main,test}/java/dev/sam3vision/core/{model,net,mask,distance,settings,text,usecase}/
├── app/                     # module :app — Compose + CameraX (écrans, ViewModel, navigation)
│   └── src/main/java/dev/sam3vision/app/   # Capture/Analysis/Chat/Settings/AppNav/AppViewModel…
├── app/src/main/java/dev/sam3vision/app/tap/       # mode tap : tap court → vidéo → SAM 3.1
├── app/src/main/java/dev/sam3vision/app/cleanup/    # purge horaire silencieuse (Room + WorkManager)
├── tools/check-kotlin-limits.py   # garde-fou < 500 lignes/fichier, < 50 lignes/fonction
├── scripts/build.sh · install-emulator.sh
├── docs/TESTING.md · MODE-TAP.md · DISTANCES.md · NETTOYAGE.md · META-ASR.md · TEST-LONGPRESS-DICTEE.md
├── app/schemas/             # schéma Room exporté (traced_captures, v1)
├── gradle/libs.versions.toml · settings.gradle.kts · local.properties (gitignoré)
```

## Prérequis

- **JDK Temurin 17** : `~/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2` (le JRE système 21
  n'a pas de `javac` ; `gradle.properties` épingle déjà `org.gradle.java.home`).
- **Android SDK** : `~/Android/Sdk` (platforms 34→36, build-tools jusqu'à 37).
- **Gradle 8.14.1** via wrapper, **AGP 8.11.1, Kotlin 2.2.21**, compileSdk 36, **minSdk 29**
  (Android 10+, exigé par le SDK des lunettes Ray-Ban Meta).
- Tout est déjà en cache local : les commandes passent `--offline`, sans Android Studio.

## Build, test, install

```bash
export JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2 ANDROID_HOME=$HOME/Android/Sdk
./gradlew :core:testDebugUnitTest --offline        # 185 tests JVM, 0 réseau
./gradlew :app:testDebugUnitTest --offline         # 45 tests JVM (tap, nettoyage, voix, dictée, intro)
./gradlew assembleDebug --offline                  # app/build/outputs/apk/debug/app-debug.apk (~21 Mo)
bash scripts/build.sh                              # idem, environnement inclus
EMULATOR_SERIAL=emulator-5554 bash scripts/install-emulator.sh   # install + CAMERA + lancement + screenshot
```

## Brancher la clé InferHub (deux voies, jamais de clé commitée)

1. **Écran Réglages** (recommandé) : coller la clé `sk-…` dans « Clé API LLM » → Enregistrer ;
   elle est stockée chiffrée (`EncryptedSharedPreferences`/Keystore, repli fichier privé AES-GCM).
2. **`local.properties`** (gitignoré, confort debug uniquement) :

```properties
sdk.dir=/home/<vous>/Android/Sdk
llm.base.url=https://api.inferhub.dev/v1
llm.api.key=sk-…votre-cle…
llm.model=ali/qwen3.8-omni-flash
```

Ces valeurs sont injectées dans `BuildConfig` et servent de **défauts** ; la saisie dans
l'écran Réglages prime sur la valeur embarquée.

## Brancher la dictée Meta Muse

1. Renseigner « Dictée Meta — clé API » dans Réglages (base URL et modèle ont des défauts :
   `https://api.meta.ai/v1`, `muse-voice-transcribe-1.0`), Enregistrer, puis
   « Tester la dictée Meta ».
2. **Prérequis côté compte** : la facturation Meta doit être réglée
   (`https://dev.meta.ai/billing/?action=resolve_billing`). Sans ça, chaque appel — dictée
   comme test — renvoie `402 billing_not_configured` et l'app l'affiche tel quel.
3. La clé est stockée chiffrée (Keystore), jamais embarquée dans l'APK ni dans le dépôt.

## Mode US — GPU aux États-Unis

Réglages → « GPU SAM 3.1 — région » → **US**. L'app interroge alors l'app Modal
`sam3-us-a100` (région `us`, routage `us-east`) au lieu de `sam3-eu-a100` : même contrat
`/live`, mêmes masques, aucun autre réglage à toucher. Une seule carte à la fois : allumer
l'app US se fait avec `~/bin/sam3-gpu-us on --reason "…"`, après avoir éteint l'EU
(`~/bin/sam3-gpu off`). Détail, coûts et pièges : `docs/MODE-US.md`.

## Réactiver la segmentation SAM 3.1

1. Redéployer l'endpoint Modal (GPU à la demande, contrat HTTP inchangé) :

```bash
cd ~/Bureau/sam3-vision-app
LLM_BASE_URL=https://api.inferhub.dev/v1 LLM_API_KEY=unused LLM_MODEL=unused \
  bash scripts/deploy_backend.sh --skip-smoke
```

2. Copier l'URL rendue (`https://baptiste-faisy--sam3-vision-sam3service-web.us-east.modal.run`
   ou celle du nouveau déploiement), la coller dans « Base URL SAM 3.1 » des Réglages,
   Enregistrer puis « Tester SAM » (`/health`). Vide = preset de la région choisie
   (EU `sam3-eu-a100`, US `sam3-us-a100`).

## Voix — ElevenLabs (TTS)

Les réponses se lisent **automatiquement** : dès qu'une réponse du chat arrive, elle est
synthétisée puis jouée (interrupteur « Lire les réponses automatiquement » dans Réglages,
activé par défaut). Un bouton « 🔊 Écouter » reste disponible sous la description (Analyse) et
sous chaque bulle de l'assistant, plus « 🔊 Tester la voix » dans les Réglages.
`core/net/ElevenLabsTtsClient.kt` appelle `POST {base}/text-to-speech/{voice}?output_format=mp3_44100_128`
avec `xi-api-key`, le corps MP3 est mis en cache privé puis lu par `android.media.MediaPlayer`
(aucune dépendance audio ajoutée).

- Réglages : « Clé API ElevenLabs » (chiffrée comme la clé LLM) + « ID de voix »
  (défaut `EXAVITQu4vr4xnSDxMaL` = Sarah). Défauts de debug via `local.properties` :
  `tts.api.key`, `tts.voice.id` → `BuildConfig.TTS_API_KEY` / `TTS_VOICE_ID`.
- **Compte free (vérifié le 24/10/2026)** : les voix **premade** passent (Sarah
  `EXAVITQu4vr4xnSDxMaL`, George `JBFqnCBsd6RMkjVDRZzb`, `pFZP5JQG7iQjIQuC4Bku`, et d'autres),
  modèle `eleven_v4_turbo` ou `eleven_multilingual_v2`. Les voix de **bibliothèque**
  (ex. Rachel `21m00Tcm4TlvDq8ikWAM`) répondent `HTTP 402 paid_plan_required` : l'app affiche
  alors « voix ElevenLabs refusée (402) : voix de bibliothèque payante ou compte sans crédits —
  choisis une voix premade (ex. Sarah) ou ajoute des crédits ».
- **Une seule classe par moteur ElevenLabs** : `ElevenLabsTtsClient` sert à la fois au chat
  (bouton Écouter, lecture automatique) et aux bandeaux de dictée (`VoiceAnnouncer`).
- Le reste (LLM, SAM) reste utilisable sans clé ElevenLabs : la voix est une option.

## Endpoint du Direct (vidéo) — 2026-10-24

App Modal déployée : **`sam3-eu-a100`** (1 × A100-40GB, `eu-west`, web function `Sam3Live.*`),
qui sert `/health`, `/segment` et `/live` :

```
sam.base.url=https://baptiste-faisy--sam3-eu-a100-sam3live-web.eu-west.modal.run
```

L'ancienne URL `baptiste-faisy--sam3-live-sam3live-web.us-east.modal.run` n'existe plus côté
Modal : elle répond `404 modal-http: invalid function call` sur tous les chemins (app non
déployée). Pour retrouver l'URL courante, sans deviner le slug :

```bash
cd ~/sam3-modal && .venv/bin/modal app list          # app déployée
.venv/bin/python -c 'import modal; print(modal.Cls.from_name("sam3-eu-a100", "Sam3Live")().web.get_web_url())'
```

Le premier appel après veille réveille le GPU en ~30 s : c'est le rôle des retours `/health`
de `LiveViewModel` (« Réveil du GPU A100 en cours… »).

## ⚠️ Sécurité — clé embarquée en debug

Un APK debug construit avec `llm.api.key` dans `local.properties` **contient la clé en clair**
dans son `BuildConfig`. Ne distribuez jamais cet APK, ne le poussez sur aucun store, testez la
« clé absente » en retirant la ligne. En release : clé vide au build, saisie utilisateur
chiffrée uniquement. Les logs et captures d'écran ne montrent jamais la clé (champ masqué).

## Protection du dépôt (CI + file d'attente)

- Checks **`ci`** et **`ci-budget`** obligatoires, merge par la **file Mergify du r630**
  (`.mergify.yml`, contrat dans `docs/CI-QUEUE.md`) — pas de push direct sur `master`.
- `ci` échoue si : `local.properties` versionné, une clé `sk-…` / `sk_…` / `ghp_…` quelque part
  dans le dépôt, une ligne `org.gradle.java.home=` dans `gradle.properties` (le worker du r630
  n'a pas ce chemin — le JDK se règle dans `~/.gradle/gradle.properties`, non versionné), ou un
  fichier `.kt` ≥ 500 lignes / une fonction ≥ 50 lignes (`tools/check-kotlin-limits.py`).
- Le **périmètre** d'une PR se déclare dans son corps (`## Scope`, puces `- \`chemin\` — raison`) :
  chaque fichier touché doit être déclaré, et chaque déclaration doit servir.
- `app/DEPS.txt` liste les artefacts Gradle requis : Room, KSP, WorkManager, `camera-video` et
  `androidx.test` y sont désormaisdéclarés — un ajout de dépendance non listée = téléchargement froid
  sur le worker, et le budget CI est de 5 minutes.

## Limites connues

- `docs/TESTING.md` § limites : recette émulateur et points vigilants.
- Le garde-fou `tools/check-kotlin-limits.py` signale un dépassement connu :
  `AnalysisScreen()` (64 lignes) dans `app/src/main/java/dev/sam3vision/app/AnalysisScreen.kt`.
