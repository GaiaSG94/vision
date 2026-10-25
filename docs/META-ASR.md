# Dictée Meta Muse (`muse-voice-transcribe-1.0`) — contrat client

Relevé du **2026-10-25** dans le worktree `sam-wt-docs-app` (origin/master, `8f9876a`).
Le contrat décrit ici est celui du **code** (`core/net/MetaAsrClient.kt`), pas d'une
doc officielle : l'API n'est pas compatible OpenAI et sa documentation publique ne
couvre pas ce chemin. Les constats datés du 2026-10-24 sont conservés ; ce qui est
périmé est **marqué**, pas supprimé.

Aucune donnée vocale n'est conservée sur l'appareil pour la dictée : le PCM reste en
mémoire jusqu'à l'envoi, puis il est jeté (seul le MP3 de la ligne Room est gardé
lors d'un appui long iSee, voir plus bas).

## Contrat HTTP

```
POST {asrBaseUrl}/asr/transcribe
Authorization: Bearer <clé Meta>
User-Agent: sam-vision-kotlin
Content-Type: multipart/form-data; boundary=----sam3vision<uuid sans tirets>

partie 1 : Content-Disposition: form-data; name="request"
           Content-Type: application/json
           {"model":"muse-voice-transcribe-1.0","audioEncoding":"WAV"}
partie 2 : Content-Disposition: form-data; name="audio"; filename="audio.wav"
           Content-Type: audio/wav
           <octets WAV>
```

- **Base URL** : `Settings.DEFAULT_ASR_BASE_URL = "https://api.meta.ai/v1"`, surchargeable
  aux Réglages (« Dictée Meta — base URL (ASR) »).
- **Chemin** : `MetaAsrClient.TRANSCRIBE_PATH = "/asr/transcribe"`, ajouté par `joinUrl`.
- **Modèle** : `Settings.DEFAULT_ASR_MODEL = "muse-voice-transcribe-1.0"`, surchargeable.
- **Clé** : `Settings.asrApiKey`, en `Authorization: Bearer` (pas `xi-api-key` : c'est
  l'inverse d'ElevenLabs). Clé vide ⇒ échec **local**, sans réseau
  (`ApiError.InvalidKey("clé Meta absente — renseigne-la dans Réglages")`).
- **Réponse** : JSON ; le texte est extrait défensivement (`extractTranscript`) qui
  accepte `text`, `transcript`, `transcription`, `output_text`, `result.text`,
  `output.text`, `data.text`, puis recolle un tableau `segments`. Champ introuvable ⇒
  `ApiError.BadResponse("champ texte absent : <120 premiers caractères>")`.
- Pas de route compatible OpenAI pour la voix, pas de `/health` : le test « Tester la
  dictée Meta » envoie **1 s de silence** (16 000 × 2 octets, `AppViewModel.testAsr`)
  et juge sur le code HTTP.

## Audio envoyé : 16 kHz mono PCM 16 bits, WAV RIFF

| Paramètre | Valeur | Preuve |
|---|---|---|
| Fréquence | **16 000 Hz** | `Wav.SAMPLE_RATE` (`core/audio/Wav.kt`) |
| Canaux | **1** (mono) | `Wav.CHANNELS` |
| Encodage | **PCM 16 bits** | `Wav.BITS_PER_SAMPLE` |
| Conteneur | WAV RIFF, en-tête **44 octets** (`RIFF`/`WAVE`/`fmt `/`data`) | `Wav.header()` + `Wav.pcm16()` |
| Capture | `AudioRecord`, source `VOICE_RECOGNITION`, mono / PCM16 / 16 kHz | `app/DictationRecorder.kt` |

⚠️ **Correction datée (2026-10-25)** : l'ancienne ligne « PCM 16 bits mono **16 ou
24 kHz** » se lisait comme un choix offert. Le commentaire de `Wav.kt` dit bien que
Meta accepte 16 ou 24 kHz, mais **l'app n'envoie jamais que 16 kHz** et aucun réglage
ne permet d'en changer : `DictationRecorder(sampleRate = Wav.SAMPLE_RATE=16_000)`.

## Durée : le plafond de 60 s n'existe plus

- `DictationRecorder.MAX_DURATION_MS = 0L` → **aucune limite** : les chunks PCM
  s'empilent en mémoire (jamais de fichier), la capture s'arrête au relâchement.
  `MuseTranscript` et `media/HoldTranscript` le disent explicitement : « sans limite
  de durée ».
- **Refus local avant réseau** : `DictationUseCase.MIN_DURATION_MS = 400L` →
  `BadResponse("enregistrement trop court (N ms, minimum 400 ms)")`.
- **Texte vide** après transcription → `BadResponse("aucune parole détectée")`
  (iSee affiche `Nothing heard`).
- **WAV trop court** : `wav.size <= 44` (en-tête seul) ⇒ `BadResponse("audio vide")`,
  sans appel réseau.

⚠️ **Périmé (conservé daté)** : « micro 16 kHz mono (**60 s max**) » (CHANGELOG,
2026-10-24) correspondait à un plafond du `DictationRecorder` depuis retiré. **Non
mesuré** : le comportement et la taille d'un envoi au-delà de 60 s (aucune mesure de
tenue longue dans ce worktree, et le compte Meta est bloqué en facturation).

## Erreurs : ce que voit l'utilisateur

