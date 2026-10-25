# Distances en temps réel sur le Direct — créé 2026-10-25, revérifié code le 2026-10-25

Chaque objet suivi du Direct porte sa distance à la caméra : une pastille « ≈ 3,2 m » posée au-dessus de sa
boîte, dans la couleur de son masque, plus la distance de l'objet le plus proche dans la ligne d'état et une
pastille par concept en bas d'écran (« personne 2,1 m »).

**Aucun GPU, aucune requête de plus** : tout est calculé sur le téléphone, à partir de ce que `/live/ws`
ramène déjà (label + boîte + masque). Une frame sans objet calibré n'affiche rien du tout — pas de pastille
« ? », pas de chiffre inventé (`DistanceMeter.measure` renvoie une liste vide, `core/…/distance/DistanceEstimator.kt`).
Transport du direct : **WebSocket `/live/ws` uniquement depuis #137** (voir `docs/TESTING.md`) — sans effet sur
le calcul de distance, qui ne voit que la frame décodée.

## La mesure : sténopé sur hauteur réelle connue

```
distance (m) = hauteur réelle de l'objet (m) × focale (px) / hauteur de la boîte (px)   (Pinhole.distanceMeters)
focale (px)  = (largeur de l'image / 2) / tan(champ de vision porté par la largeur / 2) (LensSpec.focalPx, :31-36)
```

Deux entrées, deux sources :

| Entrée | D'où elle vient |
|---|---|
| **Hauteur réelle** | `KnownSizes.TABLE` (`KnownSizes.kt:40+`) : table label → mètres (personne 1,70 · voiture 1,45 · camion 2,80 · porte 2,05 · fenêtre 1,30 · bouteille 0,25 · verre 0,12…). Casse, accents, pluriel et expressions (« sac à dos ») sont neutralisés. Concept inconnu ⇒ **pas de distance**. |
| **Focale en pixels** | `LensSpec`, selon trois sources **dans cet ordre de priorité** : le **champ saisi dans Réglages** s'il est positif (`cameraFovDeg > 0`), sinon la **focale mm + taille physique lues par l'interop Camera2 à la liaison** (`lensSpecOf`, `app/…/CameraLens.kt:22-27`), sinon 67° (`LensSpec.DEFAULT_H_FOV_DEG`, grand-angle typique). Ordre vérifié : direct `LiveViewModel.chosenLens` (`:325-330`), photo iSee `IhearPhotoDistances.chosenLens` (`:53-56`). **Attention : la voie REC d'iSee ne passe pas par le capteur** — `LiveProbe.configureFrom` applique `LensSpec.fromSettings` (`ProbeWiring.kt:19-27`), qui borne en plus la valeur saisie à 15–130° (`LensSpec.kt:43-46,71-72`) alors que le direct accepte tout champ positif. Écart connu, non corrigé. |

