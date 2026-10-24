# File de merge — ce qui est exigé, ce qui merge tout seul

Une PR entre dans la file `default` quand **toutes** ces conditions sont vraies
(`.mergify.yml`, lu sur la branche par défaut `master`) :

- `ci` verte — tests `:core` + `:app`, `assembleDebug`, **`assembleRelease` + `lintDebug`**, gardes
  anti-secret / java.home / taille Kotlin (worker `r630-sam-vision-0N`, ~1 min mesuré) ;
- `ci-budget` verte — aucun job déclaré à plus de 5 min (`~/Bureau/CI-BUDGET-5MIN.md`) ;
- pas de conflit, pas brouillon, base `master`, pas l'étiquette `hold`.

Mergify teste **sur la branche de la PR** (in-place checks : `batch_size: 1`,
`max_parallel_checks: 1`, `max_checks_retries: 0`, pas de `merge_conditions`
distinct) : un seul run CI par PR au lieu de deux, et **merge immédiat** quand la
base n'a pas bougé sous la PR. Si elle a bougé, la PR est rebasée et re-testée. Deux PR peuvent être testées en batch
(`batch_size` 1-3, `max_parallel_checks: 2` = taille du pool) — d'où
`Require branches to be up to date` **désactivé** dans la protection GitHub :
les checks spéculatifs de Mergify sont incompatibles avec cette option, et la
file fait déjà le travail (rebase + re-test avant merge).

Frein : étiqueter la PR `hold` la sort de la file. Relance manuelle :
commentaire `@Mergifyio refresh`, embarquement forcé : `@Mergifyio queue`.

La Couche 0 (`couche0`, périmètre déclaré + motifs interdits) est **exigée** par la file et
les protections : une PR qui touche un fichier non déclaré dans `## Scope`, qui ajoute une
clé en clair ou qui modifie un fichier de garde sans `SAMVISION_ALLOW_GUARD_BUMP` n'entre
pas dans la file. `sam-wt merge` remplit le `## Scope` tout seul. Garde :
`.github/couche0/check_couche0.py`.

**Auto-embarquement** : `merge_protections_settings.auto_merge_conditions: true` n'a d'effet
que si le **produit Merge Protections est activé** sur le dépôt (doc Mergify,
`/merge-protections/setup`) — il l'est depuis le 24/10, donc une PR verte part seule en file.
Le check `Mergify Merge Protections` est le témoin visuel de cette activation ; s'il
disparaît, on retombe sur l'embarquement manuel `@Mergifyio queue`.
## Côté agent : `sam-wt`

