# MASQUES SAM + STATS DE DEBUG (accueil iSee)

Ce que l'utilisateur voit sur la page caméra de l'app, et pourquoi.
Revérifié **code** le 2026-10-25 (HEAD `8f9876a`, contient #135 masques/distances/voix, #136 sonde WS et #137
WS-only). Les mesures citées restent datées ; ce qui ne vient pas d'une mesure est marqué « non mesuré ».

## Ce qui s'affiche

Pendant l'enregistrement vidéo — dans la fenêtre caméra iSee, **un tap démarre le REC, un second l'arrête**
(`IhearViewModel.kt:217`, gestualité dans `IhearCameraWindow.kt`) :

- les **masques SAM** de la dernière frame analysée, colorisés et posés sur l'aperçu au cadrage exact de la
  `PreviewView` (`CENTER_CROP` dans la vue native, `app/…/CameraSurface.kt:26-28`) ;
- les **pastilles de distance** par objet (`≈ 1,1 m`, composées **après** l'`AndroidView`, voir z-order) ;
- la **ligne d'état**, texte exact produit par `liveProbeHeadline` (`app/…/ihear/LiveProbeText.kt:15-22`) :
  `SAM · X ips · Y ms · N objets · plus proche ≈ Z m` — le suffixe « plus proche » n'apparaît que si une
  distance est mesurée. **Le nom du GPU n'est plus dans cette ligne** : l'exemple historique
  `SAM · 1,4 ips · 640 ms · 8 objets · A100-40GB` (doc du 25/10, #135) est **périmé** — le GPU est passé sur
  sa propre ligne de détail `gpu : …` (`LiveProbeText.kt:41`, tronqué à 28 caractères à la capture,
  `LiveProbe.kt:389`). Le commentaire de tête de `liveProbeHeadline` (`LiveProbeText.kt:12-13`) a d'ailleurs
  gardé « · A100-40GB » : **commentaire périmé**, la fonction ne l'ajoute plus (`:21`).
- le **détail** (`liveProbeDetails`, `LiveProbeText.kt:29-45`) : `endpoint :` (raccourci à 46 caractères),
  `frame : L×h`, `latence : Y ms · p95 P ms · serveur S ms`, `F frames · M masques`, `transport : WebSocket`
  (ou `WebSocket KO`), `suivis (N) :`, `reconnus :`, `gpu :`, `distances :` (3 max, du plus proche au plus
  loin), `dit : <phrase prononcée>`, `erreur : <dernière>`. En mode dense (petite fenêtre flottante),
  `endpoint`/`frame`/`gpu` sont retirés (`LiveDebugPanel.kt:56-71`) — l'ancienne note « panneau inchangé pendant
  le REC » est donc fausse : le panneau **s'allège** en dense.

Avant la première frame analysée, un message central s'affiche (`liveProbeNotice`, `LiveProbeText.kt:75-80`) :
`réveil du GPU SAM… (≈ 30 s au premier lancement)` pendant le warming, puis `en attente de la première
analyse…` ; dès la première frame, il disparaît (les masques parlent d'eux-mêmes).

Après l'arrêt, les derniers masques **restent affichés** jusqu'au prochain enregistrement ou à la sortie
d'écran ; une erreur sur une frame ne remet **pas** le compte d'objets à zéro (`LiveProbe.kt:278-283`).

## Coût GPU

Rien ne part à SAM hors enregistrement : l'analyseur caméra livre du JPEG base64 en continu, mais la frame
n'est **même pas convertie** hors REC (`IhearCameraWindow.kt:80-81` : `wantsFrame` et `onFrame` sont
conditionnés à `recordingVideo`) et `LiveProbe.onFrame` jette tout si aucune session n'existe. Le GPU ne se
réveille donc pas parce qu'on regarde la page caméra.

Pendant l'enregistrement, la session tourne en `Cadence.Fast` (`core/…/live/LiveSession.kt:36-46`, mesuré
25/10) : **pas de plancher artificiel**, mais **budget de latence 200 ms** — jusqu'à **2 requêtes en vol**
tant que la moyenne reste sous le budget, retour à 1 créneau dès qu'elle dépasse (à 2 en vol : 344 ms mesurés ;
à 1 : 160–180 ms). L'ancienne formule « une seule requête en vol (`FrameGate`) et cadence 350–900 ms » décrivait
le direct (`Cadence.Direct`), pas la sonde iSee : **périmée depuis #135/#137**. L'estimation « un direct de
10 s ≈ 10–25 frames » n'a pas été re-mesurée : **non mesuré**.

