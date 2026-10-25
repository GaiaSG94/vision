# File de merge — ce qui est exigé, ce qui merge tout seul

État **vérifié le 2026-10-25** dans le worktree `sam-wt-docs-app` : `.mergify.yml` et
`.github/workflows/*` y sont identiques à `origin/master` (`04e7f7c`). Le HEAD du worktree
est 2 commits derrière (`9856d9e`, `04e7f7c`) et ne touche ni `.mergify.yml` ni `.github/`
(`git diff --stat HEAD origin/master -- .mergify.yml .github/ tools/ settings.gradle.kts` :
vide), seulement `core/`, `app/`, `CHANGELOG.md` et `docs/TESTING.md`. Là où une
valeur n'est pas lisible dans le dépôt, elle a été lue par l'API GitHub (mesures datées
ci-dessous). Ce qui n'a pas pu être mesuré est marqué **à vérifier**.

Réserve : le `master` **distant** a avancé depuis (`9c60dab`, mesuré par `git ls-remote`
le 25/10 — la révision n'existe pas dans ce worktree, rien n'a été rapatrié). Les fichiers
`.github/` de `9c60dab` n'ont donc **pas** été inspectés ; tout ce qui suit décrit l'état
`04e7f7c`.

Dépôt : **`BaptisteFaisy/testt`** (renommé depuis `sam-vision`, voir §Renommage).

## Conditions d'entrée dans la file `default`

Une PR entre dans la file quand **toutes** ces `queue_conditions` sont vraies
(`.mergify.yml`, lu sur la branche par défaut `master`) :

- `check-success = ci-budget` ;
- `check-success = ci` ;
- `check-success = couche0` ;
- pas d'étiquette `hold`, pas de conflit (`-conflict`), pas brouillon, base `master`.

Réglages de la règle `default` : `batch_size: 1`, `max_checks_retries: 0`,
`merge_method: rebase`, `update_method: rebase`. Le bloc `merge_queue` est en
`mode: serial` avec `max_parallel_checks: 1`.

Conséquence des **in-place checks** : Mergify teste **sur la branche de la PR** (pas de
brouillon de batch, pas de `merge_conditions` distinct) → un seul run CI par PR, et merge
immédiat quand la base n'a pas bougé sous la PR. Si elle a bougé, la PR est rebasée et
re-testée. **Aucun batch de deux PR n'est possible avec ces réglages** (contrairement à ce
qui était écrit ici avant le 25/10 : `batch_size: 1` et `max_parallel_checks: 1`) — mais
les CI de PR, elles, tournent en parallèle sur le pool de workers.

`Require branches to be up to date` est **désactivé** : mesuré par l'API le 25/10,
`required_status_checks.strict = false` sur `master`. C'est cohérent avec les checks
spéculatifs de Mergify (la file fait déjà rebase + re-test) : réactiver `strict` casserait
ce fonctionnement.

`priority_rules` : étiquette `urgent` → priorité haute, `priority-low` → basse.
Frein : étiquette `hold`. Relance manuelle : commentaire `@Mergifyio refresh` ;
embarquement forcé : `@Mergifyio queue`.

## Merge protections (auto-embarquement)

`merge_protections` définit une règle « CI verte et pas de hold » (`if: base = master`,
`success_conditions` : `ci`, `ci-budget`, `couche0`, `-label = hold`) — volontairement
**identiques** aux conditions de file, pas de double garde. Avec
`merge_protections_settings.auto_merge_conditions: true`, une PR verte part seule en file,
**à condition que le produit Merge Protections soit activé** sur le dépôt
(doc Mergify `/merge-protections/setup`).

C'est le cas au 25/10 : sur la PR #142 (mergée le 25/10 à 18:57Z), les check-runs publiés
sont `ci`, `ci-budget`, `couche0`, `stale-base-guard`, `Verdict`, `hermes-lite/verdict`,
`Mergify Merge Queue`, `Mergify Merge Protections` — les deux checks Mergify sont présents
et verts. S'ils disparaissent, on retombe sur l'embarquement manuel `@Mergifyio queue`.

