# AGENTS.md — règles obligatoires pour tout agent LLM sur ce dépôt

> **À LIRE AVANT TOUTE MODIFICATION DE CODE.** Si tu es un agent (Claude Code, Codex, Cursor,
> Codebuff, Copilot, Hermes…) travaillant sur ce dépôt : lis ce fichier en entier, puis les
> docs cités avant d'utiliser ce qu'ils documentent. Ne rien deviner à partir du seul code
> quand une règle est écrite ici.

## 0. Docs à lire avant d'agir (contrats détaillés)

- `README.md` — fonctionnalités réelles, transport du direct, endpoints, sécurité (clé debug).
- `NOTES.md` — versions épinglées et décisions techniques.
- `docs/CI-QUEUE.md` — CI, budget 5 min, file Mergify, gate Hermes, contrat `## Scope`.
- `docs/NETTOYAGE.md` — purge horaire (Room + WorkManager).
- `docs/META-ASR.md` — contrat dictée Meta Muse (facturation 402, pièges).
- `docs/ELEVENLABS-TTS.md` — contrat voix (voix premade vs bibliothèque, 402).
- `docs/MODE-TAP.md` — mode tap (vidéo → SAM 3.1) · `docs/MODE-US.md` — région US.
- `docs/DISTANCES.md` — distances sténopé sur le téléphone · `docs/GUIDAGE.md` — voix de
  guidage post-SAM (couloir, chiffres figés, limites).
- `docs/TESTING.md` — recette émulateur, limites connues.
- `docs/META-GLASSES.md` + `docs/glasses-dat-spike.md` — lunettes Ray-Ban Meta (lire avant de
  toucher `app/glasses/` ou `glasses-spike/`).
- `docs/MASQUES-DEBUG.md` — z-order `AndroidView`/Compose et recette des masques.
- `CHANGELOG.md` — historique. **`docs/LIVE-SPEC.md` est cité par `core/net/HttpLiveClient.kt:25`
  mais n'existe pas dans ce dépôt** : le contrat serveur est dans le dépôt backend.

## 1. C'est quoi ce dépôt

App Android native **Kotlin/Compose** : photo → description par un LLM vision → questions/réponses ;
dictée Meta ASR (repli ElevenLabs Scribe), voix ElevenLabs (TTS), segmentation **SAM 3.1** sur
endpoint Modal ; **iSee en écran d'accueil** (chat plein écran + fenêtre caméra flottante,
gestes, masques, distances, voix). Les anciens écrans (Capture/Analyse/Chat/Direct/Tap) restent
là, atteignables par « Autres écrans » des Réglages (`app/AppNav.kt:63-81`).
**Aucun code de backend ici, aucune clé dans le dépôt.**

| Élément | Valeur |
|---|---|
| `:core` | domaine + réseau + logique pure JVM (`model, net, mask, distance, settings, live, gesture, predict, audio, usecase`), minSdk 26 |
| `:app` | Compose + CameraX : écrans, ViewModels, `dev.sam3vision.app`, minSdk **29** (`app/build.gradle.kts:43`) |
| `:glasses-spike` | prototype DAT lunettes, `include` dans le build, **non production** |
| Direct | **WebSocket `/live/ws`** : `core/net/WsLiveClient.kt` sur `WsTransport` (`OkHttpWsTransport` en prod, faux JVM en test) |
| Tests JVM | `@Test` comptés le 2026-10-26 : **469 `:core` + 159 `:app` = 628** ; 6 instrumentés (`androidTest`) |
| Versions | JDK Temurin 17 · Gradle 8.14.1 · AGP 8.11.1 · Kotlin 2.2.21 · compileSdk/targetSdk 36 |

## 2. Commandes de vérification (à passer avant chaque push)

```bash
export JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2 ANDROID_HOME=$HOME/Android/Sdk
./gradlew :core:testDebugUnitTest --offline     # tests JVM :core
./gradlew :app:testDebugUnitTest --offline      # tests JVM :app
./gradlew assembleDebug --offline               # APK debug
python3 tools/check-kotlin-limits.py            # garde-fou tailles (§3.1)
```

Cache Gradle local, pas d'Android Studio, `--offline`. Sur une **machine partagée** (r630), ne
lance pas Gradle : signale-le et fais vérifier sur le poste de travail. Tests JVM uniquement
(`TestHttpServer` maison, pas de MockWebServer) ; pas d'instrumentation sans demande explicite.

## 3. Règles strictes — la CI échoue si elles sont violées

1. **Tailles Kotlin** : fichier `.kt` < 500 lignes, fonction/composable < 50 lignes
   (`tools/check-kotlin-limits.py`, `ci.yml:104-105`). **Zéro duplication** : paramétrer et
   extraire un helper plutôt que copier. Au commit `8f9876a` le garde-fou passe sans exception ;
   le plus gros fichier est `app/ihear/IhearViewModel.kt` (492 lignes) — ne pas le faire grossir,
   extrait comme ça a été fait pour `app/LiveMessages.kt`.