Une frame de réserve est convertie pendant que la requête court (file d'une seule image, `LiveProbe.kt:200-216`) ;
au-delà, les frames sont jetées — la scène a changé.

## Branchement

| Élément | Fichier |
|---|---|
| Analyseur de frames + `bind` caméra | `app/…/ihear/IhearCameraPane.kt` (`IhearCameraHost`, cadre 448 px `IHEAR_FRAME_EDGE:37`) |
| Envoi `/live/ws`, masques, stats | `app/…/ihear/LiveProbe.kt` (avant #136 le commentaire disait « envoi `/live` » ; l'appel passe par `liveClient()`, **WS uniquement**) |
| Formatage (pur, testé JVM) | `app/…/ihear/LiveProbeText.kt` (17 tests `LiveProbeTextTest`) |
| Panneau à l'écran | `app/…/ihear/LiveDebugPanel.kt` |
| Rendu des masques sur l'aperçu | `app/…/CameraSurface.kt` (partagé avec le Direct, `LiveScreen.kt:191-196`) |
| Pastilles de distance | `app/…/DistanceOverlay.kt` (`LiveDistanceLayer`) + câblage `IhearCameraWindow.kt:162-167` |
| Câblage Réglages → sonde | `app/…/ihear/ProbeWiring.kt` |

> **Piège de z-order (incident documenté le 25/10 — « les masques étaient dessinés sous l'aperçu caméra »,
> corrigé dans master avec #135)** : un `AndroidView` est une vraie `View`,
> dessinée par le `ViewGroup` **après** le canevas Compose. Un masque ou une pastille composés en Compose
> **avant** l'`AndroidView` passe donc *derrière* l'aperçu — invisible, alors que le backend annonce des
> objets. D'où deux protections dans le code : la `PreviewView` et la couche de masques sont deux enfants
> d'un même `FrameLayout` (`CameraSurface.kt:10-22` pour l'explication, `:31-34` pour l'ordre des enfants), et `LiveDistanceLayer` est composé **après**
> l'`AndroidView` dans les deux écrans (`IhearCameraWindow.kt:162-167`, `LiveScreen.kt:191-196`).

**Endpoint** : plus simplement `Settings.samBaseUrl` — c'est `resolveSamEndpoint` : **saisie manuelle
(hors preset) > preset de la région EU/US choisie > URL injectée au build** (`core/…/settings/SamRegion.kt:49-58`,
câblée `ProbeWiring.kt:19-27` ; `sam.base.url`/`sam.us.base.url` de `local.properties`). Piège corrélé :
une URL qui n'est **que** le preset d'une région est effacée au chargement (`SettingsSeed.dropPresetUrl`,
`app/…/SettingsSeed.kt:24`) — sans ça la région ne s'appliquerait jamais. Vide ⇒ le panneau affiche
`SAM · aucun endpoint (Réglages)` (`LiveProbe.kt:159` + `liveProbeHeadline` sur erreur sans frame) et rien ne part.

## Décodage et colorisation des masques

La chaîne réelle, vérifiée dans `core/…/mask/Masks.kt` et `MaskCore.kt` (aucun canal **ALPHA_8** nulle part —
l'hypothèse « PNG en ALPHA_8 » est **sans objet** dans ce code, `grep ALPHA_8` vide au 25/10) :

1. base64 tolérant (préfixe `data:…;base64,` et espaces acceptés) → octets (`MaskCore.base64Bytes`) ;
2. `BitmapFactory.decodeByteArray` sur le **PNG** renvoyé par `/live/ws` (`decodeGrayMask`) ; si le decode
   échoue, l'objet est **silencieusement absent** de la liste de masques (`mapIndexedNotNull`, `LiveProbe.kt:361-367`)
   — la ligne `F frames · M masques` du détail trahit l'écart ;
3. passage en **masque gris par luminance** (255 = objet ; `grayFromArgb`, alpha ignoré) ;
4. colorisation : couleur d'objet = `maskColorAt(index)` (cycle de la palette `Theme.MASK_COLORS`,
   `app/…/Theme.kt:36`), opacité de base **`MASK_ALPHA = 115`** (`Theme.kt:34` ; l'assertion `UiLogicTest:70`
   vérifie seulement que la valeur reste dans 1..255, pas sa lisibilité), l'alpha de chaque pixel est modulé
   par son niveau (`colorizeGray` : `alpha × niveau / 255`, niveau 0 = transparent) ;
5. sortie **ARGB_8888**, tous les masques à la taille de la frame, fusionnés en un seul `Bitmap` par
   `CameraSurface.merge` (`CameraSurface.kt:45-54`) et affichés en `CENTER_CROP` — cadrage identique à la
   `PreviewView` (`FILL_CENTER`). Le direct recolorise de la même façon (`LiveViewModel.kt:311`,
   `AppViewModel.kt:188` pour la photo).

Couverture JVM : `core.mask.MaskCoreTest` **6** (base64 data-prefix, IHDR, luminance, colorisation, masque 255) ;
formatage du panneau `app.ihear.LiveProbeTextTest` **17**. Le rendu bitmap lui-même (decoding Android) n'est
pas testé en JVM.

## Recette

1. Réglages : région EU ou US, ou Base URL SAM manuelle (le `GET /health` doit répondre ; c'est le seul appel
   HTTP de ce chemin — les frames partent en WS). Quitter les Réglages.
2. Accueil iSee : un tap sur la fenêtre caméra → « ● REC ». **Le premier appel paie le réveil du conteneur :
   23,6–26,8 s mesurés le 25/10 (30–50 s dans le code) ; il ne doit PAS afficher d'erreur de timeout** —
   le délai du handshake suit celui de la frame (`LiveClientFactory.kt:9-12`), le premier appel dispose d'une
   session froide à **60 s** (`LiveProbe.kt:406`) et la boucle de réveil retente `6 × 10 s` (`:409-410`).
   Un timeout à la première frame afficherait `réveil du GPU en cours… (nouvel essai à la frame suivante)`
   (`:356-358`) : signe que le froid n'a pas tenu.
3. Vérifier : un masque tombe **sur** l'objet ; `transport : WebSocket` dans le détail ; la ligne `gpu :` est
   seule sur sa ligne ; le nombre d'objets bouge ; le p95 — l'ancienne cible « sous ~1 000 ms » (25/10, avant
   WS) n'a pas d'équivalent mesuré en WS : à re-caler sur place, **non mesuré** en REC WS au 25/10.
4. Second tap : « ● REC » part, les masques restent, la vidéo est archivée.

## Distances et voix pendant l'enregistrement (fonctionnalité entrée sur master avec #135)

La page caméra porte les trois choses au même endroit, pendant le REC :

| Élément | Où | Source |
|---|---|---|
| masques SAM | sur l'aperçu (`CameraSurface`, z-order ci-dessus) | `/live/ws` |
| pastilles « ≈ 1,1 m » | par objet, `LiveDistanceLayer` composé **après** l'`AndroidView` (`IhearCameraWindow.kt:162-167`) ; **sans nom de concept** en REC (les masques disent déjà de quel objet il s'agit) ; avec concept sur la photo du double tap (`withLabel`, `:179`) | `DistanceMeter` (100 % téléphone) |
| distance du plus proche | ligne d'état `… · plus proche ≈ 1,1 m` | idem |
| latences + transport + GPU | détail du panneau (`latence : … · p95 … · serveur … ms`, `transport :`, `gpu :`) | `/live/ws`, `/health` |
| voix « Personne à gauche, à 1,4 mètre » | ElevenLabs, une annonce par scène (`dit :` garde la dernière en mémoire) | `PositionAnnouncer` + `DistanceText.spoken` |

Exemple relevé sur téléphone le 25/10 (#135) : `SAM · 1,5 ips · 622 ms · 7 objets · plus proche ≈ 1,1 m · A100`
— **depuis #136 le tronçon `· A100` de la ligne d'état n'existe plus** (le GPU est passé dans le détail) ; garde
pour l'ordre des valeurs, pas pour la forme.

- Bascule des Réglages : « Annoncer les objets et leur distance » (`ttsAnnouncePositions` **ET** clé ElevenLabs,
  `Settings.announcePositions` `Settings.kt:124`). Coupée **ou** clé absente ⇒ **aucune phrase, aucun message
  d'erreur** (piège (b) de `docs/TESTING.md`) ; les distances restent affichées.
- Bascule « Distances » (`distanceOverlayEnabled`) ⇒ plus de mesure du tout (`DistanceMeter.enabled`).
- Focale en REC : `LensSpec.fromSettings(settings.cameraFovDeg)` (`ProbeWiring.kt:23`) — champ forcé (borné
  15–130°), sinon repli 67° ; **la lecture du capteur n'alimente ici que les pastilles de la photo** du double
  tap (`IhearPhotoDistances.chosenLens`), pas la sonde.
- La voix ne coupe jamais une réponse du chat (`playerIdle()`, `ProbeWiring.kt:36-45`), et une scène déjà dite
  n'est pas répétée (`PositionAnnouncer` + `LiveProbeState.spoken`).
- **Mesure non lissée** pendant le REC : pas de journal de pistes sur cette page (`LiveProbe.readingsOf`) —
  la pastille bouge un peu plus qu'en Direct.