## Les 6 workflows (nom, déclencheurs, bornes)

Relevé fichier par fichier le 25/10. Tous les jobs sont sur
`runs-on: [self-hosted, sam-vision-worker]` avec `timeout-minutes: 5` et un bloc
`concurrency`.

| Workflow | Déclencheurs | Job (nom publié) | `concurrency` | checkout |
|---|---|---|---|---|
| `ci.yml` — `CI` | `pull_request`, `push: [master]` | `ci` | groupe `ci-<workflow>-<ref>`, `cancel-in-progress: true` | `fetch-depth: 1` |
| `ci-budget.yml` — `ci-budget (≤ 5 min)` | `pull_request`, `push: [master]`, `workflow_dispatch` | `ci-budget` | groupe par PR ou ref, annulation | `fetch-depth: 1`, `persist-credentials: false` |
| `couche0.yml` — `couche0` | `pull_request: [opened, synchronize, reopened, edited]`, `workflow_dispatch` (`pr`) | `couche0` | groupe par PR, annulation | aucun checkout (tout par API) |
| `hermes-lite.yml` — `Hermes Lite (gate)` | `pull_request: [opened, reopened, ready_for_review, synchronize]`, `check_run: [completed]`, `schedule: */10`, `workflow_dispatch` | `Verdict` | groupe **par PR** et par type d'événement ; `cancel-in-progress` seulement hors `pull_request` | `fetch-depth: 1`, `persist-credentials: false` |
| `hermes-lite-alert.yml` | `workflow_dispatch` seul | `Lister les PR en conflit` | groupe par ref, annulation | aucun checkout |
| `stale-base-guard.yml` — `informatif` | `pull_request: [opened, synchronize, reopened]`, `workflow_dispatch` (`pr_number`) | `stale-base-guard` | groupe par PR, annulation | aucun checkout |

Notes de terrain :

- `hermes-lite` : `cancel-in-progress: ${{ github.event_name != 'pull_request' }}` — un
  gate interrompu n'est pas un gate vert, donc le passage `pull_request` n'est jamais
  annulé ; un groupe par dépôt annulait deux PR entre elles (check annulé = rouge pour
  Mergify).
- `hermes-lite` ignore les `check_run` qui ne sont rattachés à aucune PR et ceux qu'il
  publie lui-même (`Verdict`, `hermes-lite/verdict`) : sinon il se relançait en boucle.
- `stale-base-guard` est **informatif** : un retard sur la base produit un `::warning::` et
  `exit 0`. Un rebase serveur du gate est **désactivé** (`HERMES_LITE_REFRESH: '0'`) : un
  push de bot laisse les runs en `action_required` et la PR perd ses checks.
