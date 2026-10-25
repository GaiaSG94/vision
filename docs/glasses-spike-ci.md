# `:glasses-spike` dans la CI (budget 5 min)

**Vérifié le 2026-10-25** dans le worktree `sam-wt-docs-app` : le module est **toujours là**,
ce document n'est **pas historique**. Preuves dans l'arbre :

- `settings.gradle.kts` contient bien `include(":glasses-spike")` (avec `:core` et `:app`) ;
- 9 fichiers `.kt` sous `glasses-spike/src/` (7 dans `main`, 1 dans `test`, 1 dans
  `androidTest`) + `glasses-spike/build.gradle.kts` ;
- `ci.yml` compile le module (étape « Tests + APK debug + APK release + lint + spike
  lunettes ») et le reconnaît dans son test de périmètre.

Rien sous `glasses-spike/build/` : le worktree n'a pas été bâti ici (aucune trace de
`build/intermediates` à grep). Ce n'est pas un oubli : `build` est dans `SKIP_DIRS` du garde
de taille et `/build/` dans `EXEMPT_SUBSTR` de la Couche 0.

Règle budgétaire (règle user 2026-10-24) : `timeout-minutes: 5` par job, `concurrency`,
`fetch-depth: 1`, build natif/E2E hors PR, **jamais** d'allongement de timeout. Le document
de référence `~/Bureau/CI-BUDGET-5MIN.md` est un fichier du poste de travail : absent du
r630 (mesuré), donc non relu ici — les bornes ci-dessous viennent des workflows eux-mêmes.

## Ce qui a changé (dans le job `ci`)

1. **Périmètre** — l'étape « Cette PR touche-t-elle du code ? » ne reconnaissait que
   `^(app|core)/.*\.(kt|kts)$|^gradle/|^gradle\.properties$` : une PR touchant seulement
   `glasses-spike/` ou `settings.gradle.kts` passait pour « sans code », le module n'était
   jamais compilé (vert par absence de test). Motif actuel, lu dans `ci.yml` le 25/10 :
   `^(app|core)/.*\.(kt|kts)$|^glasses-spike/|^gradle/|^gradle\.properties$|^settings\.gradle\.kts$|^tools/`
2. **Build** — `:glasses-spike:assembleDebug` et `:glasses-spike:testDebugUnitTest`
   rejoignent l'appel Gradle **existant** (une configuration, un daemon) : pas de job
   ajouté, timeout du job `ci` inchangé (5 min). Pas de `lintDebug` ni d'`assembleRelease`
   pour le spike : module jetable, budget d'abord. Si le spike ne compile pas, `ci` est
   rouge — c'est voulu, et `ci` est un check exigé (file Mergify, protections, protection de
   branche — voir `docs/CI-QUEUE.md`).

## Coût

Les durées de build sont des **constats du 24/10 consignés dans `ci.yml`** : aucun build
n'a été relancé ici le 25/10 (interdiction de build dans cette mission), donc elles ne sont
**pas remesurées**. Seules les lignes marquées *mesuré 25/10* sortent d'une vérification du
jour.

| Morceau | Durée | Statut |
|---|---|---|
| job `ci` sans le spike | 46 s à chaud, 1 min 00 en CI (cache froid) | constat 24/10, non remesuré |
| les deux tâches du spike | +18 à 33 s (`assembleDebug` 15-25 s, `testDebugUnitTest` 3-8 s) | estimation 24/10, non remesurée |
| les 3 AAR `mwdat-*`, si le worker est froid | 2 à 5 s (10,6 Mo à ~17 Mo/s) | taille **mesurée 25/10**, débit constat 24/10 |
| total annoncé | ≈ 1 min 15 à chaud, ≈ 1 min 45 au pire premier run | estimation, **non remesurée** |

Taille réelle des artefacts — *mesuré 25/10* par `HEAD` sur Maven Central
(`com.meta.wearable`, version `mwdat = 1.0.1` dans `gradle/libs.versions.toml`, HTTP 200) :

| Artefact | Octets |
|---|---|
| `mwdat-core-1.0.1.aar` | 8 979 777 |
| `mwdat-camera-1.0.1.aar` | 784 799 |
| `mwdat-mockdevice-1.0.1.aar` | 871 060 |
| **somme des 3 du spike** | **10 635 636 ≈ 10,64 Mo** |