2. **Aucune clé ni secret versionné** (`sk-…`, `sk_…`, `ghp_…`), jamais de `local.properties`.
   Les clés vivent dans Réglages (chiffré, Keystore, repli `PrivateFileSettingsStore`
   AES-256/GCM) ou `local.properties` (gitignoré) → `BuildConfig`. Un APK debug avec
   `llm.api.key` embarque la clé en clair : ne jamais distribuer.
3. **Jamais de ligne `org.gradle.java.home=`** dans le `gradle.properties` versionné — portabilité
   par `JAVA_HOME` (`gradle.properties:7-10`, garde CI `ci.yml:94-100`). Le JDK se règle dans
   `~/.gradle/gradle.properties` du poste.
4. **Dépendances** : toute dépendance Gradle nouvelle se déclare dans `app/DEPS.txt` **avant**
   usage (budget CI 5 min ; un artefact hors cache = téléchargement froid = échec).
5. **Périmètre de PR** (`docs/CI-QUEUE.md`) : corps de PR avec `## Scope` et une puce
   `` - `chemin` — raison `` par fichier touché ; tout fichier touché est déclaré, toute
   déclaration sert.
6. **Réseau** : un seul client HTTP (`core/net/Http.kt`, `defaultHttpClient()`, 60 s par défaut),
   **une seule fabrique pour le direct** (`core/net/LiveClientFactory.kt` → `liveClient()`,
   WebSocket ; `LiveProbe` et `LiveViewModel` partagent cette fabrique), un seul mapper JSON
   (`org.json`, `net/Json.kt`). Pas de Retrofit, pas de kotlinx-serialization, pas de
   MockWebServer. `Result.flatMap` n'existe pas → helper `Result.then` (`net/Http.kt`).
7. **Direct = WebSocket, pas de repli HTTP sur les frames.** `POST /live` n'est plus appelé depuis
   #137 (`98a1f08`) : ne pas le réintroduire. `GET /health` reste HTTP (`WsLiveClient.kt:64`) ;
   `POST /segment` (photo) et `POST /video` (mode tap) restent HTTP. Toute frame WS porte un `id`
   et se corrélie par **file par `id`** (`core/net/WsLiveClient.kt:14-17`) ; le transport s'injecte via
   `WsTransport` pour rester testable sans réseau.
8. **Architecture JVM** : la logique testable vit dans `:core` ; les wrappers plateforme
   (Bitmap, MediaPlayer, Compose) restent fins, dans `:app`.
9. **GPU Modal — une seule carte à la fois**, toutes régions confondues (`docs/MODE-US.md`).
   Ne jamais déployer ni allumer une GPU de ta propre initiative, jamais une deuxième app GPU :
   `~/bin/sam3-gpu status|busy` d'abord, `on --reason "…"` seulement sur demande explicite de
   l'utilisateur. Changer de région dans l'app n'allume rien (`core/settings/SamRegion.kt:6-9`).
10. **Téléphone** : **jamais `adb` nu** — `~/bin/phone run --task "…" -- <cmd>` (code de sortie
    2 = appareil occupé).
11. **Ne jamais contourner un garde-fou** (CI, `check-kotlin-limits.py`, gate Hermes) pour faire
    passer un build : corriger la cause. Pas de push direct sur `master`, pas de merge sans
    demande explicite, pas de réécriture de branche partagée ; seule tolérance,
    `--force-with-lease` après un push normal raté.

## 4. Git & merge

- Base de référence : **`origin/master`** (dépôt distant renommé **`BaptisteFaisy/testt`** ;
  l'URL locale peut encore dire `sam-vision`).
- Branche `feat/…` → PR → checks `ci` + `ci-budget` + `couche0` verts → file **Mergify** →
  gate **Hermes-lite** (fraîcheur exigée au merge ; en cas de conflit l'agent ZeroClaw « hermes »
  réconcilie, voir `.github/hermes/`).
- Une branche non fusionnée n'est pas la vérité du produit : `feat/latency-ms` (`e7b95b0`,
  cadence sans plancher, LUT, `ALPHA_8`, `canAcceptFrame`, `LiveTimings`) et
  `feat/ihear-2-concepts` (2 concepts) sont **en attente**, `master` est à 8 concepts
  (`core/live/LivePrompts.kt:20`) et à une cadence 350/500/900 ms (`core/live/LiveSession.kt:33`).
- Langue du projet : **français** (code commenté, commits `feat(ihear): …`, docs).

## 5. À la livraison

Vérifier réellement ce qui est demandé : tests qui couvrent le changement, build vert, rapport
honnête de ce qui n'a pas pu être vérifié. **Un chiffre non mesuré s'écrit « non mesuré », pas
estimé.** Ne jamais déclarer « vérifié » sans avoir exécuté la vérification.
