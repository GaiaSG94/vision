# Recette — dicter : appui long iSee et bouton « Dicter »

Réécriture du **2026-10-25** sur le worktree `sam-wt-docs-app` (origin/master,
`8f9876a`). L'état du 2026-10-24 est **conservé plus bas** et marqué périmé là où le
code a bougé. Ce qui n'a pas été rejoué est écrit **« non mesuré »** — aucune mesure
nouvelle n'a été faite dans cette passe (aucun émulateur, aucun téléphone, aucune
commande `adb` n'ont été lancés ici).

## Ce qui est vrai dans le code aujourd'hui : deux chemins, un seul insère dans le champ

| Chemin | Geste | Ce qui se passe | Où va le texte |
|---|---|---|---|
| **iSee** (page d'accueil) | appui long **n'importe où** sur la surface ≥ **300 ms** | `IhearRootLayout` → `LongPressTrigger` → `IhearViewModel.onHoldStart/onHoldEnd` → `transcribeAndAnswer()` | devient une **question du chat** (iSee n'a pas de champ de saisie) |
| **Chat** (route `Route.Chat`, écran d'origine) | **tap** sur « Dicter » → le libellé passe à « Stop » → **tap** à nouveau | `AppViewModel.startDictation/stopDictation` (`hold = false`) → `DictationFeature` | **inséré dans le champ de question** (`LaunchedEffect(state.dictation.seq)` + `mergeDraft`) |

### ⚠️ Correction principale : pas d'appui long sur le bouton « Dicter »

Preuves, dans ce worktree :

- `ChatScreen.kt:168` — le bouton est un `TextButton(onClick = onClick)` ; **aucun**
  gestionnaire de clic long n'est attaché ;
- `git grep -n "onLongClick\|combinedClickable\|performLongClick" app/src core/src`
  → **aucun résultat** ; il n'existe donc ni handler, ni test instrumenté de ce geste ;
- la racine d'appui long, `IhearRootLayout`, ne pilote les gestes que quand iSee est
  à l'écran : `gesturesEnabled = { ihear.active }` (`MainActivity.kt:80`), et
  `active` n'est vrai que pendant que `IhearHome` est composé
  (`IhearHome.kt:23-24`). Sur la route Chat, la racine **n'intercepte rien**.

Conséquence pour la recette : `performLongClick()` (ou `performTouchInput { longClick() }`)
sur le nœud « Dicter » ne fait **rien** — ça ne dit pas « Stop » et n'ouvre pas le
micro. Un test écrit comme ça serait vert par vacuité. Le « devient Stop » du libellé
vient d'un **tap**, pas d'un appui long.

## Recette A — bouton « Dicter » (écran Chat), insertion dans le champ

1. Lancer l'app puis atteindre l'écran Chat : route `Route.Chat` (`AppNav.kt:71`),
   poussée par « Poser une question » depuis l'écran d'analyse
   (`AnalysisScreen.kt:91` → `AppNav.kt:130`) ou par une demande venue des lunettes
   (`AppNav.kt:96`).
2. Permission micro : au premier lancement `MainActivity.requestMicIfNeeded()` la
   demande ; sinon Réglages → refuser/accord est rejouable via
   `adb shell pm revoke dev.sam3vision.app android.permission.RECORD_AUDIO`.
3. **Tap** sur le bouton d'état « Dicter » (`ChatScreen.kt`, `DictationButton`) :
   `toggleMic` appelle `onStartDictation()` → `DictationFeature.start(hold = false)`.
   Attendu : libellé **« Stop »** (couleur danger), ligne d'état
   *« Enregistrement… parle, puis appuie sur Stop. »*, **pas de bandeau plein écran**
   (le bandeau n'existe que pour `hold = true`).
4. Parler ≥ **400 ms** (minimum refusé localement en dessous).
5. **Tap** sur « Stop » → `stopDictation()` : libellé **« … »** pendant la
   transcription, bouton désactivé (`enabled = !dictation.busy`).
6. Moteur : `speechClient(settings)` = Meta Muse d'abord, repli ElevenLabs Scribe
   (`docs/META-ASR.md`).
7. Succès : `dictation.text` = transcription, **`seq` incrémenté** ; le
   `LaunchedEffect(state.dictation.seq)` de `ChatScreen.kt:54-55` insère le texte dans
   le brouillon via `mergeDraft(draft, dictated)` — jointure par une seule espace,
   blancs ignorés (`UiLogicTest` couvre les 4 cas). Le bouton revient à « Dicter ».
8. Échec : le champ **ne bouge pas**, `statusLine = « Dictée KO — <message> »`
   (ex. `… paiement requis (402) …`, `… dictée impossible : Meta : … — ElevenLabs : …`).

**Résultat : non mesuré** au 2026-10-25 (aucun écran ni émulateur piloté). La dernière
exécution connue de ce trajet est l'émulateur du 2026-10-24, § « Ancienne recette ».

## Recette B — appui long vocal (iSee, page d'accueil)

1. Ouvrir l'app (iSee est la page d'accueil, `AppNav.kt:60`) ; la fenêtre caméra peut
   être en bulle, l'appui long marche sur **toute** la surface.
2. Poser le doigt et tenir ≥ **300 ms** (`LongPressTrigger.DEFAULT_LONG_PRESS_MS`),
   sans glisser de plus de **24 px** (`DEFAULT_SLOP_PX`) et sans second doigt : un
   glissement ou un second doigt avant le seuil **annulent** sans rien déclencher.
3. Seuil atteint → `onHoldStart` : vibration (`IhearHaptics.holdStart`),
   `holding = true`, micro ouvert (`HoldTranscript` → `AudioRecord` 16 kHz mono).
   Micro indisponible ⇒ ligne d'état `Microphone unavailable`.
4. Relâcher → `onHoldEnd` → `transcribeAndAnswer()` :
   - si une bulle du chat est en cours de lecture : la capture est **jetée** et
     l'état affiche `Busy speaking — try again` ;
   - sinon : `DictationUseCase` (min 400 ms) → Meta puis Scribe ; `transcribing` vrai
     pendant l'appel ;
   - la phrase « Disable iSee intro » (`isIntroDisablePhrase`) coupe l'intro **sans**
     être envoyée comme question ;
   - texte transcrit vide ⇒ `Nothing heard` ;
   - succès ⇒ ligne Room (`saveUserMessage(pcm, text)`, le PCM est gardé en MP3 ici,
     contrairement au trajet chat) puis **question posée au LLM**, réponse lue par
     ElevenLabs ;
   - erreur ⇒ `Dictation: <message>`.
5. Clavier/adb : `adb shell input swipe X Y X Y 1700` (sur MIUI, prévoir un léger
   déplacement, sinon la durée est ignorée — voir l'ancienne recette).

**Résultat : non mesuré** au 2026-10-25. Ce trajet est celui des essais du 2026-10-24
ci-dessous, à rejouer.

## Ce qui est vérifié sans appareil (tests JVM)

- `core/src/test/.../gesture/LongPressTriggerTest` : seuil 300 ms, glissement toléré
  ou annulant, multi-touch avant/après seuil, clôture unique.
- `app/src/test/.../UiLogicTest` : `mergeDraft` (les 4 cas d'insertion).
- `core/src/test/.../usecase/DictationUseCaseTest` et le client Meta : durée minimale,
  texte vide, multipart `request` + `audio` sans `Content-Length` par part.
- `./gradlew :core:test` : **89 verts dont 10 `LongPressTriggerTest`** (comptage du
  2026-10-24, conservé daté ; **non re-exécuté** dans cette passe).

## Brancher le vrai moteur

Réglages → section « Dictée Meta » : base URL `https://api.meta.ai/v1`, clé Meta,
modèle `muse-voice-transcribe-1.0` (`SettingsScreen.kt`, `DEFAULT_ASR_*`). Sans clé
Meta, la dictée utilise la **clé ElevenLabs** déjà saisie (Scribe `scribe_v1`).
« Tester la dictée Meta » envoie 1 s de silence et juge sur le code HTTP.

Pour valider le texte transcrit sans le service réel : mock local (l'ancienne recette
utilisait `/tmp/mock_muse_asr.py`, port 51987) puis base URL `http://10.0.2.2:51987/v1`
— le debug APK autorise le clair pour 10.0.2.2/localhost
(`res/xml/network_security.xml`), jamais pour les autres hôtes.

## Ancienne recette — écran Capture, 2026-10-24 (périmée, conservée)

> ⚠️ **Périmé en partie** : la branche `longpress-muse-dictation` et son worktree
> `~/tmp/sam-wt-longpress-muse-dictation` ne sont plus l'état de master ; la vue
> racine `LongPressRootLayout` existe encore dans `app/src/main/java` mais **n'est
> plus utilisée** (remplacée par `IhearRootLayout`, `MainActivity.kt:76`) ;
> `AppViewModel.onHoldStart/onHoldEnd` (`hold = true`) ne sont plus branchés sur
> aucune vue — le bandeau plein écran « J'écoute / Transcription / Dictée » n'est donc
> plus atteignable par un geste. Les mesures ci-dessous restent des faits datés.

| # | Geste (écran Capture, 1080x2400) | Attendu | Résultat 2026-10-24 |
|---|----------------------------------|---------|---------------------|
| 1 | Appui long 1,7 s au centre (540,900) | Bandeau « J'écoute » puis résultat en gros texte, POST audio parti | **OK** — POST 42 420 octets, bandeau « DICTÉE » |
| 2 | Tap court (100 ms) | Rien | **OK** — aucun bandeau |
| 3 | Swipe vertical 900 ms (glissement) | Rien (pas un appui long) | **OK** |
| 4 | Appui long dans la zone de gestes système (540,2360) | Rien — exclusion bas d'écran | **OK** |
| 5 | Appui long sur le bouton « Photographier » | Dictée prioritaire, caméra NON ouverte | **OK** — POST 43 444 octets |
| 6 | Appui long sur le bandeau ouvert | Nouvelle dictée (le tap seul ferme) | **OK** |
| 7 | Tap sur « Fermer » / n'importe où | Bandeau fermé | **OK** |
| 8 | Permission micro refusée | Voix ElevenLabs « Micro non autorisé » + bandeau | Lu via ElevenLabs sur téléphone ; le refus lui-même reste à rejouer |

Installer et rejouer (à faire sur le poste de travail, pas ici) :

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant dev.sam3vision.app android.permission.RECORD_AUDIO
adb shell am start -n dev.sam3vision.app/.MainActivity
# appui long : adb shell input swipe X Y X Y 1700   (lecture : adb shell uiautomator dump)
```

### Téléphone réel 2312DRA50G (Android 15, navigation 3 boutons), 2026-10-24 — conservé

- TTS système Google lisait résultat et erreurs (limitation « pas de moteur TTS »
  propre à l'AVD). **Aujourd'hui périmé** : l'app n'utilise plus aucun TTS système,
  tout passe par ElevenLabs (`VoiceAnnouncer`, `ProximityVoice`).
- Exclusion des boutons système : appui long 1,5 s sur Retour puis Récents
  (y = 2685) → aucune dictée, aucun POST.
- Trajet complet sans mock : `POST https://api.meta.ai/v1/asr/transcribe` avec la clé
  du compte → `402 billing_not_configured` affiché et lu. La clé est donc valide
  (pas de 401) ; seul le moyen de paiement bloque la transcription Meta.
- Trajet avec texte affiché : mock via `adb reverse tcp:51987`, base
  `http://localhost:51987/v1` → bandeau « DICTÉE » + texte + voix.
- Chaîne complète : appui long 5 s (swipe avec léger déplacement, sinon MIUI ignore la
  durée) → micro ouvert **4,7 s** → Meta 402 → repli Scribe → texte de Scribe lu par
  ElevenLabs (4 flux audio en logcat). Texte relevé : « [birds chirping] » — la voix
  humaine reste à valider en parlant réellement au téléphone.

## Non mesuré (à ne pas vendre comme vérifié)

- Aucune des deux recettes n'a été rejouée le 2026-10-25 : **non mesuré**.
- Comptage des tests JVM au 2026-10-25 : **non mesuré** (aucun `gradlew` lancé).
- Durée maximale réelle d'une tenue (le plafond de 60 s a été retiré) : **non mesuré** ;
  taille d'un WAV au-delà d'une minute, et réaction du service Meta : **non mesuré**.
- `performLongClick` sur « Dicter » : **aucun test instrumenté n'existe** ; d'après le
  code, le geste attendu est un tap, pas un appui long.
- Refus de permission micro sur l'app actuelle : **non mesuré** (le trajet bandeau
  n'est plus branché ; iSee affiche `Microphone unavailable`).