L'axe vertical du capteur sert quand l'image est en portrait (`sensorLandscape` + `widthFovDeg`, `LensSpec.kt:23-29`)
— le direct redresse chaque frame avant envoi (`LiveScreen.kt:286`). La mesure est indépendante de la
résolution envoyée : `focalPx` se recalcule pour chaque frame depuis les dimensions **revenant du serveur**
(512 px côté direct `LIVE_EDGE`, `LiveScreen.kt:47 ; 448 px côté iSee `IHEAR_FRAME_EDGE`, `IhearCameraPane.kt:37` ;
`work_edge` déclaré : 640, `LiveSession.kt:149`).

**Bornes de plausibilité** (`Pinhole`, `DistanceEstimator.kt`) : rien sous **0,3 m**, rien au-delà de **60 m**,
rien pour une boîte de moins de **4 px** de haut. Hors bornes ⇒ pas d'affichage.

**Objet coupé par le bord haut ou bas ⇒ pas de distance** (`Pinhole.isVerticallyCut`, marge de **2 px**,
`DistanceEstimator.kt`). Vérifié sur téléphone le 25/10 : une couette segmentée à tort en « personne », collée
aux deux bords, sortait à **31 m** — la boîte ne mesurait plus la hauteur réelle. Les bords gauche/droite
restent acceptés : seule la hauteur porte la mesure.

## Lissage : lisible à 1-2 images par seconde

`DistanceSmoother` : moyenne mobile exponentielle **par piste** (l'identité stable du journal du direct —
`LiveTimeline.record` renvoie l'id de chaque détection ; un objet non identifié est affiché **brut**, sans
lisser, `DistanceMeter.readOne`). Deux vitesses — **0,35** pour accompagner un objet qui s'approche, **0,85**
quand la mesure fait un bond de plus de **50 %** (l'objet a réellement bougé, ou la piste a été reprise). Une
piste muette plus de **2 s** est oubliée : l'objet qui revient est mesuré à neuf. Valeurs aux défauts du
constructeur (`DistanceSmoother.kt:17-22`), testées dans `DistanceMeterTest`.

Sur la page caméra iSee (REC) et sur la photo du double tap : **pas de journal de pistes, mesure brute**
(`LiveProbe.readingsOf` n'appelle jamais `record` ; `IhearPhotoDistances.layerOf`) — la pastille y bouge donc
un peu plus qu'en Direct.

## Précision — ce que le chiffre vaut

Le doc annonçait le 25/10 « ±15-20 % sur un objet **debout et entier** » : **jamais confronté à un mètre ruban,
précision non mesurée** — à tenir pour une estimation d'ingénierie, pas une mesure. Trois causes d'erreur connues :

- **hauteur réelle supposée** : un enfant mesuré comme « personne » (1,70 m) sort deux fois trop loin ;
- **objet coupé** par un bord haut/bas : écarté depuis le 25/10 (il n'affiche rien) ;
- **objet penché ou allongé** (chaise vue de dessus, table basse) : la boîte ne mesure plus la hauteur réelle.

L'étiquette porte le « ≈ » pour cette raison (`DistanceText.approximate`), et le format dégressif — cm sous
1 m, décimètre jusqu'à 10 m, mètre au-delà (`DistanceText.format`) — ne promet pas plus de précision que ça.

## Cadrage des pastilles

Une pastille est centrée sur le **milieu horizontal** de sa boîte (pas alignée à gauche : constat du 25/10,
plusieurs pastilles empilées sur le bord gauche), puis **ramenée dans le cadre** (`pillTranslationX`,
`DistanceLayout.kt:60`) et **hors du bandeau du haut** (`pillTranslationY`, `:76` ; inset **230 dp** —
`TOP_SAFE_INSET`, `DistanceOverlay.kt:79` : boutons « Arrêter / Réglages », ligne d'état, concepts). Sans ça,
un objet collé au bord droit sortait à moitié de l'écran et un objet dans le haut de l'image voyait sa
distance cachée derrière les boutons (constaté le 25/10, corrigé).

**Deux pastilles au même endroit se cachaient** → `spreadLabels` (`DistanceLayout.kt:116`, entré sur master
avec #135) : toute pastille tombant dans l'emprise d'une pastille déjà posée glisse vers le bas par pas de
`PILL_MIN_Y_GAP = 76` px écran (seuil horizontal `PILL_MIN_X_GAP = 150`, `:105,108`). L'ordre des objets et
leur couleur sont conservés. Testé dans `DistanceLayoutTest`.

## iSee : les pastilles sur la photo du double tap

L'accueil iSee (chat + fenêtre caméra) ne suit aucun flux `/live` pendant la navigation : ni masques, ni
pistes, donc aucun objet à étiqueter en continu. Le **double tap** (`IhearViewModel.onDoubleTap`, `:221`)
prend une photo, l'affiche dans la fenêtre flottante et l'envoie au `/segment` de SAM (`HttpSamClient.segment`,
`IhearPhotoDistances.kt:46` ; le segment est **resté en HTTP** — seul le direct est en WS) ; chaque objet dont
le concept a une hauteur connue reçoit sa pastille **avec son nom** (`personne ≈ 3,9 m` — `withLabel = true`,
`IhearCameraWindow.kt:179`) — sans masque sur une photo, c'est le seul moyen de savoir de quel objet parle la
pastille. Une photo ne bouge pas : pas de lissage, une mesure brute.

La photo reste 2,6 s (`PHOTO_SHOW_MS`, `IhearViewModel.kt:487`) ; dès que des pastilles arrivent, elle est
**maintenue 6 s** (`PHOTO_MEASURED_MS`, `:490`) le temps de lire. Échec de mesure expliqué au chat, pas muet :
`PhotoMeasure` distingue bascule coupée, endpoint absent, image illisible, SAM injoignable, rien de mesurable
(`IhearPhotoDistances.kt:62-68`). Rien de mesurable : la photo s'évapore comme avant, sans pastille.

## À voix haute

La voix (ElevenLabs, bascule « Annoncer les objets et leur distance » — coupée **ou** clé absente ⇒ silence
sans erreur visible, cf. piège (b) de `docs/TESTING.md`) dit la scène **avec les distances** :

```
Personne à gauche, à 1,4 mètre ; chien au centre, à 3 mètres.
```

Exact, dans le code : au-delà de 3 objets cités (`PositionText.MAX_OBJECTS`), le reste est résumé (« , et 2
autres objets. ») ; le séparateur devient le point-virgule dès qu'une distance est présente (`PositionText.phrase`,
`core/…/live/PositionText.kt:79-95`).

Pas de duplication : c'est l'annonce de positions existante (`PositionAnnouncer` : stabilité **2 frames**,
pas de répétition, **4 s** entre deux phrases — `PositionAnnouncer.kt:81,84`) qui porte désormais la distance
dans `PositionText.item`, et **la distance arrondie au mètre entre dans la signature de scène**
(`PositionText.key` + `bucket`, `:104-117`) — c'est ce qui fait reparler un objet qui s'approche (3 m → 2 m →
1 m) sans bavarder à chaque frame. `DistanceText.spoken` met les mètres en mots français (« 85 centimètres »,
« 1,4 mètre », « 12 mètres ») ; le singulier va jusqu'à 2 sur la valeur **arrondie** (« 1,5 mètre »).
La phrase effectivement dite reste visible dans le panneau iSee sous `dit : …` (`LiveProbeText.kt:43`).

## Réglages

- **Distances sur le direct** (bascule, activée par défaut, `Settings.distanceOverlayEnabled:34`) : coupe
  l'overlay **et** le calcul (`DistanceMeter.enabled`).
- **Champ de vision caméra (°)** : `0` = lecture du capteur (voie normale). À saisir si le driver est muet ou
  si l'étalonnage dérive — la valeur se vérifie facilement : à 2 m, une personne de 1,70 m doit afficher
  « ≈ 2,0 m » (recette de contrôle ; tolérance chiffrée non mesurée).

## Où c'est testé (comptages `@Test` du 2026-10-25, ce worktree)

- `core.distance.LensSpecTest` (**7**) : focale paysage/portrait, capteur natif portrait, bornes, déduction du champ vertical.
- `core.distance.KnownSizesTest` (**5**) : **tous** les concepts par défaut du direct sont calibrés (les huit
  de `LivePrompts.Default`), synonymes FR/EN, accents, concept inconnu.
- `core.distance.DistanceMeterTest` (**12**) : formule (personne 200 px, focale 386 px ⇒ **3,29 m**), doublement
  de distance quand la boîte est deux fois plus petite, bornes, objet non calibré, coupé haut/bas, colle aux
  deux bords, lissage doux vs bond, oubli après silence, mesure réelle dans les bornes.
- `core.distance.DistanceTextTest` (**7**) : « 85 cm », « 3,3 m », « 12 m », « ≈ », plus proche, par étiquette, mots parlés.
- `core.live.LiveTimelineTest` (**10**, dont le test « record rend l'identité **dans l'ordre des détections** »,
  `null` pour une détection inexploitable).
- `core.live.PositionTextTest` (**10**) + `PositionAnnouncerTest` (**8**) : secteurs, ordre de lecture, distance
  dans la phrase et dans la signature, stabilité/non-répétition/4 s.
- `app.DistanceLayoutTest` (**13**) : transformation `Crop` identique à l'aperçu, étiquette centrée sur la boîte
  et ramenée dans le cadre, bandeau du haut, `spreadLabels` (pastilles qui se chevauchent glissent vers le bas,
  et deux pastilles éloignées ne bougent pas), saisie du champ (± bornes), pastilles du bas, préfixe du concept.
- `app.ihear.IhearDistancesTest` (**5**) : pastilles d'une photo segmentée — personne haute mesurée (≈ 3,9 m),
  une pastille par objet, concept inconnu ignoré, photo vide ou de taille nulle, boîte coupée par le bord bas
  non mesurée.
- `app.ihear.LiveProbeTextTest` : ligne d'état REC avec `plus proche ≈ 1,1 m` et détail `distances :` trié.
- Recette sur téléphone réel (`2c6852ac`, 1220×2712, **25/10**) : ligne d'état
  « Direct · 1,5 ips · 635 ms · 4 objets · plus proche 2,0 m · NVIDIA A100 », pastilles
  « ≈ 2,0 m / ≈ 2,1 m / ≈ 2,3 m » superposées aux masques, section Réglages « Distances » rendue.
  Mesure datée, format relevé à l'époque ; la ligne du direct s'écrit aujourd'hui via `LiveState.line`.

## Pistes (non faites)

- **Plan de sol** : distance pour n'importe quel objet qui touche le sol, à partir de la hauteur et de
  l'inclinaison de la caméra — plus juste pour « l'obstacle devant moi », mais il faut lire le pitch du capteur.
- **Profondeur métrique réelle** (Depth Anything sur la GPU Modal) : plus juste, mais réveil du GPU payant et
  latence par frame — décision explicite du user.
- **Étalonnage guidé** : « tiens-toi à 2 m » → ajustement du champ de vision.
- **Précision chiffrée** : une fois pour toutes, comparer 10 objets à un mètre ruban et remplacer « ±15-20 %
  non mesuré » par un chiffre daté.
