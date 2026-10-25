# Distances en temps réel sur le Direct — 2026-10-25

Chaque objet suivi du Direct porte sa distance à la caméra : une pastille
« ≈ 3,2 m » posée au-dessus de sa boîte, dans la couleur de son masque, plus la
distance de l'objet le plus proche dans la ligne d'état et une pastille par
concept en bas d'écran (« personne 2,1 m »).

**Aucun GPU, aucune requête** : tout est calculé sur le téléphone, à partir de ce
que `/live` renvoie déjà (label + boîte). Une frame sans objet calibré n'affiche
rien du tout — pas de pastille « ? », pas de chiffre inventé.

## La mesure : sténopé sur hauteur réelle connue

```
distance (m) = hauteur réelle de l'objet (m) × focale (px) / hauteur de la boîte (px)
focale (px)  = (largeur de l'image / 2) / tan(champ de vision / 2)
```

Deux entrées, deux sources :

| Entrée | D'où elle vient |
|---|---|
| **Hauteur réelle** | `KnownSizes` : table label → mètres (personne 1,70 · voiture 1,45 · porte 2,05 · bouteille 0,25…). Casse, accents, pluriel et expressions (« sac à dos ») sont neutralisés. Concept inconnu ⇒ **pas de distance**. |
| **Focale en pixels** | `LensSpec.focalPx(width, height)` depuis les champs de vision du capteur (focale mm + taille physique, lus par l'interop Camera2 à la liaison), sinon le champ saisi dans Réglages, sinon 67° (grand-angle typique). L'axe vertical sert quand l'image est en portrait — le direct redresse les frames. |

Bornes de plausibilité (`Pinhole`) : rien sous **0,3 m**, rien au-delà de **60 m**,
rien pour une boîte de moins de **4 px** de haut. Hors bornes ⇒ pas d'affichage.

**Objet coupé par le bord haut ou bas ⇒ pas de distance** (`Pinhole.isVerticallyCut`,
marge de 2 px). Vérifié sur téléphone le 25/10 : une couette segmentée à tort en
« personne », collée aux deux bords, sortait à **31 m** — la boîte ne mesurait plus
la hauteur réelle. Les bords gauche/droite restent acceptés : seule la hauteur
porte la mesure.

## Lissage : lisible à 1-2 images par seconde

`DistanceSmoother` : moyenne mobile exponentielle **par piste** (l'identité stable
du journal du direct, `LiveTimeline.record` renvoie désormais l'id de chaque
détection). Deux vitesses — 0,35 pour accompagner un objet qui s'approche,
0,85 quand la mesure fait un bond de plus de 50 % (l'objet a réellement bougé,
ou la piste a été reprise). Une piste muette plus de 2 s est oubliée : l'objet
qui revient est mesuré à neuf.

## Précision — ce que le chiffre vaut

±15-20 % sur un objet **debout et entier**, avec trois causes d'erreur connues :

- **hauteur réelle supposée** : un enfant mesuré comme « personne » (1,70 m) sort
  deux fois trop loin ;
- **objet coupé** par un bord haut/bas : écarté depuis le 25/10 (il n'affiche rien) ;
- **objet penché ou allongé** (chaise vue de dessus, table basse) : la boîte ne
  mesure plus la hauteur réelle.

L'étiquette porte le « ≈ » pour cette raison, et le format dégressif (cm sous 1 m,
décimètre jusqu'à 10 m, mètre au-delà) ne promet pas plus de précision que ça.

## Cadrage des pastilles

Une pastille est centrée sur le milieu horizontal de sa boîte, puis **ramenée dans
le cadre** (`pillTranslationX`) et **hors du bandeau du haut** (`pillTranslationY`,
230 dp : boutons « Arrêter / Réglages », ligne d'état, concepts). Sans ça, un objet
collé au bord droit sortait à moitié de l'écran et un objet dans le haut de l'image
voyait sa distance cachée derrière les boutons (constaté le 25/10, corrigé).

## Réglages

- **Distances sur le direct** (bascule, activée par défaut) : coupe l'overlay et
  le calcul (`DistanceMeter.enabled`).
- **Champ de vision caméra (°)** : `0` = lecture du capteur (voie normale). À
  saisir si le driver est muet ou si l'étalonnage dérive — la valeur se vérifie
  facilement : à 2 m, une personne de 1,70 m doit afficher « ≈ 2,0 m ».

## Où c'est testé

- `core.distance.LensSpecTest` (6) : focale paysage/portrait, capteur natif
  portrait, bornes, déduction du champ vertical.
- `core.distance.KnownSizesTest` (5) : **tous** les concepts par défaut du direct
  sont calibrés, synonymes FR/EN, accents, concept inconnu.
- `core.distance.DistanceMeterTest` (10) : formule (200 px de personne à 386 px de
  focale ⇒ 3,29 m), doublement de distance quand la boîte est deux fois plus
  petite, bornes, objet non calibré, lissage doux vs bond, oubli après silence.
- `core.distance.DistanceTextTest` (6) : « 85 cm », « 3,3 m », « 12 m », plus
  proche, une distance par étiquette.
- `core.live.LiveTimelineTest` (+1) : `record` rend l'identité **dans l'ordre des
  détections** (`null` pour une détection inexploitable).
- `app.DistanceLayoutTest` (9) : transformation `Crop` identique à l'aperçu,
  étiquette **centrée sur la boîte** et ramenée dans le cadre, boîte illisible,
  saisie du champ (± bornes), pastilles du bas.
- Recette sur téléphone réel (`2c6852ac`, 1220×2712, 25/10) : ligne d'état
  « Direct · 1,5 ips · 635 ms · 4 objets · plus proche 2,0 m · NVIDIA A100 »,
  pastilles « ≈ 2,0 m / ≈ 2,1 m / ≈ 2,3 m » superposées aux masques, section
  Réglages « Distances » rendue.

## Pistes (non faites)

- **Plan de sol** : distance pour n'importe quel objet qui touche le sol, à partir
  de la hauteur et de l'inclinaison de la caméra — plus juste pour « l'obstacle
  devant moi », mais il faut lire le pitch du capteur.
- **Profondeur métrique réelle** (Depth Anything sur la GPU Modal) : plus juste,
  mais réveil du GPU payant et latence par frame — décision explicite du user.
- **Étalonnage guidé** : « tiens-toi à 2 m » → ajustement du champ de vision.