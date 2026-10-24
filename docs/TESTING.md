# TESTING — sam-vision-kotlin

## Tests JVM (79 core + 4 app, réseau simulé)

```bash
export JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2 ANDROID_HOME=$HOME/Android/Sdk
./gradlew :core:testDebugUnitTest --offline    # ~2 s avec cache ; BUILD SUCCESSFUL attendu
```

Couverture (`core/src/test/...`, serveur maison `TestHttpServer`, jamais d'appel réel) :
`mask.MaskCoreTest` 6 (base64 data-prefix, IHDR, luminance, colorisation, masque 255) ;
`net.LlmClientTest` 9 (JSON/texte libre/JSON noyé, Bearer+modèle, historique ≤ 20 tours,
401→clé invalide, 429→limite, clé absente→échec sans réseau, BadResponse) ;
`net.SamClientTest` 6 (parse `/segment`, User-Agent sans clé, 400 typé, `/health` ok/ko, timeout) ;
`text.TextSanitizerTest` 4 ; `usecase.UseCasesTest` 6 (fusion objets+labels, sans SAM, échec SAM
toléré, erreur LLM propagée). Détail : `core/build/test-results/testDebugUnitTest/*.xml`.

Dictée Meta (`docs/META-ASR.md`) : `audio.WavTest` 3 (champs RIFF, assemblage en-tête+PCM,
durée) ; `net.MetaAsrClientTest` 8 (multipart `request`+`audio` sans `Content-Length` par part,
champs imbriqués et `segments`, texte vide = succès, 402→PaymentRequired, 401→InvalidKey,
clé absente et audio vide = zéro requête, réponse sans texte→BadResponse) ;
`usecase.DictationUseCaseTest` 4 (trop court refusé avant réseau, WAV complet envoyé, texte
vide→« aucune parole », erreur client propagée) ; `app.UiLogicTest` ajoute la fusion
brouillon+dictée.

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

1. Capture : `input tap 540 2108` « Photographier » (Galerie 171 2276, Réglages 671 2276).
2. Analyse (~5–15 s, appel LLM réel) : bandeau « Segmentation désactivée — renseigne un
   endpoint SAM dans Réglages. », tags, description ; retour `input tap 160 95`.
3. Réglages : champs Base URL LLM (540 452), Clé (540 688), Modèle (540 924), URL SAM (540 1160) ;
   « Enregistrer » (540 1331), « Tester le LLM » (540 1499) ⇒ « LLM OK — … », « Tester SAM » (540 1667).
4. Dictée : champ « Dictée Meta — clé API » (540 1150), taper la clé (échapper le `|` :
   `input text "LLM\|…"`), « Enregistrer », « Tester la dictée Meta » (540 2061) ⇒
   « ASR KO — paiement requis (402) … » = trajectoire bonne, facturation Meta à régler.
   Dans le Chat : « Dicter » (540 1978) ⇒ bandeau « Enregistrement… », « Stop » ⇒
   « Dictée KO — paiement requis (402) … » (même raison).
5. Screenshot : `adb -s emulator-5554 shell screencap /sdcard/s.png && adb -s emulator-5554 pull
   /sdcard/s.png /tmp/samvision_manual.png`. Clé visible nulle part (champ masqué `•••`).

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
