# Dictée Meta Muse (`muse-voice-transcribe-1.0`)

L'app parle, Meta écrit, le chat affiche. Aucune donnée vocale n'est stockée sur l'appareil :
le PCM reste en mémoire jusqu'à l'envoi, puis il est jeté.

## Contrat de l'API (vérifié le 2026-10-24 au curl)

```
POST https://api.meta.ai/v1/asr/transcribe
Authorization: Bearer <clé Meta>
Content-Type: multipart/form-data; boundary=…

partie 1 : name="request"  → {"model":"muse-voice-transcribe-1.0","audioEncoding":"WAV"}
partie 2 : name="audio"    → fichier audio.wav, PCM 16 bits mono 16 ou 24 kHz
```

Réponse : `{ "text": "…" }` (d'autres formes existent selon les sessions — le parseur
`extractTranscript` accepte `text`, `transcript`, `transcription`, `output_text`,
`result.text`, `output.text`, `data.text`, puis recolle un tableau `segments`).

Pas de route compatible OpenAI pour la voix, pas de `/health` : le test « Tester la dictée
Meta » envoie 1 s de silence et juge sur le code HTTP.

## Deux pièges relevés en intégration

1. **OkHttp et le `Content-Length` par part.** `MultipartBody` écrit un en-tête
   `Content-Length:` *dans chaque partie* ; Meta répond alors
   `400 invalid_request_error — Malformed multipart body`. Le corps est donc construit à la
   main dans `MetaAsrClient.multipart` (une seule longueur : celle de la requête).
   Le test `transcribe envoie un multipart request+audio…` échoue si quelqu'un revient à
   `MultipartBody`.
2. **`input text` d'adb et la clé Meta.** La clé contient un `|` : sans échappement, la
   saisie est coupée après 6 caractères et l'app part en 401. Échapper `|` en `\|`.

## ⚠️ Blocage actuel : facturation Meta

Le compte renvoie sur **tout** appel d'inférence (chat, images, asr) :

```
402 billing_error — {"error":{"code":"billing_not_configured","message":"Billing verification failed"}}
```

À régulariser sur `https://dev.meta.ai/billing/?action=resolve_billing` (team_id
2153010125253051) : ajout d'un moyen de paiement, acte personnel que l'agent ne peut pas faire.
Le chemin complet est pourtant validé **depuis l'appareil** : micro ouvert, WAV construit,
requête acceptée (plus de 400), réponse 402 remontée telle quelle dans le chat
(« Dictée KO — paiement requis (402) … »). Dès que la facturation est OK, la dictée fonctionne
sans changer une ligne de code.

Même cause, même remède pour `custom.muse` côté ZeroClaw et pour le pont local
`meta-asr-bridge.service` (127.0.0.1:8788) utilisé par les notes vocales.

## Où ça vit dans le code

| Rôle | Fichier |
|---|---|
| Enveloppe WAV + durée | `core/.../audio/Wav.kt` |
| Client HTTP Meta | `core/.../net/MetaAsrClient.kt` |
| Règles (durée mini, texte vide) | `core/.../usecase/DictationUseCase.kt` |
| Micro (AudioRecord 16 kHz) | `app/.../DictationRecorder.kt` |
| Bouton Dicter/Stop + insertion | `app/.../ChatScreen.kt` |
| Réglages (base URL, clé, modèle) | `app/.../SettingsScreen.kt` |
| Orchestration | `app/.../AppViewModel.kt` (`startDictation`/`stopDictation`/`testAsr`) |