- 4 workers `r630-sam-vision-01..04` (labels `self-hosted`, `sam-vision-worker`,
  `ci-node`), tous `online` et idle — mesuré par l'API le 25/10 à 19:04Z. Chacun a son
  `GRADLE_USER_HOME` (cache persistent sur la machine, pas d'`actions/cache`) ; la
  répartition par worker est hors dépôt, **à vérifier** sur le r630.

## Ce que fait le job `ci`

1. **Périmètre** — la liste des fichiers vient de l'API (checkout superficiel : la base
   n'est pas là). Motif « code » :
   `^(app|core)/.*\.(kt|kts)$|^glasses-spike/|^gradle/|^gradle\.properties$|^settings\.gradle\.kts$|^tools/`.
   Aucun fichier ne correspond → les étapes Gradle sont sautées (~10 s, constat 24/10).
   Pas de `paths-ignore` : `ci` est exigé par la file, il doit exister sur toutes les PR.
2. **Chaîne d'outils** — `JAVA_HOME` et `ANDROID_HOME` doivent venir du `.env` du runner
   (installation : `~/toolchains/jdk17` et `~/android-sdk` sur le r630).
3. **Gardes** — aucun `local.properties` versionné, aucun secret en clair (`sk-`, `sk_`,
   `ghp_`), pas de `org.gradle.java.home` dans `gradle.properties`, puis
   `python3 tools/check-kotlin-limits.py` (< 500 lignes/fichier, < 50 lignes/fonction).
4. **Un seul appel Gradle** : `:core:testDebugUnitTest :app:testDebugUnitTest
   :app:assembleDebug :app:assembleRelease :app:lintDebug :glasses-spike:assembleDebug
   :glasses-spike:testDebugUnitTest`, avec `-Dorg.gradle.jvmargs="-Xmx2g"` (4 daemons sur
   31 Go). Le spike lunettes est donc couvert par la gate exigée par Mergify, sans job
   séparé — voir `docs/glasses-spike-ci.md`.
5. **Rapports** — `**/build/reports/tests/**` et `**/build/reports/lint-results-*.html`
   en artefact, 3 jours (`if: always()`).

Mesures de référence du 24/10, consignées dans les commentaires de `ci.yml` et **non
remesurées** ici (aucun build lancé le 25/10) :

| Étape | Durée |
|---|---|
| `:core:testDebugUnitTest` + `:app:testDebugUnitTest` + `:app:assembleDebug` | 15 s |
| `:app:assembleRelease` | 41 s |
| `:app:lintDebug` | 22 s |
| job `ci` complet (code) | **46 s** à chaud, 1 min 00 en CI (cache froid) |
| job `ci` d'une PR sans code (docs, config) | ~10 s — Gradle sauté |
| `couche0` / `ci-budget` / `stale-base-guard` / `Verdict` | 6 à 10 s, en parallèle |

Verdict de PR : **≈ 45 s** pour du code, **≈ 10 s** pour de la doc — budget 5 min. Toute
évolution qui pousserait le job `ci` au-delà de ~3 min doit sortir de la PR (règle CI
5 min) : jamais d'allongement de `timeout-minutes`.

## Couche 0 — opposable, sauf sur les refs `mergify/*`

Garde : `.github/couche0/check_couche0.py` (189 lignes, stdlib seule — les workers n'ont ni
`node` ni `gh`). Deux contrôles déterministes :

- **scope** — tout fichier modifié doit être déclaré dans la section `## Scope` du corps de
  la PR, et toute déclaration doit servir ;
- **forbidden** — motifs refusés dans les lignes ajoutées : prise de côté forcée
  (`-X` suivi de `ours`/`theirs`), push forcé sans `--force-with-lease`, déclencheur à
  privilèges (`pull_request_` + `target`), clés `sk-…`/`sk_…`/`ghp_…` en clair, `sdk.dir`
  en dur. Ce paragraphe décrit les motifs sans les écrire littéralement : la garde lit
  aussi les lignes de doc.
  `.github/workflows/ci.yml`. Les toucher exige la mention
  `SAMVISION_ALLOW_GUARD_BUMP` dans le corps de la PR (`.github/hermes/` n'est pas gelé).
  Exemptés de déclaration : `/build/`, `gradle/wrapper/`, `.gradle/`, `*.lock`, images.

Le **juge vient de `master`**, jamais du head de la PR (le workflow récupère le script par
l'API `?ref=master`, avec repli sur le SHA de la PR si le garde n'est pas encore sur
`master`) : une PR ne peut pas assouplir son propre garde.

**Opposable** : `couche0` figure dans les `queue_conditions`, dans les
`merge_protections.success_conditions`, **et** dans la protection de branche de `master`
— mesuré le 25/10 par l'API : `contexts = ["ci", "ci-budget", "couche0"]`. Une PR qui
touche un fichier non déclaré, ajoute une clé en clair ou modifie un fichier de garde sans
le drapeau n'entre pas dans la file. `sam-wt merge` remplit le `## Scope` tout seul (outil
du poste : voir §Côté agent, non vérifié depuis ce worktree).

**Ignorée sur les refs `mergify/*`** : la PR-brouillon d'un batch a un corps écrit par
Mergify, sans `## Scope` → garde toujours rouge → `action.queue.leave CHECKS_FAILED`. Le
workflow sort donc **en succès sans rien contrôler** sur `mergify/*` (première étape :
`case "${{ github.head_ref }}" in mergify/*) exit 0`, seconde étape conditionnée à
`!startsWith(github.head_ref, 'mergify/')`). La vraie PR, elle, a déjà été jugée.

> **Correction d'une affirmation fausse.** Le titre du commit `7013ac6` dit « retirée des
> checks requis », mais **rien n'a été retiré** : ni `.mergify.yml` (le même commit ne
> touche que `couche0.yml`, et son corps précise « la Couche 0 reste exigée là où elle juge
> une vraie PR : queue_conditions + merge_protections »), ni la protection de branche
> (`couche0` y est toujours requis, mesuré le 25/10). Ce que ce commit a retiré, c'est
> seulement un `if:` au niveau du **job** — et c'est `9bec462` (24/10) qui l'a remplacé par
> la sortie en succès, parce qu'un job « skipping » ne satisfait pas
> `check-success = couche0` (constaté sur #27, #30, #47, #66).

## Budget 5 min — le garde `ci-budget`

`.github/ci-budget/check_timeouts.py` (`MAX_MINUTES = 5`) lit les workflows ligne à ligne
(pas de PyYAML sur les runners) et **échoue** si un job n'a pas de `timeout-minutes` ou en
déclare plus de 5 ; il **avertit** (sans échouer) si `concurrency` manque ou si un
checkout n'a pas `fetch-depth: 1`. Il tourne sur chaque PR pour que le check `ci-budget`
existe toujours, et se contrôle lui-même.

Mesure du 25/10 : `6 workflow(s) contrôlé(s) : 0 erreur(s), 0 avertissement(s)` — les
6 jobs sont bornés à 5 min, tous ont `concurrency`, tous les checkouts sont superficiels.

## Conflit : l'agent hermes ouvre un chat ZeroClaw (InferHub)

Un conflit n'est plus une impasse. Quand le gate voit `mergeable_state = dirty`, il
**saisit un agent ZeroClaw** qui résout le conflit lui-même
(`.github/hermes/gate.py`, fonction `escalate` → `spawn_conflict_agent`).

- **Qui** : alias `hermes` du daemon ZeroClaw de la machine du runner. Le dépôt ne dit que
  ça : `provider custom.inferhub` (commentaire de `conflict_agent.py`) et un repli de jeton
  `/opt/zeroclaw/ephem/data/gateway-admin.token`. Le point d'entrée InferHub
  (`https://api.inferhub.dev/v1`) et le modèle (`combo/cheap`) viennent de la **config du
  daemon, hors dépôt** : à vérifier sur le worker, pas mesuré ici.
- **Comment** : le gate lance `python3 .github/hermes/conflict_agent.py --spawn` **détaché**
  (`Popen`, `start_new_session`) et rend la main : le job reste sous le plafond de 5 min.
  Le processus envoie le contexte du conflit en JSON-RPC `SendMessage` à
  `POST <HERMES_ZC_URL>/a2a/<agent>` (défaut `http://127.0.0.1:42617/a2a/hermes`), attend
  la réponse (`HERMES_AGENT_TIMEOUT`, 1500 s) puis publie le compte rendu en commentaire :
  check `hermes-agent/conflit` + étiquette `hermes/agent-conflit` si le conflit a disparu,
  `hermes/intervention-humaine` sinon.
- **Ce que l'agent fait** : clone dans `/tmp/hermes-pr-<n>` (jamais dans un arbre partagé),
  `git switch` sur la branche de la PR, `git merge origin/master` (ou rebase s'il n'y a
  aucun commit local), résolution qui garde les **deux intentions** (jamais un côté choisi
  à l'aveugle), gardes respectés (`tools/check-kotlin-limits.py`, couche 0),
  validation `./gradlew :core:test :app:testDebugUnitTest assembleDebug --offline`, puis
  **push simple de la seule branche de la PR** — `--force-with-lease` seulement si le
  dépôt a bougé.
- **Ce qu'il ne fait pas** : merger, toucher `master`, `.github/`, `.mergify.yml` ou les
  gardes, supprimer un commit de la PR. Après 3 tentatives ratées sur un fichier (ou si le
  push est refusé), il pose `hermes/intervention-humaine` et explique ce qui bloque.
- **Le gate est en dry-run** : variable de dépôt `HERMES_LITE_DRY_RUN = 1` (mesuré le
  25/10). Il rend un verdict et saisit l'agent, mais ne merge pas : c'est Mergify qui
  embarque.

### Garde-fous de la saisine

| Frein | Effet (dans `conflict_agent.py` / `gate.py`) |
|---|---|
| empreinte `pr-<n>-<sha12>.stamp` + cooldown 20 min | une seule saisine par tête de PR ; pas de boucle si l'agent ne pousse pas |
| `MAX_SPAWN_PER_RUN = 3` sur la même fenêtre de 20 min | une rafale de PRs en conflit ne lance pas 20 agents |
| étiquette `hermes/intervention-humaine` ou `hold` | aucune saisine, la main reste humaine |
| branche venue d'un fork (`head.repo != repo`) | aucune saisine (pas de droit d'écriture) |
| `HERMES_AGENT_RESOLVE=0` | coupe tout le mécanisme sans toucher au code |
| échec du chat / jeton introuvable | commentaire + étiquette humaine ; la saisine ne rougit jamais la CI |

### Droits du jeton sur le worker

Rappel : l'agent tourne **hors du job**, donc sans le `GITHUB_TOKEN` éphémère de la CI
(expiré bien avant la fin de sa résolution). Il s'authentifie avec `~/.gh-token` (présent
sur le r630) qui doit porter :

