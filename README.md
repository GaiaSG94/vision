# SAM Vision — app Android native (Kotlin)

Photo → description par un LLM vision → questions/réponses sur la photo. Client unique :
pas de backend à nous. LLM via **InferHub** (`https://api.inferhub.dev/v1`, compatible OpenAI,
modèle par défaut `ali/qwen3.8-omni-flash`). Segmentation **SAM 3.1 optionnelle** : un endpoint
HTTP (Modal redéployé) saisi dans Réglages ; vide ⇒ bandeau « Segmentation désactivée » et la
photo part directement au LLM. Modal est fermé (0 GPU) — voir l'ancien dépôt
`~/Bureau/sam3-vision-app/docs/MODAL-CLOSED.md`.

## Ce que voit l'utilisateur (captures décrites, 1080×2400)

- **Capture** : plein écran sombre, titre « SAM Vision », zone d'aperçu caméra, trois
  boutons : « Photographier » (large, centre bas), « Galerie » (à gauche), « Réglages » (à droite).
- **Analyse** : photo traitée (JPEG bord max 1024, q85), bandeau « Segmentation désactivée —
  renseigne un endpoint SAM dans Réglages. » quand `samBaseUrl` est vide (sinon : overlays de
  masques togglables + liste label/score), tags en petits caractères sous le titre, puis bloc
  « Description » en texte libre, bouton « Poser une question » → Chat.
- **Chat** : fil de bulles user/assistant sur la photo courante, champ « Ex. : qu'y a-t-il à
  gauche ? », bouton « Dicter » (dictée Meta Muse, devient « Stop » pendant l'enregistrement),
  bouton « Envoyer », état « en cours… » et réessai.
- **Dictée Meta Muse** : le micro (16 kHz mono, 60 s max) est transcrit par
  `muse-voice-transcribe-1.0` et le texte est inséré dans le champ de question. Contrat,
  pièges et blocage de facturation : `docs/META-ASR.md`.
- **Réglages** : titre « Réglages du backend », mention « Aucune clé n'est écrite dans le code. »,
  7 champs (Base URL LLM, Clé API LLM masquée `•••`, Modèle LLM, Base URL SAM, base URL ASR,
  clé Meta masquée, modèle ASR), boutons « Enregistrer », « Tester le LLM » (mini-image 16×16,
  statut « LLM OK — … »), « Tester SAM », « Tester la dictée Meta » (1 s de silence).

## Arborescence

```
sam-vision-kotlin/
├── core/                    # module :core — domaine + réseau, pur JVM (31 tests)
│   └── src/{main,test}/java/dev/sam3vision/core/{model,net,mask,settings,text,usecase}/
├── app/                     # module :app — Compose + CameraX (écrans, ViewModel, navigation)
│   └── src/main/java/dev/sam3vision/app/   # Capture/Analysis/Chat/Settings/AppNav/AppViewModel…
├── tools/check-kotlin-limits.py   # garde-fou < 500 lignes/fichier, < 50 lignes/fonction
├── scripts/build.sh · install-emulator.sh
├── docs/TESTING.md          # tests JVM + recette émulateur
├── gradle/libs.versions.toml · settings.gradle.kts · local.properties (gitignoré)
```

## Prérequis

- **JDK Temurin 17** : `~/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2` (le JRE système 21
  n'a pas de `javac` ; `gradle.properties` épingle déjà `org.gradle.java.home`).
- **Android SDK** : `~/Android/Sdk` (platforms 34→36, build-tools jusqu'à 37).
- **Gradle 8.14.1** via wrapper, **AGP 8.11.1, Kotlin 2.2.21**, compileSdk 36, minSdk 26.
- Tout est déjà en cache local : les commandes passent `--offline`, sans Android Studio.

## Build, test, install

```bash
export JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2 ANDROID_HOME=$HOME/Android/Sdk
./gradlew :core:testDebugUnitTest --offline        # 31 tests JVM, 0 réseau
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

## Réactiver la segmentation SAM 3.1

1. Redéployer l'endpoint Modal (GPU à la demande, contrat HTTP inchangé) :

```bash
cd ~/Bureau/sam3-vision-app
LLM_BASE_URL=https://api.inferhub.dev/v1 LLM_API_KEY=unused LLM_MODEL=unused \
  bash scripts/deploy_backend.sh --skip-smoke
```

2. Copier l'URL rendue (`https://baptiste-faisy--sam3-vision-sam3service-web.us-east.modal.run`
   ou celle du nouveau déploiement), la coller dans « Base URL SAM 3.1 » des Réglages,
   Enregistrer puis « Tester SAM » (`/health`). Vide = segmentation désactivée.

## ⚠️ Sécurité — clé embarquée en debug

Un APK debug construit avec `llm.api.key` dans `local.properties` **contient la clé en clair**
dans son `BuildConfig`. Ne distribuez jamais cet APK, ne le poussez sur aucun store, testez la
« clé absente » en retirant la ligne. En release : clé vide au build, saisie utilisateur
chiffrée uniquement. Les logs et captures d'écran ne montrent jamais la clé (champ masqué).

## Limites connues

- `docs/TESTING.md` § limites : recette émulateur et points vigilants.
- Le garde-fou `tools/check-kotlin-limits.py` signale un dépassement connu :
  `AnalysisScreen()` (64 lignes) dans `app/src/main/java/dev/sam3vision/app/AnalysisScreen.kt`.

- Le dépôt est protégé : `ci` + `ci-budget` obligatoires, merge par la file Mergify du r630 (voir `.mergify.yml`).
