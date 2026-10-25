# AGENTS.md — règles obligatoires pour tout agent LLM sur ce dépôt

> **À LIRE AVANT TOUTE MODIFICATION DE CODE.** Si tu es un agent (Claude Code, Codex, Cursor,
> Codebuff, Copilot, Hermes…) travaillant sur ce dépôt : lis ce fichier en entier, puis les
> docs cités avant d'utiliser ce qu'ils documentent. Ne rien deviner à partir du seul code
> quand une règle est écrite ici.

## 0. Docs à lire avant d'agir (contrats détaillés)

- `README.md` — fonctionnalités réelles, endpoints, sécurité (clé debug), procédures.
- `NOTES.md` — versions et décisions techniques justifiées par build.
- `docs/META-ASR.md` — contrat dictée Meta Muse (facturation 402, pièges).
- `docs/ELEVENLABS-TTS.md` — contrat voix (voix premade vs bibliothèque, 402).
- `docs/MODE-TAP.md` — mode tap (vidéo → SAM 3.1).
- `docs/TESTING.md` — recette émulateur, limites connues.
- `docs/CI-QUEUE.md` — CI, budget, file Mergify, gate Hermes.
- `docs/glasses-dat-spike.md` — contrat DAT lunettes Meta (lire avant de toucher à `glasses-spike/`).
- `CHANGELOG.md` — historique des changements.

## 1. C'est quoi ce dépôt

App Android native **Kotlin/Compose** : photo → description par un LLM vision → questions/réponses
sur la photo ; dictée Meta ASR (repli ElevenLabs Scribe), voix ElevenLabs (TTS), segmentation
SAM 3.1 optionnelle via endpoint Modal ; **iHear** en écran d'accueil (chat + caméra, swipe,
branché sur `feat/ihear-ui`). **Client unique : aucun backend à nous, aucune clé dans le dépôt.**

| Élément | Valeur |
|---|---|
| `:core` | domaine + réseau, JVM pur, aucune API Android dans la logique |
| `:app` | Compose + CameraX (écrans, ViewModel, navigation, `dev.sam3vision.app`) |
| `glasses-spike/` | prototype DAT (lunettes Meta) — non considéré comme production avant re-validation |
| Versions | JDK Temurin 17 · Gradle 8.14.1 · AGP 8.11.1 · Kotlin 2.2.21 · compileSdk 36 · minSdk 26 |

## 2. Commandes de vérification (à passer avant chaque push)

```bash
export JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2 ANDROID_HOME=$HOME/Android/Sdk
./gradlew :core:testDebugUnitTest --offline     # tests JVM :core
./gradlew :app:testDebugUnitTest --offline      # tests JVM :app
./gradlew assembleDebug --offline               # build APK debug
python3 tools/check-kotlin-limits.py            # garde-fou tailles (§3.1)
```

Tout est en cache Gradle (`--offline`). Pas d'Android Studio. Tests JVM uniquement
(`TestHttpServer` maison, pas de MockWebServer) ; pas d'instrumentation sans demande explicite.

## 3. Règles strictes — la CI échoue si elles sont violées

1. **Tailles Kotlin** : fichier `.kt` < 500 lignes, fonction < 50 lignes
   (`tools/check-kotlin-limits.py` ; une exception connue et signalée : `AnalysisScreen()`).
2. **Aucune clé / secret dans le dépôt** (`sk-…`, `sk_…`, `ghp_…`), aucun `local.properties`
   versionné. Les clés vivent dans Réglages (chiffré, Keystore, avec repli
   `PrivateFileSettingsStore` AES-256/GCM) ou `local.properties` (gitignoré) → `BuildConfig`.
   Attention : un APK debug avec `llm.api.key` embarque la clé en clair — ne jamais distribuer.
3. **Jamais de ligne `org.gradle.java.home=`** dans le `gradle.properties` versionné
   (le worker CI n'a pas ce chemin ; le JDK se règle dans `~/.gradle/gradle.properties`).
4. **Dépendances** : toute dépendance Gradle nouvelle doit être déclarée dans `app/DEPS.txt`
   AVANT usage (budget CI 5 min ; un artefact absent du cache = téléchargement froid = échec).
5. **Périmètre de PR** (`docs/CI-QUEUE.md`) : le corps d'une PR déclare `## Scope` avec une
   puce `- \`chemin\` — raison` par fichier touché ; chaque fichier touché est déclaré,
   chaque déclaration sert. Un agent qui touche un fichier non listé casse le contrat.
6. **Réseau** : un seul client HTTP (`core/net/Http.kt`, `defaultHttpClient()`, 60 s) et un seul
   mapper JSON (`org.json`, `net/Json.kt`). Pas de Retrofit, pas de kotlinx-serialization,
   pas de MockWebServer. `Result.flatMap` n'existe pas → helper `Result.then` (`net/Http.kt`).
7. **Architecture JVM** : la logique testable vit dans `:core` (pur JVM) ; les wrappers
   plateforme (Bitmap, MediaPlayer, Compose) restent fins, dans `:app`.
8. **Ne jamais contourner un garde-fou** (CI, `check-kotlin-limits.py`, gate Hermes) pour
   faire passer un build : corriger la cause. Pas de push direct sur `master`, pas de merge
   sans demande explicite de l'utilisateur, pas de réécriture de branche partagée ; la
   seule tolérance devrait rester `--force-with-lease`, après échec d'un push normal.

## 4. Git & merge

- Branche `feat/…` → PR → checks `ci` + `ci-budget` verts → file **Mergify** →
  gate **Hermes-lite** (fraîcheur exigée au merge ; en cas de conflit, l'agent
  ZeroClaw « hermes » réconcilie, voir `.github/hermes/`).
- Langue du projet : **français** (code commenté, commits `feat(ihear): …`, docs).

## 5. À la livraison

Vérifier réellement ce qui est demandé : tests qui couvrent le changement, build vert,
rapport honnête de ce qui n'a pas pu être vérifié. Ne jamais déclarer « vérifié »
sans avoir exécuté la vérification.
