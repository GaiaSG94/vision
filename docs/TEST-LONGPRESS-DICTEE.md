# Recette — Appui long : dictée Muse Transcript

Branche `longpress-muse-dictation` · worktree `~/tmp/sam-wt-longpress-muse-dictation` · APK `app/build/outputs/apk/debug/app-debug.apk`

## Ce qui est testé

| # | Geste (écran Capture, 1080x2400) | Attendu | Résultat 2026-10-24 |
|---|----------------------------------|---------|---------------------|
| 1 | Appui long 1,7 s au centre (540,900) | Bandeau « J'écoute » puis résultat en gros texte, POST audio parti | **OK** — POST 42 420 octets, bandeau « DICTÉE » |
| 2 | Tap court (100 ms) | Rien | **OK** — aucun bandeau |
| 3 | Swipe vertical 900 ms (glissement) | Rien (pas un appui long) | **OK** |
| 4 | Appui long dans la zone de gestes système (540,2360) | Rien — exclusion bas d'écran | **OK** |
| 5 | Appui long sur le bouton « Photographier » | Dictée prioritaire, caméra NON ouverte | **OK** — POST 43 444 octets |
| 6 | Appui long sur le bandeau ouvert | Nouvelle dictée (le tap seul ferme) | **OK** |
| 7 | Tap sur « Fermer » / n'importe où | Bandeau fermé | **OK** |
| 8 | Permission micro refusée | Voix ElevenLabs « Micro non autorisé » + bandeau | Lu via ElevenLabs sur téléphone (annonces d'erreur lues vérifiées) ; le refus lui-même reste à rejouer (permission déjà accordée) |

Tests unitaires : `./gradlew :core:test` → 89 verts dont 10 `LongPressTriggerTest`
(seuil 300 ms, glissement toléré/annulant, multi-touch avant/après seuil, clôture unique).

## Installer et rejouer

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant dev.sam3vision.app android.permission.RECORD_AUDIO   # ou accepter la demande au 1er lancement
adb shell am start -n dev.sam3vision.app/.MainActivity
```

Sans écran, piloter par `adb shell input swipe X Y X Y 1700` (appui long) et
`uiautomator dump` pour lire le bandeau.

## Brancher le vrai moteur

Réglages → « Dictée Meta — base URL (ASR) » = `https://api.meta.ai/v1`,
clé = clé Meta, modèle `muse-voice-transcribe-1.0`. **Compte Meta actuel : 402
`billing_not_configured`** (facturation non réglée) → le bandeau affichera
« Erreur — paiement requis (402) », ce qui valide déjà tout le trajet aller.

Pour valider le texte transcrit sans le vrai service : `python3 /tmp/mock_muse_asr.py`
(serveur local, port 51987, répond un texte fixe) puis base URL
`http://10.0.2.2:51987/v1` — le debug APK autorise le clair pour 10.0.2.2/localhost
(`res/xml/network_security.xml`), jamais pour les autres hôtes.

## Recette sur téléphone réel (2312DRA50G, Android 15, navigation 3 boutons — 2026-10-24)

- **TTS** : le moteur Google du téléphone lit bien le résultat et les erreurs
  (streams `com.google.android.tts` vérifiés en logcat) — la limite « pas de moteur
  TTS » ne concernait que l'AVD sans moteur installé.
- **Exclusion des boutons système** : appui long 1,5 s sur Retour puis sur Récents
  (y=2685, barre système) → aucune dictée déclenchée, aucun POST.
- **Trajet complet sans mock** : appui long → `POST https://api.meta.ai/v1/asr/transcribe`
  avec la clé Meta du compte → réponse `402 billing_not_configured` affichée et lue
  (« Erreur — paiement requis (402) »). La clé est donc valide (pas de 401) ; seul le
  moyen de paiement du compte Meta bloque la transcription réelle.
- Trajet avec transcription affichée : validé via mock (`adb reverse tcp:51987`,
  base URL `http://localhost:51987/v1`) → bandeau « DICTÉE » + texte + voix.

## Voix et moteurs (demande user 2026-10-24 : pas de Google TTS)

- **Voix = ElevenLabs uniquement** : `VoiceAnnouncer` (ElevenLabsTtsClient + MediaPlayer,
  MP3 dans le cache privé). Aucun TTS Android (SystemSpeech supprimé). Chaque bandeau
  (« J'écoute », « Transcription », résultat, erreurs) est un énoncé lu — une seule règle,
  centralisée dans le ViewModel (collect du `sequence` du bandeau).
- **Dictée = Meta d'abord, repli ElevenLabs Scribe** : `FallbackSpeechClient(MetaAsrClient,
  ElevenLabsSttClient)` ; la clé ElevenLabs des réglages sert aussi au STT (model `scribe_v1`).
  Si les deux échouent, `ApiError.AllProviders` affiche les deux messages.
- TalkBack reste actif sur le bandeau (région live, gros caractères 3 sp->34 sp) ; l'appui
  long TalkBack sur un élément n'est jamais intercepté.

### Recette chaîne complète sur le téléphone (2312DRA50G)

Appui long 5 s (sur MIUI, `input swipe` doit avoir un léger déplacement, sinon la durée
est ignorée : utiliser `swipe X Y X Y+4 5000`) → micro ouvert 4,7 s → Meta répond 402 →
repli Scribe → bandeau « DICTÉE » avec le texte de Scribe → lu par la voix ElevenLabs
(4 flux audio `dev.sam3vision.app` en logcat). Texte relevé pendant l'essai : « [birds
chirping] » (Scribe qualifie l'ambiance de la pièce — la voix humaine reste à valider
en parlant réellement au téléphone).
