# Module de nettoyage — purge horaire des captures de l'app

Purge **silencieuse** (aucune boîte de dialogue, aucun son, aucune vibration) de toutes les
images et vidéos créées par l'app, **toutes les heures**, et rien d'autre.

## Périmètre : le stockage privé de l'app, uniquement

`CapturePaths` (`Android/data/<package>/…`) est le seul endroit qui décide où l'app écrit :

- `getExternalFilesDir(DIRECTORY_PICTURES)` — photos et images importées de la galerie ;
- `getExternalFilesDir(DIRECTORY_MOVIES)` — vidéos du mode tap ;
- `filesDir/captures` — repli si le stockage externe privé est absent.

Chaque dossier porte un `.nomedia`. Le stockage partagé, les répertoires d'autres apps et les
fichiers média d'autres applications ne sont **jamais** ni lus ni supprimés : le job ne parcourt
que `CapturePaths.privateRoots(context)`, récursivement, **sans suivre les liens symboliques**
(un lien vers `/sdcard/…` ne devient pas une porte de sortie).

Preuves instrumentées (`CaptureCleanupTest`, 6/6 vertes) : capture de l'app supprimée, fichier
déposé hors périmètre intact, dé-sync MediaStore gérée silencieusement, capture en cours
d'écriture reportée, périmètre privé seulement, job unique sans réseau.

## Traces MediaStore : liste blanche, jamais une plage de dates

Par défaut `CleanupConfig.ADD_TO_GALLERY = false` : les captures restent privées, invisibles dans
la galerie. Si l'app publie dans MediaStore (`MediaStoreGallery.publish`, Android 10+), l'`_ID`
rendu est écrit **dans la table Room à la création** (`traced_captures`) :

| Colonne | Rôle |
|---|---|
| `mediaStoreId` | `_ID` renvoyé par l'insertion |
| `uri`, `relativePath` | retrouver l'entrée même si l'`_ID` est recyclé |
| `kind` | collection à interroger (`Images` ou `Video`) |
| `createdAtMs` | horodatage de la capture |

À la purge (`RoomTracedCaptureStore.delete`), la sélection est **toujours**
`_ID = ? [AND OWNER_PACKAGE_NAME = ?]` : jamais de `MediaStore.Files` filtré par
`date < X`, qui emporterait les photos de l'appareil photo ou de WhatsApp.

Idempotence : l'entrée a disparu (suppression manuelle, MediaStore dé-synchronisé) → la trace
est oubliée sans erreur ; le fichier déjà absent est compté comme supprimé.

## Planification

`CleanupScheduler.schedule` appelé dans `SamApp.onCreate` (classe `Application`, déclarée dans
le manifeste) :

- `PeriodicWorkRequest` d'intervalle `CleanupConfig.PERIOD_HOURS = 1` heure ;
- `enqueueUniquePeriodicWork(KEEP)` → jamais de doublon après un redémarrage ;
- `Constraints` vides, `NetworkType.NOT_REQUIRED` → tourne hors connexion ;
- `CaptureCleanupWorker` renvoie `Result.success()` dans **tous** les cas : un échec est journalisé,
  repris à l'heure suivante, pas de file de retries ni de crash.

Capture en cours : un fichier modifié il y a moins de `IN_PROGRESS_GRACE_SECONDS = 60` s est
reporté à la passe suivante.

Journal : une ligne par passe dans `filesDir/cleanup.log` (200 lignes max) + logcat
(`TAG = CaptureCleanup`), compte tenu/supprimés/reportés/échoués.

## Changer le comportement — un seul fichier

`CleanupConfig.kt` :

```kotlin
PERIOD_HOURS = 1              // périodicité : un seul endroit
DELETE_IMAGES = true          // couper les images sans couper les vidéos
DELETE_VIDEOS = true
MAX_AGE_MINUTES = 0L          // 0 = tout supprimer à chaque passe
IN_PROGRESS_GRACE_SECONDS = 60L
ADD_TO_GALLERY = false        // true = publie + trace dans Room, et purge via _ID
```

Les deux lectures possibles de « toutes les 1 h » sont déjà câblées :
- **par défaut** : chaque heure, on purge tout ce que l'app a capté (rétention ≈ 1 h) ;
- **« supprimer ce qui a plus d'une heure »** : `MAX_AGE_MINUTES = 60` — le mécanisme
  (`CaptureScanner.minAgeMs`) est prévu, rien d'autre à changer.

## Garde-fous

Aucun `chmod`/`bash`/`rm` système, aucun `MediaStore.Files` global, aucun dialogue, aucun son :
le module ne parle qu'à `java.io.File` dans le périmètre privé et qu'aux `_ID` tracés.