Le « ~11 Mo » du doc est donc confirmé. Le quatrième artefact, `mwdat-inputs` (146 878
octets), n'est demandé que par `:app` (`implementation(libs.mwdat.inputs)`) : le spike,
lui, prend `core`, `camera`, `mockdevice`. Cohérence du débit : 8,98 Mo ÷ 0,53 s ≈ 16,9
Mo/s, soit le « ~17 Mo/s » mesuré depuis le r630 le 24/10.

En revanche, les « ~11 Mo de `.so` META **à empaqueter** » dans l'APK ne sont pas
vérifiables ici : il faudrait dépaqueter les AAR (donc build). **À vérifier** au prochain
run qui le permet.

## Si le spike grossit

Tant que le total reste loin des 5 min, on garde une seule étape Gradle : un job séparé
doublerait checkout + configuration. Si lint, tests d'instrumentation ou d'autres modules
entrent en jeu, la sortie propre est un job `glasses` avec `needs: ci` et
`timeout-minutes: 5` — on ne touche pas au timeout de `ci` (règle des 5 min, garde
`ci-budget` : un job sans timeout ou au-delà de 5 minutes fait échouer la PR).

## Premier run sur un worker : pas de `actions/cache`

Vérifié dans `ci.yml` le 25/10 : aucune étape `actions/cache` (le mot n'apparaît que dans
un commentaire). Le cache Gradle est persistant sur la machine, donc un worker « froid »
retélécharge les 10,64 Mo d'AAR une seule fois.

La répartition `GRADLE_USER_HOME` par worker (`~/.gradle` pour 01/02, `~/.gradle-w03` et
`~/.gradle-w04` pour 03/04, fixés dans le `.env` de chaque runner) et l'état chaud/froid du
24/10 (01 et 02 chauds, 03 et 04 froids) sont **hors dépôt** : non relus ici, **à vérifier**
sur le r630. Ce qui est mesurable côté API le 25/10 : 4 runners à l'état `online` et idle,
`r630-sam-vision-01..04`, labels `self-hosted` + `sam-vision-worker` + `ci-node`.

Rien de GPU dans la CI : `grep -i "modal|gpu|nvidia"` ne renvoie aucun résultat dans
`.github/workflows/` (mesuré 25/10). La règle « une seule GPU Modal à la fois » n'est donc
jamais sollicitée par un job ; aucun build GPU n'est à relancer ici.

## Pourquoi un module séparé (et ce qui a bougé depuis)

`com.meta.wearable:mwdat-*` impose **minSdk 29** ; au moment du spike, `:app` était en 26 et
ne devait pas être relevé pour un SDK encore en developer preview. État *mesuré 25/10* dans
les `build.gradle.kts` : `:app` `minSdk = 29`, `:glasses-spike` `minSdk = 29`, `:core`
`minSdk = 26`, `compileSdk = 36` pour les trois. Le spike a donc survécu à l'entrée de DAT
dans `:app` (voir `docs/META-GLASSES.md`) et reste un module de preuve, pas une dépendance.

Sens des dépendances, lu dans `glasses-spike/build.gradle.kts` : le spike **dépend de**
`project(":core")`, et `:app` ne dépend pas du spike. `settings.gradle.kts` est le seul
endroit où le module est déclaré. Le dépôt est résolu par `google()` + `mavenCentral()` avec
`RepositoriesMode.FAIL_ON_PROJECT_REPOS` (aucun dépôt déclaré par un module).

Deux détails à garder en tête pour un run réel : les placeholders `mwdat_application_id` et
`mwdat_client_token` valent `"0"` dans le spike (pas d'attestation en Developer Mode), et
`TODO(spike)` interdit la survie de `mockdevice` à un build public.

## Gardes de taille

`tools/check-kotlin-limits.py` parcourt la racine récursivement en sautant
`{.git, .gradle, build, .idea, tmp, node_modules}` : `glasses-spike/` est donc contrôlé
(< 500 lignes/fichier, < 50 lignes/fonction), script inchangé pour ça.

Relancé ici (lecture seule, pas un build) le 25/10 :
`check-kotlin-limits : 237 fichier(s) .kt/.kts inspecté(s)` — **OK, aucun dépassement**.
Le chiffre de 113 fichiers (constat du 24/10) est périmé : le dépôt a grossi. Dans le spike,
le plus gros fichier est `GlassesSession.kt` (341 lignes), puis `GlassesVoice.kt` (258) et
`MainActivity.kt` (239) — tous sous la barre des 500.
