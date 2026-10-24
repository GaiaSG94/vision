# NOTES — squelette Gradle + module `:core`

## Versions retenues (vérifiées par build)

| Élément | Version |
|---|---|
| Gradle | 8.14.1 (wrapper, distribution déjà en cache `~/.gradle/wrapper/dists`) |
| AGP | 8.11.1 |
| Kotlin | 2.2.21 (+ plugin `org.jetbrains.kotlin.plugin.compose` 2.2.21) |
| JDK | Temurin 17 (`~/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2`), épinglé dans `gradle.properties` (`org.gradle.java.home`) |
| compileSdk / targetSdk | **36** (android-36 installé) |
| minSdk | 26 |
| Compose BOM | 2026.05.01 (ui 1.11.2, material3 1.4.0) |
| CameraX | 1.6.0 |
| OkHttp | 4.12.0 |
| coroutines | 1.10.2 |

Écart assumé avec `KOTLIN-SPEC.md` : `compileSdk = 35` → **36**. Les artefacts Compose
2026.05.01 / activity-compose 1.13.0 / lifecycle 2.10.0 exigent compileSdk 36
(android-36 est installé, `android-35` reste disponible si besoin de redescendre).

## Décisions techniques

- **Un seul client HTTP** (`defaultHttpClient()`, 60 s partout) et **un seul mapper JSON**
  (`net/Json.kt` + `net/InferHubLlmClient.parse`). Pas de Retrofit, pas de kotlinx-serialization.
  `org.json` est fourni par Android → aucune dépendance de prod ajoutée pour le JSON.
- **Tests JVM** : `MockWebServer` n'est pas dans le cache Gradle → serveur maison
  `TestHttpServer` (ServerSocket) : 119 lignes, un thread par requête, garantit `Content-Length`,
  `delayMs` configurable pour tester les timeouts.
- **`org.json` sur JVM** : l'android.jar mockable renvoie des valeurs par défaut, donc
  `testImplementation("org.json:json:20240303")` (jar réel, prioritaire sur le classpath de test).
- **`javax.imageio` / `java.awt` sont masqués** par le mockable jar : les tests de masque
  travaillent sur des en-têtes PNG synthétiques et des `IntArray` (logique pure de `mask/MaskCore.kt`).
  Les wrappers `Bitmap` (`mask/Masks.kt`) ne sont pas testables en JVM et restent fins.
- **`Result.flatMap` n'existe pas** dans la stdlib → helper `Result.then` dans `net/Http.kt`.
- **`security-crypto 1.1.0`** : résolue et fonctionnelle, mais l'API `EncryptedSharedPreferences`
  est marquée *deprecated* (warning de compilation seulement). Repli documenté :
  `SettingsStoreFactory` bascule sur `PrivateFileSettingsStore` (fichier privé AES-256/GCM,
  clé Android Keystore non exportable, format `[IV 12][ciphertext]`) si le Keystore échoue.
  → aucune dépendance manquante, le repli n'a pas été nécessaire en build.
- **Segmentation non bloquante** : `AnalyzeUseCase` avale un échec SAM et décrit quand même
  l'image sans masque (spec : « si samBaseUrl vide, la photo part directement au LLM »).
- **Jamais de clé en dur** : `llmApiKey` vide par défaut ; message « clé API absente —
  renseigne-la dans Réglages » et échec immédiat sans appel réseau (testé).
  Le format de clé attendu côté InferHub commence par « sk- » : aucune valeur n'est
  écrite dans le dépôt, uniquement cette mention générique.

## Vérifications passées

- `./gradlew :core:testDebugUnitTest` → **BUILD SUCCESSFUL**, 27 tests (0 échec).
- `./gradlew assembleDebug` → **BUILD SUCCESSFUL**, `app/build/outputs/apk/debug/app-debug.apk` (17 Mo).

## Reste ouvert (pour l'agent UI)

- `:app` ne contient que `MainActivity` (placeholder `Text`), le manifest et le thème.
  Tout le reste de `app/src/main/java/dev/sam3vision/app/` est libre.
- Aucun ViewModel, aucun écran, aucune permission runtime : à écrire.
- `app/DEPS.txt` liste les dépendances déjà déclarées et celles à ajouter (coil, exif, etc.).
