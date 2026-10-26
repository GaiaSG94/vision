# Guidage vocal post-SAM — chiffres figés (2026-10-26)

La voix d'iSee ne décrit plus la scène : elle **guide** quelqu'un qui veut aller là où
elle filme. Aucun LLM dans cette boucle — uniquement les boîtes et les distances que le
téléphone calcule déjà (`docs/DISTANCES.md`).

## La chaîne

```
frame 320 px → SAM /live/ws → boîtes + labels
                            → DistanceMeter (sténopé, hauteurs connues)
                            → GuidanceGeometry  (latéral + largeur, en mètres)
                            → CorridorPlanner   (libre / contourner / stop)
                            → GuidanceAnnouncer (quand parler)
                            → ElevenLabs → haut-parleur
```

| Étage | Fichier | Nature |
|---|---|---|
| Repère de marche (latéral, largeur) | `core/guidance/GuidedObject.kt` | pur, JVM |
| Décision (couloir, côté, arrêt) | `core/guidance/Guidance.kt`, `CorridorPlanner.kt` | pur, JVM |
| Mots (fr/en) + signature | `core/guidance/GuidanceText.kt` | pur, JVM |
| Déclenchement (stabilité, délai) | `core/guidance/GuidanceAnnouncer.kt` | pur, JVM |
| Câblage frame → phrase | `app/ihear/LiveGuidance.kt` | sans Android |
| Voix | `app/ihear/ProximityVoice.kt` | ElevenLabs |

## Géométrie (ce qui est mesuré, pas supposé)

`f` = focale en pixels de l'image analysée (`LensSpec.focalPx(width, height)`).

- **latéral** : `x = d · (cx − W/2) / f`, signé, positif à droite de l'axe optique.
- **largeur transverse** : `L = d · largeur_boîte_px / f`, bornée à 5 cm – 8 m.
- **bords occupés** : `[x − L/2, x + L/2]`.

La longueur (profondeur) d'un objet n'est **pas** mesurable sur une vue : elle n'est pas
utilisée. Seule compte la face la plus proche, que le sténopé donne déjà.

## Chiffres figés

| Constante | Valeur | Pourquoi |
|---|---|---|
| `Corridor.HALF_WIDTH_METERS` | **0,45 m** | demi-largeur d'un buste + marge d'épaule |
| `Corridor.MARGIN_METERS` | **0,20 m** | on ne frôle pas : on passe à 20 cm du bord |
| `Corridor.WARN_METERS` | **3,0 m** | à 1,2 m/s et ~0,5 s de latence, deux pas pour agir |
| `Corridor.STOP_METERS` | **1,2 m** | sous cette distance, contourner n'est plus un pas |
| `Corridor.MAX_SHIFT_METERS` | **1,20 m** | au-delà, on annonce un contournement, pas un décalage |
| `GuidanceAnnouncer.DEFAULT_STABLE_FRAMES` | **2** | une frame isolée ne parle pas |
| `GuidanceAnnouncer.DEFAULT_MIN_INTERVAL_MS` | **1 500 ms** | temps de dire une instruction courte |
| `GuidanceAnnouncer.DEFAULT_HALT_INTERVAL_MS` | **2 000 ms** | un « stop » se répète tant que l'obstacle est là |
| Arrêt : frames de stabilité | **1** | le seul cas où attendre coûte un pas |
| Arrondi de la signature | 0,5 m / 0,25 m | un objet qui s'approche est ré-annoncé sans bavarder |

## Décision

1. Aucun objet mesuré → **silence** (`Unknown`) : on ne dit jamais « c'est libre » sans mesure.
2. Le plus proche objet **dans le couloir** et à ≤ 3 m :
   - ≤ 1,2 m → `Halt` (« Stop. Chaise à 1 mètre. ») ;
   - sinon → `Pass` du côté qui demande **le moins de pas**, avec le décalage chiffré.
3. Des objets mesurés, aucun dans le couloir → `Clear` (« Tout droit, c'est libre. »).

Un objet n'est « dans le couloir » que si son intervalle latéral recouvre
`[−0,45 − 0,20 ; +0,45 + 0,20]` m.

**Exception : les passages.** Une porte (ou une fenêtre, une sortie) est retirée de la
scène avant la décision (`OpeningConcepts`) : elle occupe l'image sans fermer le chemin,
et « décale à gauche » devant la porte qu'on veut passer serait un contresens. Elle garde
son masque et sa pastille.

## Ce que la voix dit

| Cas | Français |
|---|---|
| libre | `Tout droit, c'est libre.` |
| contourner | `Chaise devant, à 2,1 mètres. Décale à gauche de 50 centimètres.` |
| côté déjà libre | `Personne devant, à 1,4 mètre. Passe à droite.` |
| arrêt | `Stop. Chaise à 1 mètre.` |

L'anglais suit les Réglages, mêmes seuils et mêmes chiffres.

## Ce qui n'est pas atteint (assumé)

- **Objets non calibrés** : la distance vient de `KnownSizes` ; un concept absent de la
  table est ignoré par le guidage (pas de distance inventée).
- **Pas de lissage par piste** dans iSee : la stabilité de 2 frames et l'arrondi à 0,5 m
  absorbent le bruit, sans plus.
- **2 concepts par défaut** (`LivePrompts.Default`) : le guidage ne voit que ce que SAM
  cherche. Élargir la liste est un choix produit/backend, pas une limite technique.
- **Latence** : une instruction = un aller-retour ElevenLabs (~1 s) ; c'est pourquoi on
  prévient à 3 m. Un « stop » instantané demanderait des clips pré-synthétisés.
- **Pas d'IMU** : pas de plan de sol, pas de vérification que la personne a suivi
  l'instruction (boucle ouverte). Sous 2 m, le sol sort souvent du cadre.

## Tests

`CorridorPlannerTest` (8), `GuidanceAnnouncerTest` (8), `GuidanceGeometryTest` (6),
`GuidanceTextTest` (8), `OpeningConceptsTest` (2) — `core/src/test/.../guidance/` — plus
`PhotoOrientationTest` (7, `app/src/test`) pour le redressement de la photo et la rotation
des pastilles.