- `sam-wt new <sujet>` — worktree + branche **depuis `origin/master`** (jamais l'arbre partagé).
- `sam-wt test <sujet>` — `:core:test` + `:app:testDebugUnitTest` + `assembleDebug`.
- `sam-wt merge <sujet>` — pousse la branche, **ouvre la PR avec le `## Scope` rempli
  automatiquement** (un motif par fichier touché, requis par la Couche 0), puis attend que
  la file embarque et merge. Il n'y a plus de merge local : `master` est protégé.
- `sam-wt sync` — remet le `master` local à niveau sur `origin/master`.
- `sam-wt rm <sujet>` — worktree et branche nettoyés une fois mergé.

Si le diff touche `.mergify.yml` ou `.github/`, la PR doit porter la mention
`SAMVISION_ALLOW_GUARD_BUMP` dans son corps (`sam-wt merge` le rappelle).

## Conflit : l'agent hermes ouvre un chat ZeroClaw (InferHub)

Un conflit n'est plus une impasse. Quand le gate voit `mergeable_state = dirty`, il
n'écrit plus seulement un check rouge : il **saisit un agent ZeroClaw** qui va
résoudre le conflit lui-même.

- **Qui** : alias `hermes` du daemon ZeroClaw qui tourne sur le worker
  (`/opt/zeroclaw/ephem`), branché sur **InferHub** par le provider
  `custom.inferhub_ci` (`https://api.inferhub.dev/v1`, modèle `combo/cheap`).
- **Comment** : `.github/hermes/conflict_agent.py`, lancé **détaché** par le gate
  (`--spawn`). Le job ne fait que le lancement : il reste sous le plafond de 5 min
  imposé par `ci-budget`. Le processus détaché envoie le contexte du conflit à
  `POST /a2a/hermes` sur `127.0.0.1:42617` (le runner et le daemon tournent sous le
  même utilisateur, le jeton client est dans `~/.config/zeroclaw-serveur/token`),
  attend la réponse puis la publie en commentaire de la PR.
- **Ce que l'agent fait** : worktree hors de l'arbre partagé, rebase sur `origin/master`,
  résolution qui garde les **deux intentions** (jamais un côté choisi à l'aveugle),
  `:core:test` + `:app:testDebugUnitTest` + `assembleDebug` verts, puis
  `--force-with-lease` sur **la branche de la PR** (jamais sur `master`). Il commente
  son travail et laisse la file reprendre la main au push suivant.
- **Ce qu'il ne fait pas** : merger, toucher `master`, `.github/`, `.mergify.yml` ou
  les gardes. Après 3 tentatives ratées sur un fichier, il commente le blocage et pose
  `hermes/intervention-humaine`.

### Garde-fous

| Frein | Effet |
|---|---|
| empreinte `pr-<n>-<sha>` + cooldown 20 min | une seule saisine par tête de PR ; pas de boucle si l'agent ne pousse pas |
| plafond de 3 saisines par période | une rafale de PRs en conflit ne lance pas 20 agents |
| label `hermes/intervention-humaine` ou `hold` | aucune saisine, la main reste humaine |
| branche venue d'un fork | aucune saisine (pas de droit d'écriture) |
| `vars.HERMES_AGENT_RESOLVE=0` | coupe tout le mécanisme sans toucher au code |
| échec du chat | commentaire + label humain ; le gate reste vert côté saisine (jamais de CI rouge pour un agent) |

### Réglages et relance

Variables de dépôt (toutes optionnelles) : `HERMES_ZC_URL` (défaut
`http://127.0.0.1:42617`), `HERMES_ZC_AGENT` (défaut `hermes`), `HERMES_ZC_TOKEN_FILE`
(défaut `~/.config/zeroclaw-serveur/token`), `HERMES_AGENT_TIMEOUT` (1500 s),
`HERMES_AGENT_RESOLVE` (1).

Relance manuelle après une escalade :

```bash
python3 .github/hermes/conflict_agent.py --run --repo BaptisteFaisy/sam-vision \
  --pr <N> --malgre-etat
```

Traces : `~/.cache/hermes-agent/pr-<N>.log` sur le worker. Check
`hermes-agent/conflit`, étiquette `hermes/agent-conflit` quand l'agent a rendu son
compte rendu.

## Chronologie mesurée (worker `r630-sam-vision-0N`, 24/10)

| Étape | Durée |
|---|---|
| `:core:testDebugUnitTest` + `:app:testDebugUnitTest` + `:app:assembleDebug` | 15 s |
| `:app:assembleRelease` | 41 s |
| `:app:lintDebug` | 22 s |
| job `ci` complet (code) | **46 s** (1 min 00 en CI, cache froid) |
| job `ci` d'une PR **sans code** (docs, config) | **~10 s** — Gradle sauté |
| `couche0` / `ci-budget` / `stale-base-guard` / `Verdict` | 6 à 10 s, en parallèle |

4 workers `r630-sam-vision-01..04`, chacun avec son cache Gradle isolé : les CI des
PR tournent en parallèle sans se battre pour les verrous du cache.

Verdict de PR : **≈ 45 s** pour du code, **≈ 10 s** pour de la doc — budget 5 min. Toute évolution qui ferait
passer le job `ci` au-delà de ~3 min doit sortir de la PR (règle CI 5 min).

- Mesure de référence du flux (2026-10-24 22:20).

- Flux mesuré : PR de doc, checks ~15 s, file déclenchée dès le vert.

- Troisième mesure : chemin rapide quand la branche est à jour et verte.

- Quatrième mesure : rebase + re-test + merge, sans passer par la file.

- Cinquième mesure : attente de l’état de fusion au lieu de basculer sur la file.

- Sixième mesure : rebase + re-test + merge, sans mergeStateStatus.