| Code / cas | `ApiError` | Message affiché |
|---|---|---|
| 401, 403 | `InvalidKey` | `clé invalide (401)` / `accès refusé (403)` |
| 402 | `PaymentRequired` | `paiement requis (402) : compte sans crédits ou facturation non configurée (…)` |
| 429 | `RateLimited` | `limite atteinte (429)` |
| autre code | `Http(code, body)` | `erreur HTTP <code> : <160 premiers caractères>` |
| corps non JSON / champ absent | `BadResponse` | `réponse inattendue : champ texte absent : …` |
| délai dépassé | `Timeout` | `backend injoignable : délai dépassé` |
| réseau coupé | `Network` | `backend injoignable : réseau` |
| Meta **et** Scribe en échec | `AllProviders` | `dictée impossible : Meta : <…> — ElevenLabs : <…>` |

Côté écran : bouton du chat ⇒ ligne d'état `Dictée KO — <message>` ; appui long iSee
⇒ `status = "Dictation: <message>"` (iSee est en anglais).

## Repli ElevenLabs Scribe (partie intégrante du contrat de dictée)

`app/SpeechClients.kt` définit **la** règle de l'app :

```kotlin
fun speechClient(settings: Settings): SpeechClient? {
    val meta   = if (settings.asrConfigured) MetaAsrClient(...) else null   // base + clé Meta
    val eleven = if (settings.ttsApiKey.isNotBlank()) ElevenLabsSttClient(settings.ttsApiKey) else null
    // les deux → FallbackSpeechClient(primary = meta, fallback = eleven)
    // un seul → celui-là ; aucun → null (« aucun moteur de dictée »)
}
```

- **Scribe** = `POST https://api.elevenlabs.io/v1/speech-to-text`, multipart
  `model_id` + `file` (`audio/wav`), en-tête `xi-api-key`, modèle `scribe_v1`
  (`core/net/ElevenLabsSttClient.kt`). Même extraction de texte que Meta.
- `FallbackSpeechClient` essaie Meta puis Scribe **sur le même WAV** ; l'erreur du
  primaire seule remonte si aucun repli n'est configuré ; si les deux échouent,
  `AllProviders` compose les deux messages.
- Conséquence pratique : **une dictée peut réussir même avec un compte Meta bloqué**,
  puisque la clé ElevenLabs des Réglages sert aux deux (TTS et STT).

## ⚠️ Blocage de facturation Meta — statut du 2026-10-24, **non re-vérifié**

Le compte renvoyait sur **tout** appel d'inférence (chat, images, asr) :

```
402 billing_error — {"error":{"code":"billing_not_configured","message":"Billing verification failed"}}
```

À régulariser sur `https://dev.meta.ai/billing/?action=resolve_billing` (team_id
2153010125253051) : ajout d'un moyen de paiement, acte personnel que l'agent ne peut
pas faire. Le chemin complet était validé **depuis l'appareil** : micro ouvert, WAV
construit, requête acceptée (plus de 400), réponse 402 remontée telle quelle dans le
bandeau. Dès que la facturation est OK, la dictée fonctionne sans changer une ligne.

**Statut au 2026-10-25 : non mesuré dans cette passe** (aucune requête réseau lancée
ici). Même cause, même remède pour `custom.muse` côté ZeroClaw et pour le pont local
`meta-asr-bridge.service` (127.0.0.1:8788) des notes vocales.

## Deux pièges d'intégration (conservés)

1. **OkHttp et le `Content-Length` par part.** `MultipartBody` écrit un en-tête
   `Content-Length:` *dans chaque partie* ; Meta répond alors
   `400 invalid_request_error — Malformed multipart body`. Le corps est donc construit
   **à la main** dans `MetaAsrClient.multipart` (une seule longueur : celle de la
   requête). Le test `transcribe envoie un multipart request+audio…` échoue si
   quelqu'un revient à `MultipartBody`.
2. **`input text` d'adb et la clé Meta.** La clé contient un `|` : sans échappement,
   la saisie est coupée après quelques caractères et l'app part en 401. Échapper `|`
   en `\|` (et ne jamais écrire la clé dans un fichier suivi par git).

## Où ça vit dans le code

| Rôle | Fichier |
|---|---|
| Enveloppe WAV + durée | `core/src/main/java/dev/sam3vision/core/audio/Wav.kt` |
| Client HTTP Meta (+ multipart à la main) | `core/.../net/MetaAsrClient.kt` |
| Repli Scribe | `core/.../net/ElevenLabsSttClient.kt` |
| Chaîne Meta → Scribe | `core/.../net/FallbackSpeechClient.kt`, `app/SpeechClients.kt` |
| Règles (durée mini, texte vide) | `core/.../usecase/DictationUseCase.kt` |
| Micro (AudioRecord 16 kHz) | `app/DictationRecorder.kt` |
| Façade « audio → texte » | `app/MuseTranscript.kt`, `app/media/HoldTranscript.kt` |
| Dictée du chat (bouton) | `app/DictationFeature.kt`, `app/ChatScreen.kt`, `app/DictationOverlay.kt` |
| Dictée du direct (voix de la question) | `app/LiveVoice.kt` |
| Appui long iSee | `app/ihear/IhearViewModel.kt` (`onHoldStart`, `transcribeAndAnswer`) |
| Réglages (base URL, clé, modèle, test) | `app/SettingsScreen.kt` ; test = `AppViewModel.testAsr` |