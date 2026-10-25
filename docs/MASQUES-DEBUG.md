# MASQUES SAM + STATS DE DEBUG (accueil iHear)

Ce que l'utilisateur voit sur la page caméra de l'app, et pourquoi.

## Ce qui s'affiche

Pendant l'enregistrement vidéo (un tap pour filmer, un second pour arrêter) :

- les **masques SAM** de la dernière frame analysée, colorisés et posés sur l'aperçu au
  cadrage exact de la `PreviewView` (`ContentScale.Crop`, comme le Direct) ;
- la **ligne d'état** : `SAM · 1,4 ips · 640 ms · 8 objets · A100-40GB` ;
- le **détail** : endpoint utilisé, taille de frame, latence moyenne + p95 + durée serveur,
  nombre de frames et de masques, concepts reconnus, dernière erreur.

Après l'arrêt, les derniers masques restent affichés (on voit ce que SAM a trouvé) jusqu'au
prochain enregistrement ou à la sortie de l'écran.

## Coût GPU

Rien ne part à SAM hors enregistrement : l'analyseur caméra livre des frames 512 px en
continu, mais `LiveProbe.onFrame` n'est appelé que si `recordingVideo` est vrai. Le GPU ne
se réveille donc pas parce qu'on regarde la page caméra.

Pendant l'enregistrement, une seule requête est en vol (`FrameGate` de `LiveSession`) et la
cadence s'adapte à la latence (350–900 ms). Un direct de 10 s ≈ 10–25 frames.

## Branchement

| Élément | Fichier |
|---|---|
| Analyseur de frames + `bind` caméra | `app/…/ihear/IhearCameraPane.kt` (`IhearCameraHost`) |
| Envoi `/live`, masques, stats | `app/…/ihear/LiveProbe.kt` |
| Formatage (pur, testé JVM) | `app/…/ihear/LiveProbeText.kt` |
| Panneau à l'écran | `app/…/ihear/LiveDebugPanel.kt` |
| Rendu d'un masque (partagé Direct) | `app/…/CameraSurface.kt` |

> **Piège à connaître** : un `AndroidView` est une vraie `View`, dessinée par le `ViewGroup`
> **après** le canevas Compose. Un masque composé en Compose au-dessus de l'aperçu passe donc
> *derrière* lui (invisible, alors que le backend announce des objets). D'où `CameraSurface` :
> la `PreviewView` et la couche de masques sont deux enfants d'un même `FrameLayout`.

Endpoint : `Settings.samBaseUrl` (Réglages) sinon `BuildConfig.SAM_BASE_URL`
(`sam.base.url` de `local.properties`). Vide ⇒ le panneau affiche
« SAM · aucun endpoint (Réglages) » et rien ne part.

## Recette

1. Réglages : Base URL SAM = endpoint Modal (`/health` doit répondre) ; quitter.
2. Accueil caméra : un tap → « ● REC », la ligne SAM se remplit en 1–3 s (le premier appel
   paie le réveil du conteneur, ~30 s).
3. Vérifier qu'un masque tombe **sur** l'objet, que le nombre d'objets bouge, que le p95
   reste sous ~1 000 ms.
4. Second tap : « ● REC » part, les masques restent, la vidéo est archivée.

## Distances et voix pendant l'enregistrement (2026-10-25, `feat/ihear-rec-distances`)

La page caméra porte maintenant les trois choses demandées au même endroit, pendant le REC :

| Élément | Où | Source |
|---|---|---|
| masques SAM | sur l'aperçu (`CameraSurface`) | `/live` |
| pastilles « ≈ 1,1 m » | par objet, `LiveDistanceLayer` composé **après** l'`AndroidView` | `DistanceMeter` (100 % téléphone) |
| distance du plus proche | ligne d'état : `SAM · 1,5 ips · 622 ms · 7 objets · plus proche ≈ 1,1 m · A100` | idem |
| latence moyenne / p95 / serveur | panneau de debug (inchangé) | `/live` |
| voix « Personne à gauche, à 1,4 mètre » | ElevenLabs, une annonce par scène | `PositionAnnouncer` + `DistanceText.spoken` |

- Bascule des Réglages : « Annoncer les objets et leur distance » (`ttsAnnouncePositions` +
  clé ElevenLabs). Coupée ⇒ aucune phrase, mais les distances restent affichées.
- Bascule « distances » (`distanceOverlayEnabled`) ⇒ plus de mesure du tout.
- Focale : `LensSpec.fromSettings(settings.cameraFovDeg)` (champ forcé, sinon 67° de repli).
- La voix ne coupe jamais une réponse du chat (`playerIdle()`), et une phrase déjà dite
  n'est pas répétée (`LiveProbeState.spoken` + `lastSpoken` côté ViewModel).
- Mesure non lissée ici (pas de journal de suivi sur cette page) : la distance bouge donc
  un peu plus qu'en Direct.
