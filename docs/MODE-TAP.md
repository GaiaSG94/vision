# Mode tap court — vidéo → SAM 3.1, annonces ElevenLabs

Conçu pour une utilisation sans regard. **Un tap sur l'aperçu de Capture ouvre le mode
vidéo et démarre l'enregistrement** (le bouton « Vidéo (tap) » reste là, sans démarrage
automatique : on a le temps de se placer). Sur l'écran vidéo, **un second tap** arrête et
envoie. **L'appui long parle** — y compris pendant l'enregistrement vidéo.

## Interaction

| Événement | Verbe prononcé (anglais, mot unique) | Écran |
|---|---|---|
| Tap sur l'aperçu de Capture | `Launched` | « Enregistrement » (démarré d'office) |
| Tap court relâché, caméra ouverte | `Launched` | « Enregistrement » |
| Second tap relâché, envoi réussi | `Sent` | « Envoyé » + résumé texte |
| Permission refusée / caméra KO / envoi KO | `Failed` | « Échec » + motif |

- Le geste vient de `android.view.GestureDetector` **avec ses seuils par défaut**
  (`ViewConfiguration`) : `onSingleTapConfirmed` = tap court, `onLongPress` = réservé à la
  logique appui long (paramètre `longPressAction`, aucun conflit si fusion dans une seule app),
  `onScroll`/fling = glissement ignoré.
- **Barre système jamais déclenchante** : le conteneur est posé sous `WindowInsets.systemBars`
  et `TapAreaView` rejette en plus tout point tombant dans les bandes (haut/bas) ou les bords
  (`TapZones.kt`, testé en JVM). Les boutons retour/accueil/récents restent au système.
- TalkBack : `contentDescription` sur la surface + `performClick()` comme action — un
  double-tap TalkBack fait exactement ce que fait un tap du doigt, une seule fois.
- **Tap contre appui long, sans double déclenchement** : la couche de tap de l'accueil est
  classée **au lever** par `core/gesture/TapOrHold` avec les seuils de l'appui long de l'app
  (300 ms, ~8 dp) ; un appui tenu est un appui long, il ne lance donc jamais la vidéo. Les
  commandes posées au-dessus (Photographier, Galerie, Réglages) gardent leurs taps.

## Parler pendant la vidéo

