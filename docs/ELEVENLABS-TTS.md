# Voix ElevenLabs — Jade US (`TOXPlnYC7z5SQSV2Zgc5`)

Relevé du 2026-10-24, demande user : « implémenter la voix Jade US dans ElevenLabs
pour l'app SAM Vision ». L'app lisait déjà les réponses (contrat `TtsClient` posé avec la
dictée), mais **aucun client TTS n'existait** : `Settings` annonçait une voix, rien ne
l'appelait. C'est ce chaînon qui est écrit ici.

## Quelle voix, exactement

**Jade US = `TOXPlnYC7z5SQSV2Zgc5`** (ID communiqué par l'utilisateur le 2026-10-24,
vérifié en réel ci-dessous). C'est l'ID retenu comme défaut de l'app
(`Settings.DEFAULT_TTS_VOICE_ID`).

Attention, la doc publique ElevenLabs (« What are Default voices ? »,
`https://elevenlabs.io/docs/help-center/product/voices/my-voices/what-are-default-voices.md`)
ne liste **aucune** voix appelée « Jade US » : la seule Jade documentée y est la voice
library `g7LVvkPWALzPxOQbF6OE` (« Jade – Upbeat and Natural », remplaçante de Jessica).
Premier jet de cette intégration parti sur cet ID-là — correction faite. La clé du compte
n'ayant pas la permission `voices_read` (401 `missing_permissions` sur `/v1/voices`), la
liste réelle du compte n'est pas interrogeable : c'est l'ID fourni qui fait foi, et il
répond.

Vérifié sur le compte du 24/10 (clé `sk_9332…` dans `local.properties`) :

| Requête | Résultat |
|---|---|
| `POST /v1/text-to-speech/TOXPlnYC7z5SQSV2Zgc5` `eleven_v3` | **200 `audio/mpeg`**, 30 554 octets |
| idem `eleven_multilingual_v2` | **200 `audio/mpeg`**, 27 628 octets |
| idem avec `language_code: en`, `fr`, ou sans | 200, audio identique ou équivalent |

MP3 44,1 kHz mono 128 kb/s dans tous les cas. `eleven_v3` est multilingue et suit la
langue du texte : `language_code` n'est pas un filtre, il reste optionnel (défaut `en`).

Migration : les deux valeurs jamais saisies par l'utilisateur (Rachel
`21m00Tcm4TlvDq8ikWAM` + `eleven_turbo_v2`, et la première Jade `g7LVvkPWALzPxOQbF6OE`)
deviennent `TOXPlnYC7z5SQSV2Zgc5` + `eleven_v3` (`Settings.migratedVoice()`). Une voix
choisie explicitement n'est **jamais** écrasée.

## Contrat HTTP utilisé

```
POST https://api.elevenlabs.io/v1/text-to-speech/{voice_id}?output_format=mp3_44100_128
xi-api-key: <clé>          # pas Authorization: Bearer
Content-Type: application/json

{"text":"…","model_id":"eleven_v3","language_code":"en",
 "voice_settings":{"stability":0.5,"similarity_boost":0.75}}
```

- **Modèle** : `eleven_v3` par défaut (`Settings.DEFAULT_TTS_MODEL`), `language_code`
  explicite (`en`). `eleven_v3` est **multilingue** : il suit la langue du texte, le
  français est lu en français (l'accent dépend de la voix). Les voix d'une autre langue
  (Sarah, Brian, Roger) se choisissent dans Réglages sans toucher au code.
- **Limite dure** : 5 000 caractères par requête, au-delà `400 text_too_long`. Le client
  refuse avant le réseau et `SentenceChunker` (1 200 car.) découpe aux phrases.
- **Erreurs typées** : 401 `quota_exceeded` → `ApiError.UsageLimitExceeded` (quota de
  caractères épuisé), 402 → `ApiError.PaymentRequired`, 429 → `RateLimited`, 2xx non
  audio → `BadResponse` (un JSON d'erreur n'est jamais joué comme un MP3).

## Câblage dans l'app

| Élément | Où |
|---|---|
| Client HTTP | `core/net/ElevenLabsTtsClient.kt` (pur JVM, testé) |
| Découpage en phrases | `core/audio/SentenceChunker.kt` |
| Synthèse + cache LRU (6 réponses) | `core/usecase/SpeechUseCase.kt` |
| Lecture MP3 + cache disque | `app/VoicePlayer.kt` (`MediaPlayer`, `cacheDir/tts`) |
| Pilote (une lecture à la fois, erreurs → bandeau) | `app/VoiceController.kt` |
| Boutons « Lire / Stop », bascule haut-parleur | `app/VoiceUi.kt`, Chat et Analyse |
| Réglages (clé, voix, modèle, « Tester la voix ») | `app/SettingsScreen.kt` |

Comportement : la description et chaque réponse d'assistant ont un bouton **Lire** ;
la bascule **Haut-parleur** coupe la lecture automatique (activée par défaut quand une
clé existe). Rejouer une réponse ne re-consomme pas de caractères (cache). La voix est
coupée par `reset()` et `onCleared()`.

## Brancher la clé

1. **Écran Réglages** (recommandé) : coller la clé dans « Clé API ElevenLabs » →
   Enregistrer → « Tester la voix ElevenLabs » (`SAM Vision, voice check.`).
2. **`local.properties`** (debug, gitignoré), seules ces lignes comptent :

```properties
tts.base.url=https://api.elevenlabs.io/v1
tts.api.key=sk_…votre-cle…
tts.model=eleven_v3
tts.voice.id=TOXPlnYC7z5SQSV2Zgc5
```

Sans clé, bouton « Lire » affiche **Voix KO**, zéro requête réseau (comme le LLM).
La clé est chiffrée au repos (Keystore), jamais commitée.

## Coût

Compteur du compte le 24/10 : **321 / 130 005 caractères** consommés (tier `creator`)
au premier relevé, plus les essais de vérification (~200 caractères). Une lecture de description
coûte ~1 caractère par caractère lu ; le cache évite de payer deux fois la même réponse.

## Migration des réglages existants

L'ancien défaut (`21m00Tcm4TlvDq8ikWAM`, Rachel, modèle `eleven_turbo_v2`) est remplacé
à la lecture du store par Jade US + `eleven_v3` (`Settings.migratedVoice()`). Une voix
choisie explicitement par l'utilisateur n'est **jamais** écrasée.

## Reste ouvert

- Pas de test sur émulateur depuis ce serveur (aucun émulateur lancé ici) : la recette
  d'écoute est dans `docs/TESTING.md`.
- La clé du compte n'a pas `voices_read` : le sélecteur de voix propose des IDs connus,
  pas la liste réelle du compte. Élargir la permission de la clé si besoin.
