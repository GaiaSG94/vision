# Mode US — la GPU SAM 3.1 aux États-Unis

Réglages → section **« GPU SAM 3.1 — région »** : deux boutons **EU · Europe (eu)** et
**US · États-Unis (us)**. Le choix est persisté (`samRegion`, chiffré comme le reste) et
s'applique **à tous les écrans** : le Direct (`LiveState.liveBaseUrl`) et l'analyse photo
(`AppViewModel`).

## Ce que fait le réglage (et ce qu'il ne fait pas)

Il **élit l'endpoint** appelé, il **n'allume aucune carte** :

| Région | App Modal | Conteneur | Endpoint HTTP |
|---|---|---|---|
| EU | `sam3-eu-a100` | `region=eu`, `routing_region=eu-west` | `https://baptiste-faisy--sam3-eu-a100-sam3live-web.eu-west.modal.run` |
| US | `sam3-us-a100` | `region=us`, `routing_region=us-east` | `https://baptiste-faisy--sam3-us-a100-sam3live-web.us-east.modal.run` |

Ordre de résolution (`core/settings/SamRegion.kt::resolveSamEndpoint`) :

1. **Base URL SAM 3.1** saisie à la main dans les Réglages (hors preset) ;
2. preset de la **région** choisie ;
3. URL injectée au build pour cette région (`sam.base.url` / `sam.us.base.url` de
   `local.properties`).

Une URL qui n'est **que** le preset d'une région (injectée par le build, ou persistée par une
version antérieure) ne compte pas comme une saisie manuelle : elle est effacée au chargement
(`SettingsSeed.dropPresetUrl`) pour ne pas figer le mode. Sans ça, une install existante
serait bloquée sur l'EU et la bascule US semblerait sans effet.

## Allumer la carte américaine

```bash
~/bin/sam3-gpu-us status                     # app US déployée ? conteneurs ? bail ?
~/bin/sam3-gpu-us on --reason "mode US"      # déploie si besoin + bail 30 min
~/bin/sam3-gpu-us off --reason "fini"        # extinction (app laissée déployée)
```

`sam3-gpu-us` est un simple enrobage : `SAM3_APP=sam3-us-a100 SAM3_REGION=us
SAM3_ROUTING=us-east` autour de `~/bin/sam3-gpu` — mêmes règles, même journal
(`~/logs/sam3-gpu.log`), même bail extinctif, même watchdog.

- Premier `on` = **déploiement** puis réveil : cold start ~50 s (poids 20,9 Go + build du
  predictor 22,3 s). `--warm` attend que `/health` réponde.
- **Une seule GPU, jamais deux** : `sam3-gpu on` / `sam3-gpu-us on` refusent d'allumer tant
  qu'un conteneur d'une autre app tourne ; `modal-one-gpu-watch` (r630) ramène à une carte.
  Donc EU et US ne tournent jamais en même temps : on éteint l'une avant d'allumer l'autre.
- Coût : `region=us` est une région **large** (×1,15 ; `us-west` serait ×1,75). A100-40GB :
  2,415 $/h, extinction automatique à la fin du bail.

## Vérifier

```bash
~/bin/sam3-gpu-us status
curl -s https://baptiste-faisy--sam3-us-a100-sam3live-web.us-east.modal.run/health
# → {"ok":true,"gpu":"NVIDIA A100-…","region":"us",…}   (réveille la carte si elle dort)
```

Dans l'app : Réglages affiche « Endpoint actif : …us-east… » sous les deux boutons ;
« Tester SAM » appelle `/health` de la région choisie et affiche `(US)` pendant le test.

## Pièges

- **`routing_region` ne se change pas** après le premier déploiement d'une fonction : c'est
  pour ça que le mode US est une **app distincte** (`sam3-us-a100`) et pas un redéploiement de
  `sam3-eu-a100` avec d'autres variables.
- App US non déployée ou arrêtée ⇒ le proxy Modal répond `404 invalid function call` sur
  toutes les routes ; l'app le traduit en `BackendStopped` (« backend arrêté (404) — relance-le :
  sam3-gpu on »).
- Depuis la France, l'EU reste plus rapide (routage Dublin) : le mode US sert quand l'Europe
  est à sec ou indisponible, pas pour gagner en latence.
- Le preset suppose le profil Modal `baptiste-faisy`. Un autre profil ⇒ mettre l'URL dans
  « Base URL SAM 3.1 » (elle gagne sur le preset).