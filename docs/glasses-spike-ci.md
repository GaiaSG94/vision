# `:glasses-spike` dans la CI (budget 5 min)

Règle : `~/Bureau/CI-BUDGET-5MIN.md`. Timeout du job `ci` inchangé (5 min), aucun job ajouté.
## Ce qui a changé
1. **Périmètre** — l'étape « Cette PR touche-t-elle du code ? » ne reconnaissait que
   `^(app|core)/.*\.(kt|kts)$|^gradle/|^gradle\.properties$` : une PR touchant seulement
   `glasses-spike/` ou `settings.gradle.kts` passait pour « sans code », le module n'était
   jamais compilé (vert par absence de test). Motif actuel :
   `^(app|core)/.*\.(kt|kts)$|^glasses-spike/|^gradle/|^gradle\.properties$|^settings\.gradle\.kts$|^tools/`
2. **Build** — `:glasses-spike:assembleDebug` + `:glasses-spike:testDebugUnitTest` rejoignent
   l'appel Gradle *existant* (une configuration, un daemon). Pas de `lintDebug` ni
   `assembleRelease` : module jetable, budget d'abord. Si le spike ne compile pas, `ci` est
   rouge — c'est voulu.

## Coût estimé (budget 300 s)
| Morceau | Durée |
|---|---|
| job `ci` aujourd'hui | 46 s à chaud, 1 min 00 en CI (`docs/CI-QUEUE.md`, r630) |
| les deux tâches du spike | +18 à 33 s : `assembleDebug` 15-25 s (7 `.kt` Compose + ~11 Mo de `.so` META à empaqueter), `testDebugUnitTest` 3-8 s (`YuvConverterTest`) |
| AAR `mwdat-*`, premier run | 11 Mo ; mesuré du r630 : 8,9 Mo en 0,53 s ≈ 17 Mo/s → 2 à 5 s |
| **Total** | **≈ 1 min 15 à chaud, ≈ 1 min 45 au pire premier run — marge > 3 min** |

Si le spike grossit (lint, tests d'instrumentation) : job `glasses` séparé, `needs: ci`,
`timeout-minutes: 5` — on ne touche pas au timeout de `ci`.

## Premier run : les AAR Meta
Pas de step `actions/cache` : le cache Gradle est **persistant sur la machine**. Les workers
`r630-sam-vision-01..04` fixent `GRADLE_USER_HOME` dans leur `.env` (`~/.gradle` pour 01/02,
`~/.gradle-w03` / `~/.gradle-w04` pour 03/04) : le premier run sur un worker sans
`com.meta.wearable` télécharge ~11 Mo depuis Maven Central (les 3 AAR répondent 200), les
suivants sont à chaud. Au 24/10 : 01 et 02 chauds, 03 et 04 froids. Rien à précharger.

## Pourquoi un module séparé
`com.meta.wearable:mwdat-*` impose **minSdk 29** ; `:app` et `:core` sont en **26** et ne
seront pas relevés pour un SDK encore en developer preview. `:app` ne dépend pas de
`glasses-spike` : seul `settings.gradle.kts` enregistre le module.

## Gardes de taille
`tools/check-kotlin-limits.py` parcourt la racine récursivement : `glasses-spike/` est déjà
contrôlé (< 500 lignes/fichier, < 50 lignes/fonction), script inchangé. 24/10 : 113
fichiers inspectés, 0 dépassement ; `glasses-spike/build/` reste exclu.