Android n'autorise **qu'un seul `AudioRecord` par application**. Une vidéo qui prend le micro
interdit donc la dictée pendant l'enregistrement : `MicBus` refuse le second demandeur, et
c'est une panne silencieuse pour l'utilisateur (l'un des deux part muet).

Le mode vidéo renonce donc à sa piste audio (`TapConfig.VIDEO_WITH_AUDIO = false`) : la
demande faite à SAM 3.1 est **visuelle** (`UPLOAD_PROMPT` est fixe), le son de la vidéo
n'apporte rien, et le micro reste disponible pour la voix. Un appui long pendant
l'enregistrement ouvre la dictée, la vidéo continue, le relâchement rend la parole.

Repasser `VIDEO_WITH_AUDIO` à `true` rend la vidéo sonore mais rétablit l'arbitrage
strict : la dictée est alors refusée (« micro occupé par la vidéo »).

## Voix

`AnnouncementPlayer` : **Three words only**. `Announcement` est un enum fermé
(`LAUNCHED`/`SENT`/`FAILED`) et `announcementAllowed()` refuse tout autre texte — le TTS
system (SpeechRecognizer/TTS moteur) n'est jamais utilisé ici, et le lecteur du chat
(`speak()`) est coupé à l'entrée de l'écran pour ne rien couvrir.

Latence : `prewarm()` génère les trois clips à l'ouverture de l'écran dans
`filesDir/announcements/{launched,sent,failed}.mp3` (vérifié sur émulateur : 17,5 / 16,3 /
15,1 ko). Ensuite la lecture est **100 % locale**, sans réseau. Si un clip manque et que le
réseau dépasse le budget (`TapConfig.NETWORK_LATENCY_BUDGET_MS = 500 ms`), le mot est tu pour
ce coup (jamais de voix qui arrive en retard sur la piste audio) et la génération continue en
arrière-plan pour le tap suivant. Lecture via `SpeechPlayer.playFile` : pas de demande de focus
audio, donc l'enregistrement en cours n'est ni coupé ni ducker.

## Rien n'est perdu : durée minimale et file d'attente

`TapConfig.MIN_RECORDING_MS = 350 ms` : en dessous, CameraX finalise la prise en **erreur 8 avec
un fichier de 0 octet** (mesuré : échec à 62 ms, succès à 332/521/718/1022/1523 ms, 412 à 452 ko).
Un tap trop tôt n'est donc **pas ignoré** : il est mémorisé (`stopRequested`) et le battement
(250 ms) l'exécute dès que la durée exploitable est atteinte — imperceptible, et deux taps à
200 ms d'écart partent bien. Un tap arrivé pendant la liaison caméra est traité de la même façon.

Avant l'envoi, `TapVideoSession.stopAndAwait` attend l'événement `VideoRecordEvent.Finalize`
(4 s max) : sans cette attente, la taille du fichier était lue trop tôt et la vidéo était
déclarée « aucune vidéo à envoyer » alors qu'elle venait de s'écrire.

## Caméra et fichier

CameraX `VideoCapture` + `Recorder` (couche la maigre, sélection simple caméra arrière,
`Quality.HD`), **sans piste audio** par défaut (voir « Parler pendant la vidéo »). La vidéo
est écrite dans
`context.getExternalFilesDir(DIRECTORY_MOVIES)` = `Android/data/dev.sam3vision.app/files/Movies/`
— le périmètre exact purgé par le module de nettoyage.

## Cohabitation avec la dictée par appui long (autre feature de l'app)

Les deux gestes vivent sur le même écran, sans se marcher dessus :

- **tap court** (relâchement < seuil) → mode tap : `TapAreaView` déclenche l'enregistrement ;
- **appui long maintenu** (≥ `ViewConfiguration.getLongPressTimeout()`) → dictée : c'est la
  racine `LongPressRootLayout` posée par `MainActivity` qui intercepte et envoie `ACTION_CANCEL`
  à la surface de tap — vérifié sur émulateur, les deux gestes fonctionnent l'un après l'autre.

**Le micro est exclusif.** `MicBus` (`app/MicBus.kt`) est le seul arbitre : la dictée tient le
micro pendant la tenue, la vidéo le tient pendant la prise. Le second demandeur est refusé — le
mode tap annonce `Failed` (« micro occupé par la dictée »), la dictée affiche le bandeau correspondant.
Sinon la panne serait silencieuse : dictée muette, ou vidéo sans piste audio. La prise étant
écrite à l'arrêt, `MicBus` est rendu **avant** l'envoi : la dictée peut reprendre pendant que la
vidéo monte vers SAM 3.1.




| Clé | Provient de | Défaut |
|---|---|---|
| `SAM31_ENDPOINT` | `local.properties` → `sam31.endpoint` (BuildConfig) | vide → « Failed » |
| `ELEVENLABS_API_KEY` | Réglages (store chiffré) sinon `local.properties` → `tts.api.key` | vide → muet |
| `LAUNCHED` / `SENT` / `FAILED` | `enum Announcement` | mots anglais |
| `MAX_RECORDING_MS` | 60 s, arrêt + envoi automatiques | |
| `MIN_RECORDING_MS` | 250 ms, effleurement ignoré | |
| `ON_DEVICE_INFERENCE` | `false` = upload cloud multipart | |

Le build debug autorise le HTTP (`app/src/debug/AndroidManifest.xml`) : un endpoint local
(`http://10.0.2.2:8099` sur émulateur) est utilisable sans recompiler. La release garde HTTPS.

## Cloud ou on-device ? (question ouverte du prompt)

Le contrat retenu est l'**upload** : `POST {SAM31_ENDPOINT}/video` en `multipart/form-data`
(champs `video`, `model=sam3.1-video`, `prompt`). Si SAM 3.1 doit tourner **sur le téléphone**,
on ne change qu'un endroit : `TapConfig.ON_DEVICE_INFERENCE = true` — là, `HttpSam31VideoClient`
refuse sans appel réseau et l'écran annonce `Failed`. Il faudra alors brancher le runtime local
(TFLite/ONNX + modèle vidéo) à la place du client ; le geste, la file d'annonces et le
néttoyage restent identiques.
