# File de merge — ce qui est exigé, ce qui merge tout seul

Une PR entre dans la file `default` quand **toutes** ces conditions sont vraies
(`.mergify.yml`, lu sur la branche par défaut `master`) :

- `ci` verte — tests `:core` + `:app`, `assembleDebug`, garde anti-secret (worker `r630-sam-vision-0N`) ;
- `ci-budget` verte — aucun job déclaré à plus de 5 min (`~/Bureau/CI-BUDGET-5MIN.md`) ;
- pas de conflit, pas brouillon, base `master`, pas l'étiquette `hold`.

Mergify **rebase** alors la PR sur le master courant, **re-exécute** les checks sur
le résultat, puis merge en `rebase`. Deux PR peuvent être testées en batch
(`batch_size` 1-3, `max_parallel_checks: 2` = taille du pool) — d'où
`Require branches to be up to date` **désactivé** dans la protection GitHub :
les checks spéculatifs de Mergify sont incompatibles avec cette option, et la
file fait déjà le travail (rebase + re-test avant merge).

Frein : étiqueter la PR `hold` la sort de la file. Relance manuelle :
commentaire `@Mergifyio refresh`, embarquement forcé : `@Mergifyio queue`.

La Couche 0 (`couche0`, périmètre déclaré + motifs interdits) est **informative**
tant qu'elle n'est pas calibrée ; voir `.github/couche0/check_couche0.py`.

**Auto-embarquement** : `merge_protections_settings.auto_merge_conditions: true` n'a d'effet
que si le **produit Merge Protections est activé** sur le dépôt (doc Mergify,
`/merge-protections/setup`). Tant qu'il ne l'est pas, la file n'admet une PR que sur
commentaire `@Mergifyio queue` — vérifiable au check `Mergify Merge Protections` absent.