| Droit | Pourquoi |
|---|---|
| `contents: write` | pousser la résolution sur la branche de la PR |
| `issues: write` | commenter la PR et poser les étiquettes |
| `pull_requests: write` | relire l'état de la PR (conflit résolu ou non) |

Aucun secret de dépôt n'est configuré sur `testt` (mesuré le 25/10 : liste vide) : le gate
utilise donc `github.token` (`secrets.HERMES_LITE_TOKEN || github.token`). La portée exacte
de `~/.gh-token` **n'a pas été revérifiée** ici — constat 24/10 : `contents: read` seulement
(`Write access to repository not granted`), donc l'agent diagnostiquait sans pouvoir
corriger. Le mécanisme reste utile sans `contents: write` : il documente le conflit.

### Réglages et relance

Variables de dépôt (toutes optionnelles ; **une seule est définie** le 25/10 :
`HERMES_LITE_DRY_RUN=1`) : `HERMES_ZC_URL` (défaut `http://127.0.0.1:42617`),
`HERMES_ZC_AGENT` (défaut `hermes`), `HERMES_ZC_TOKEN_FILE` (défaut
`~/.config/zeroclaw-serveur/token`, repli `/opt/zeroclaw/ephem/data/gateway-admin.token`),
`HERMES_AGENT_TIMEOUT` (1500 s), `HERMES_AGENT_RESOLVE` (1), `HERMES_STATE_DIR`
(défaut `~/.cache/hermes-agent`), `HERMES_REQUIRED_CHECKS` (vide dans le workflow = merge
dès que la PR est fraîche).

