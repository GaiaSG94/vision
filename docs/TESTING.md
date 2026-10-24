# TESTING — sam-vision-kotlin

## Tests JVM (147 = 118 `:core` + 29 `:app`, réseau simulé)

```bash
export JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2 ANDROID_HOME=$HOME/Android/Sdk
./gradlew :core:testDebugUnitTest --offline    # ~2 s avec cache ; BUILD SUCCESSFUL attendu
```

Couverture (`core/src/test/...`, serveur maison `TestHttpServer`, jamais d'appel réel) :
`net.VideoClientTest` 8 (multipart vidéo `model`/`video`/`prompt`, texte brut, 500 typé,
endpoint vide et mode on-device refusés **sans** appel réseau, fichier absent, `/health`,
résumé tronqué à 600) ;
`mask.MaskCoreTest` 6 (base64 data-prefix, IHDR, luminance, colorisation, masque 255) ;
`net.LlmClientTest` 9 (JSON/texte libre/JSON noyé, Bearer+modèle, historique ≤ 20 tours,
401→clé invalide, 429→limite, clé absente→échec sans réseau, BadResponse) ;
`net.SamClientTest` 6 (parse `/segment`, User-Agent sans clé, 400 typé, `/health` ok/ko, timeout) ;
`text.TextSanitizerTest` 4 ; `usecase.UseCasesTest` 6 (fusion objets+labels, sans SAM, échec SAM
toléré, erreur LLM propagée). Détail : `core/build/test-results/testDebugUnitTest/*.xml`.

Voix ElevenLabs (§ « Voix » plus bas) : `net.ElevenLabsTtsClientTest` 7 (audio 200 renvoyé
tel quel + requête `text-to-speech/{voice}` conforme et voice ID échappé, 402 sans crédits →
`PaymentRequired` avec message clair, 401 → `InvalidKey` et 429 → `RateLimited`, texte vide ou
clé absente = zéro appel réseau, voix absente refusée localement, 200 sans audio = erreur
lisible) ; `settings.SettingsTtsTest` 3 (le codec conserve clé + voix + autoplay, un ancien
fichier sans clés TTS reprend les défauts) ; `app.TtsLogicTest` 6 (`speakRefusal`, une seule
phrase active, libellés des boutons Écouter, `ttsAutoRead`, `withTtsDefaults`).

Dictée Meta (`docs/META-ASR.md`) : `audio.WavTest` 3 (champs RIFF, assemblage en-tête+PCM,
durée) ; `net.MetaAsrClientTest` 8 (multipart `request`+`audio` sans `Content-Length` par part,
champs imbriqués et `segments`, texte vide = succès, 402→PaymentRequired, 401→InvalidKey,
clé absente et audio vide = zéro requête, réponse sans texte→BadResponse) ;
`usecase.DictationUseCaseTest` 4 (trop court refusé avant réseau, WAV complet envoyé, texte
vide→« aucune parole », erreur client propagée) ; `settings.SettingsAsrTest` 2 (le codec du
fichier privé conserve base/clé/modèle ASR, un ancien fichier sans clés ASR reprend les
défauts et `sttConfigured` reste vrai grâce à la clé ElevenLabs) ; `app.UiLogicTest` 4 ajoute
la fusion brouillon+dictée. Repli des moteurs : `net.ElevenLabsSttClientTest` 5 (multipart
Scribe `model`/`file` conforme, texte + `segments`, 402 → `PaymentRequired`, clé absente = zéro
requête, audio trop court refusé) et `net.FallbackSpeechClientTest` 4 (primaire OK → repli
jamais appelé, primaire en échec → repli transcrit, les deux échouent → `AllProviders` avec les
deux messages, sans repli → erreur du primaire telle quelle). Appui long :
`gesture.LongPressTriggerTest` 10 (délai de 300 ms, un seul démarrage, relâchement avant le
seuil, glissement et second doigt annulent, tremblement toléré, clôture unique, nouvel appui
repart proprement).

## Tests JVM du module tap et du nettoyage (`:app`, 19)

`tap/TapMachineTest` 8 (transitions IDLE→START, RECORDING→STOP_AND_SEND, effleurement ignoré,
tap **trop tôt remisé puis exécuté dès la durée exploitable**, tap pendant l'envoi ignoré, arrêt
automatique à 60 s, liste blanche des trois mots, noms de clips, périodicité ≥ 15 min) ;
`tap/TapZonesTest` 4 (bandes système haute/basse, centre, bords) + `tap/TapReleaseTest` 2
(l'action part du relâchement court, jamais de l'appui long ni du glissement) ;
`cleanup/CaptureScannerTest` 5 (parcours récursif, extensions, écriture en cours, types
désactivés, rétention 60 min).

```bash
./gradlew :app:testDebugUnitTest :core:testDebugUnitTest    # 147 tests, 0 échec
```

## Module de nettoyage (instrumenté, 6 tests)

```bash
export JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2 ANDROID_HOME=$HOME/Android/Sdk
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5558 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5558 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5558 shell am instrument -w \
  -e class dev.sam3vision.app.cleanup.CaptureCleanupTest \
  dev.sam3vision.app.test/androidx.test.runner.AndroidJUnitRunner    # OK (6 tests), ~3 s
```

Preuves : capture de l'app supprimée · fichier hors périmètre intact · trace MediaStore
dé-synchronisée oubliée sans erreur · capture en cours d'écriture reportée · périmètre limité au
privé · job unique sans réseau.

## Recette manuelle du mode tap (émulateur)

```bash
adb -s emulator-5558 shell pm grant dev.sam3vision.app android.permission.CAMERA
adb -s emulator-5558 shell pm grant dev.sam3vision.app android.permission.RECORD_AUDIO
adb -s emulator-5558 shell am start -n dev.sam3vision.app/.MainActivity
adb -s emulator-5558 shell input tap 270 2045   # « Vidéo (tap) »
adb -s emulator-5558 shell input tap 540 1200   # tap court : « Launched » → Enregistrement
sleep 4
adb -s emulator-5558 shell input tap 540 1200   # second tap : « Sent » → Envoyé
```

Jouée le 2026-10-24 avec `sam31.endpoint=http://10.0.2.2:8099` (serveur local) :
vidéo MP4 de 590 ko → 1,09 Mo envoyée en multipart (champs `model`, `video`, `prompt`),
écran « Envoyé — Deux mains montent une etagere. ». Sans endpoint : « Échec — endpoint SAM 3.1
vide » + annonce `Failed`. Clips `launched/sent/failed.mp3` présents dans
`files/announcements/` dès l'ouverture de l'écran.


## Recette manuelle émulateur (1080×2400 @ 420 dpi — serial par défaut emulator-5554)

```bash
EMULATOR_SERIAL=emulator-5554 bash scripts/install-emulator.sh   # fait tout ci-dessous
# ou à la main :
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell pm grant dev.sam3vision.app android.permission.CAMERA
adb -s emulator-5554 shell pm grant dev.sam3vision.app android.permission.RECORD_AUDIO
adb -s emulator-5554 shell am start -n dev.sam3vision.app/.MainActivity
```

Parcours validé (coordonnées px centrées sur les cibles uiautomator) :

1. Capture (4 boutons depuis le mode tap) : `input tap 540 1877` « Photographier »,
   `input tap 171 2045` « Vidéo (tap) », `input tap 671 2045` « Galerie »,
   `input tap 540 2213` « Réglages ».
2. Analyse (~5–15 s, appel LLM réel) : bandeau « Segmentation désactivée — renseigne un
   endpoint SAM dans Réglages. », tags, description ; retour `input tap 160 95`.
3. Réglages : champs Base URL LLM (540 452), Clé (540 688), Modèle (540 924), URL SAM (540 1160) ;
   « Enregistrer » (540 1331), « Tester le LLM » (540 1499) ⇒ « LLM OK — … », « Tester SAM » (540 1667).
   Depuis le mode tap, la section « Vidéo (tap) » n'ajoute aucune clé : elle réutilise la clé
   ElevenLabs des Réglages (sinon `local.properties`).
4. Dictée : champ « Dictée Meta — clé API » (540 1150), taper la clé (échapper le `|` :
   `input text "LLM\|…"`) puis « Tester la dictée Meta » (540 2061) ⇒
   « ASR KO — paiement requis (402) … » = trajectoire bonne, facturation Meta à régler ; la
   dictée bascule alors sur le repli ElevenLabs Scribe (même clé que la voix).
   Dans le Chat : « Dicter » (540 1978) ⇒ ligne « Enregistrement… », « Stop » ⇒ le texte
   s'insère dans le champ de question (ou « Dictée KO — … » si aucun moteur ne répond).
   Appui long 300 ms n'importe où (Capture, Analyse, Chat, Réglages, tap) ⇒ bandeau
   « J'écoute » prononcé par ElevenLabs ; au relâchement ⇒ « Transcription » puis le texte en
   gros caractères ; un appui ferme le bandeau. Recette : `docs/TEST-LONGPRESS-DICTEE.md`.
5. Screenshot : `adb -s emulator-5554 shell screencap /sdcard/s.png && adb -s emulator-5554 pull
   /sdcard/s.png /tmp/samvision_manual.png`. Clé visible nulle part (champ masqué `•••`).

## Voix (ElevenLabs)

1. Réglages → section « Voix ElevenLabs » : la clé et l'ID de voix apparaissent (masqués).
2. « 🔊 Tester la voix » → avec une voix **premade** (défaut Sarah) et un compte free :
   la phrase est prononcée, statut « voix OK » (réseau requis, ~2 s). Avec une voix de
   bibliothèque (ex. Rachel `21m00Tcm4TlvDq8ikWAM`) : statut
   « voix ElevenLabs refusée (402) : voix de bibliothèque payante ou compte sans crédits… ».
   Aucun crash, aucun état « en cours » qui reste.
3. Clé vidée + « Enregistrer » → « 🔊 Tester la voix » affiche
   « clé ElevenLabs absente — renseigne-la dans Réglages » **sans appel réseau**.
4. Analyse d'une photo → « 🔊 Écouter » sous la description, et sous chaque réponse du chat.
5. **Lecture automatique (recette jouée le 24/10 sur emulator-5556)** : autoriser la caméra,
   « Photographier », attendre la description, « Poser une question », taper une question,
   « Envoyer ». La réponse arrive puis le bouton passe par « voix en cours… » →
   « lecture en cours… » (~18 s d'audio pour une réponse longue) → « voix OK ».
   Capture : `docs/chat-voix.png`. Coupure d'un ⏹ Stop en cours de lecture → retour à l'état
   inactif sans erreur.

## Non-régression jouée après le mode tap (2026-10-24, emulator-5558)

Chaîne photo d'origine revalidée de bout en bout après les changements (`SpeechPlayer`
factorisé, `newCacheFile` déplacé dans le stockage privé, `Application` déclarée, 4e bouton) :
« Photographier » → description réelle du LLM (`abstrait · pixelisé · …`) → « Poser une
question » → réponse du chat → **lecture automatique ElevenLabs** jusqu'à « voix OK ».
Aucun crash au démarrage à froid (logcat : 0 `FATAL EXCEPTION`), `cleanup.log` écrit,
photo et vidéo dans `files/Pictures` et `files/Movies` avec `.nomedia`, `assembleRelease` OK.

## Limites connues

- L'app reprend l'écran où elle était (ex. Chat) après `am start` sans `force-stop` préalable ;
  pour rejouer depuis Capture : `am force-stop dev.sam3vision.app` avant l'étape 1.
- Les coordonnées ci-dessus sont propres à 1080×2400/420 dpi et à l'état courant des écrans ;
  les re-mesurer via `uiautomator dump` si l'UI bouge. `KEYCODE_BACK` peut sortir de l'app
  (préférer le bouton « Retour » en haut à gauche).
- `tools/check-kotlin-limits.py` (heuristique accolades, faux positifs possibles sur default
  parameters à lambda) signale au 24/10 : `AnalysisScreen()` 64 lignes — non corrigé ici,
  fichier réservé à l'agent UI.
- `install -r` échoue si un build antérieur a changé de signature : désinstaller d'abord.