Relance manuelle après une escalade :

```bash
python3 .github/hermes/conflict_agent.py --run --repo BaptisteFaisy/testt \
  --pr <N> --malgre-etat
```

Traces : `~/.cache/hermes-agent/pr-<N>.log` sur le worker. Le compte rendu est publié
**une seule fois** : l'agent rédige, le script poste.

## Côté agent : `sam-wt`

Outil du poste de travail, **absent du dépôt et du PATH du r630** : les commandes ci-dessous
sont donc **à vérifier** là où `sam-wt` est installé.

- `sam-wt new <sujet>` — worktree + branche depuis `origin/master` (jamais l'arbre partagé).
- `sam-wt test <sujet>` — `:core:test` + `:app:testDebugUnitTest` + `assembleDebug`.
- `sam-wt merge <sujet>` — pousse la branche, ouvre la PR avec le `## Scope` rempli
  automatiquement (un motif par fichier touché, requis par la Couche 0), puis attend que la
  file embarque et merge. Plus de merge local : `master` est protégé.
- `sam-wt sync` — remet le `master` local à niveau sur `origin/master`.
- `sam-wt rm <sujet>` — worktree et branche nettoyés une fois mergé.

## Renommage `sam-vision` → `testt` et le piège du 301

Mesuré le 25/10 avec l'API :

- `GET /repos/BaptisteFaisy/sam-vision` → **HTTP 301** vers
  `https://api.github.com/repositories/1432406615` ;
- `GET /repos/BaptisteFaisy/testt` → **HTTP 200**, `full_name = "BaptisteFaisy/testt"`.

Pièges :

- **Sans jeton, les deux noms répondent 404** (dépôt privé) : un 404 ne prouve pas que le
  dépôt n'existe pas, et ne distingue pas l'ancien nom du nouveau.
- **Un 301 sur une écriture est plus dangereux qu'une erreur** : `curl` sans `-L` affiche
  la redirection et n'écrit rien ; `curl -L` sur un `POST` **rejoue la requête en `GET`**
  (le corps est perdu) sauf `--post301`. Une relance de run « qui a l'air » de marcher peut
  donc n'avoir rien fait. Écrire directement sur `testt`.
- Le `origin` de ce worktree pointe encore sur l'ancienne URL (`git remote -v`) : à
  corriger sur le poste, hors périmètre de ce document. Le protocole git, lui, suit la
  redirection sans plainte — mesuré 25/10 : `git ls-remote origin HEAD` sur l'ancienne URL
  répond `9c60dab…` (et signale au passage que `origin/master` local est périmé : le
  worktree n'a pas été `fetch`). C'est donc l'**API** qui piège, pas `git`.
- Les workflows ne sont pas concernés : ils construisent leurs URLs avec
  `$GITHUB_API_URL` / `$GITHUB_REPOSITORY`, qui portent le nom courant.

## Pièges connus (et leur correctif)

### Runs bloqués en `action_required`

Un push fait par l'**API GitHub** (`PUT /pulls/N/update-branch`, rebase serveur) — ou plus
généralement un commit dont le committer est `github-actions[bot]` — crée des runs que
GitHub met **en attente d'approbation** : conclusion `action_required`. Aucun check n'est
publié, ni vert ni rouge ; la PR paraît immobile et la file attend un `check-success` qui
n'arrivera jamais (constat 24/10 : #30 et #87, 15 runs bloqués, PR figées une heure).

Double correctif :

1. **Automatique** — le gate relance ces runs avant de juger (`rerun_blocked_runs`, plafond
   20 runs, idempotent dans `.github/hermes/gate.py`), puis rend la main : le verdict tombe
   au passage suivant. C'est aussi pourquoi `HERMES_LITE_REFRESH=0` : le rebase serveur est
   le métier de Mergify (`update_method: rebase`), dont le push déclenche la CI normalement.
2. **Manuel** — pour un run précis :

```bash
curl -fsS -X POST -H "Authorization: Bearer $(cat ~/.gh-token)" \
  https://api.github.com/repos/BaptisteFaisy/testt/actions/runs/<run_id>/rerun
```

Un push humain produit aussi des runs normaux : c'est pourquoi « pousser soi-même » débloque
là où le rebase serveur échoue.

### File qui n'embarque pas après un `--force-with-lease`

Une tête de PR déjà évaluée par Mergify, remplacée par un push forcé, peut laisser la file
inerte : checks verts, protections passées, rien ne bouge. Le geste de réentrée est
explicite :

```bash
curl -fsS -X POST -H "Authorization: Bearer $(cat ~/.gh-token)" \
  -d '{"body":"@Mergifyio queue"}' \
  https://api.github.com/repos/BaptisteFaisy/testt/issues/<n>/comments
```

Constaté sur #30 (vert, `MERGEABLE`, immobile 5 min, mergée 30 s après la commande).