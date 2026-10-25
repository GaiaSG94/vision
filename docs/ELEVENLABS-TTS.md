# Voix ElevenLabs — client, Jade US et intro d'accueil

Relevé du 2026-10-24, demande user : « implémenter la voix Jade US dans ElevenLabs
pour l'app SAM Vision ». Depuis le rebase sur `master` (commit `2486853`), **une seule
piste de lecture** existe : `VoiceAnnouncer` / `SpeechPlayer` de `master`. La branche
`feat/jade-tts` n'ajoute que ce qui manquait — l'**intro embarquée** et le **découpage
en phrases** (prêt, testé, pas encore branché sur la lecture).

## Quelle voix, exactement

**Jade US = `TOXPlnYC7z5SQSV2Zgc5`** (ID communiqué par l'utilisateur le 2026-10-24,
vérifié en réel ci-dessous). Il est exposé par `Settings.JADE_US_VOICE_ID` et proposé
dans les Réglages.

**Le défaut de l'app reste Sarah** (`EXAVITQu4vr4xnSDxMaL`, voix « premade ») : une
installation fraîche doit parler même sur un compte free. Jade US est une voix de
bibliothèque : elle répond **402** tant que le compte n'a pas de crédits — le message
d'erreur de `ApiError.PaymentRequired` le dit explicitement. Mettre Jade par défaut
casserait donc la voix des comptes free ; on la choisit dans Réglages (champ
« ID de voix ElevenLabs »).

Attention, la doc publique ElevenLabs (« What are Default voices ? »,
`https://elevenlabs.io/docs/help-center/product/voices/my-voices/what-are-default-voices.md`)
ne liste **aucune** voix appelée « Jade US » : la seule Jade documentée y est la voice
library `g7LVvkPWALzPxOQbF6OE` (« Jade – Upbeat and Natural »). La clé du compte n'ayant
pas la permission `voices_read` (401 `missing_permissions` sur `/v1/voices`), la liste
réelle du compte n'est pas interrogeable : c'est l'ID fourni qui fait foi, et il répond.

Vérifié sur le compte du 24/10 (clé `sk_9332…` dans `local.properties`) :

| Requête | Résultat |
|---|---|
| `POST /v1/text-to-speech/TOXPlnYC7z5SQSV2Zgc5` `eleven_v3` | **200 `audio/mpeg`**, 30 554 octets |
| idem `eleven_multilingual_v2` | **200 `audio/mpeg`**, 27 628 octets |
| idem avec `language_code: en`, `fr`, ou sans | 200, audio identique ou équivalent |

MP3 44,1 kHz mono 128 kb/s dans tous les cas.

## Contrat HTTP utilisé (client de `master`)

```
POST {base}/text-to-speech/{voice_id}?output_format=mp3_44100_128
xi-api-key: <clé>          # pas Authorization: Bearer
User-Agent: sam-vision-kotlin
Content-Type: application/json

{"text":"…","model_id":"eleven_v4_turbo",
 "voice_settings":{"stability":0.5,"similarity_boost":0.75,"style":0.0,"use_speaker_boost":true}}
```

- **URL construite par OkHttp** (`HttpUrl.newBuilder()`), jamais par concaténation : le
  `voice_id` est échappé correctement (`core/net/ElevenLabsTtsClient.kt`).
- **Modèle** : `eleven_v4_turbo` (`ElevenLabsTtsClient.DEFAULT_TTS_MODEL`), multilingue et
  accepté en compte free. Aucun champ « modèle » dans les Réglages : changer de modèle est
  un changement de constante, volontairement.
- **Erreurs typées** : 401 → `ApiError.InvalidKey`, 402 → `PaymentRequired`,
  429 → `RateLimited`, autre code → `Http`, 2xx non audio (corps vide ou qui commence par
  `{`/`[`/`<`) → `BadResponse`. Un JSON d'erreur n'est jamais joué comme un MP3.
- **Refus locaux, sans réseau** : texte vide, clé absente, voix absente.
- **Limite dure** : 5 000 caractères par requête (`400 text_too_long`).
  `core/audio/SentenceChunker` + `core/usecase/SpeechUseCase` découpent aux phrases
  (1 200 car. max par morceau) et mettent en cache LRU 6 réponses : ce découpage est testé
  en JVM et **pas encore branché** sur la lecture (une seule requête par phrase, comme
  aujourd'hui, suffit aux réponses courantes).

## Câblage dans l'app

| Élément | Où |
|---|---|
| Client HTTP | `core/net/ElevenLabsTtsClient.kt` (pur JVM, testé : URL, erreurs, audio) |
| Découpage en phrases + cache | `core/audio/SentenceChunker.kt`, `core/usecase/SpeechUseCase.kt` |
| Lecture MP3 (un seul lecteur) | `app/SpeechPlayer.kt` (`MediaPlayer`, `cacheDir/tts`) |
| Synthèse + état des boutons | `app/AppViewModel.kt` (`speak`, `stopSpeaking`, `SpeakState`) |
| Boutons « 🔊 Lire / ⏹ », bascule haut-parleur | `app/TtsState.kt`, `app/TtsSettings.kt`, Chat et Analyse |
| Voix des bandeaux de dictée | `app/VoiceAnnouncer.kt` |
| Intro embarquée (1re ouverture, sur appui) | `app/src/main/res/raw/intro_ihear.mp3`, `app/IntroAudio.kt`, `app/IntroGate.kt`, `app/IntroLogic.kt`, `AppViewModel.onIntroTap()` |

Comportement : la description et chaque réponse d'assistant ont un bouton **Lire** ;
la bascule **« Lire les réponses automatiquement »** commande la lecture à l'arrivée
(activée par défaut quand une clé existe). Une lecture à la fois : toute nouvelle lecture
coupe la précédente, et `reset()` / `onCleared()` coupent la voix.

## Annonce vocale des positions (Direct + analyse photo)

Demande user du 2026-10-25 : « décris-moi la position des objets qu'il voit, avec la voix
ElevenLabs ». Livré en trois briques, dont deux pures et testées en JVM.

| Élément | Où |
|---|---|
| Mise en mots des positions | `core/live/PositionText.kt` (secteurs, phrase, signature de scène) |
| Déclencheur anti-bavardage | `core/live/PositionAnnouncer.kt` |
| Voix + sous-titre du Direct | `app/LiveViewModel.kt` (`announcePositions`, `sayPositions`) |
| Annonce après une photo | `app/AppViewModel.kt` (`announcePositions`) |
| Bascule | `Settings.ttsAnnouncePositions` (défaut `true`), « Annoncer la position des objets détectés » |

**Forme de la phrase.** Labels + secteur, sans article (donc sans faute de genre sur des
labels libres) : `Personne à gauche, chaise en bas à droite.` Ordre de lecture gauche → droite,
un label répété au même endroit n'est cité qu'une fois, 3 objets au plus puis
« et N autres objets ». Secteurs : `centerX < 0,34` → à gauche, `> 0,66` → à droite, sinon au
centre ; même découpage en vertical (`en haut` / au milieu / `en bas`).

**Quand elle parle** (sinon elle se tairait ou bavarderait à 2 im/s) :
1. la scène doit être vue **2 frames de suite** (un masque qui clignote ne déclenche rien) ;
2. une scène **déjà annoncée** reste muette, même si les objets sont toujours là — la signature
   est `label|secteur` triée, insensible à l'ordre et aux doublons ;
3. **4 s minimum** entre deux annonces ;
4. scène vide ⇒ la mémoire de scène retombe, donc le retour des objets est de nouveau annoncé.

**Où ça parle.** Direct : à chaque frame publiée (`LiveViewModel.publish`), silencieux pendant
une question sur la mémoire du direct (`memory.asking`) et sans clé ElevenLabs. Photo : après
une analyse réussie, sur les objets segmentés par SAM (boîtes normalisées par la taille de la
photo envoyée). Un `Mutex` garantit qu'une phrase en cours n'est ni coupée ni doublée par la
frame suivante ; le Direct a son propre dossier audio (`cacheDir/positions`) pour ne pas
écraser le MP3 des bandeaux de dictée.

**Clé de build.** `seededFromBuild()` (`app/SettingsSeed.kt`, ex-bloc privé d'`AppViewModel`)
complète les réglages lus du store avec les valeurs de `local.properties` : **`LiveViewModel`
l'utilise aussi** — sans ça, le Direct restait muet quand la clé venait du build et non de
l'écran Réglages (bug trouvé en recette le 25/10).

**Recette émulateur jouée (25/10)** : faux backend SAM (`/tmp/mock_sam.py` : `/health` +
`/live` renvoyant une personne à gauche et une chaise à droite), app pointée dessus via
`sam.base.url` de `local.properties`, `emul-instance@1` (emulator-5556). Résultat : sous-titre
`🔊 Personne à gauche, chaise en bas à droite.`, `dumpsys media.player` montre le
`MediaPlayer` de l'app en `audio/mpeg` (décodeur `c2.android.mp3.decoder`, 127 008 frames
rendues ≈ 2,9 s). Bascule coupée + Enregistrer ⇒ frames toujours traitées (2,8 ips) mais plus
aucun sous-titre : l'annonce est bien conditionnée par le réglage.

## Intro de la première ouverture

`app/src/main/res/raw/intro_ihear.mp3` : 309 caractères, voix Jade US, `eleven_v3`,
MP3 44,1 kHz mono 128 kb/s, ~22 s, 347 785 octets. Rendu **une fois** par l'API et
embarqué : l'app ne consomme ni caractère ni réseau pour la jouer.

- Texte (source) :
  « Welcome to iHear. To record your surroundings, tap anywhere on the screen to start the
  video, and tap again to stop and analyze it. To ask a question or make a request, tap and
  hold while you speak, then release. To stop hearing this intro, press and hold the screen
  long enough to say, "Disable iHear intro." »
- **Deux conditions, jamais une de moins** : `Settings.introPending` (intro autorisée **et**
  jamais entendue) **et** un appui sur l'écran (`introGateVisible`, logique pure testée).
  L'app s'ouvre donc en silence sur `IntroGate` (« Touchez l'écran pour commencer ») ;
  sans ce geste, aucune voix ne sort.
- Lecture : `AppViewModel.onIntroTap()` → `SpeechPlayer.playFile(IntroAudio.ensure(...))` —
  **le lecteur du TTS**, donc une lecture coupe l'autre. `IntroAudio` recopie l'asset dans
  `cacheDir/intro/` (le cache peut être vidé : la copie est refaite au besoin).
- **Une seule fois** : `Settings.introPlayed` est posé et persisté (clé `intro_played` des
  `EncryptedSharedPreferences`, champ `introPlayed` du codec du fichier privé) **avant** la
  lecture — un arrêt en cours de route ne la rejoue pas. Absent d'un ancien réglage = faux,
  donc l'intro est redemandée une fois après la mise à jour.
- Coupure : bascule **« Intro à la 1re ouverture : on/off »** (Réglages →
  `Settings.introEnabled`, vrai par défaut, persisté par les deux stores).
- Régénérer avec l'autre modèle (choix de timbre) :
  `eleven_multilingual_v2` sur le même texte donne un rendu plus neutre ; remplacer le MP3
  et garder le nom de fichier.

## Brancher la clé

1. **Écran Réglages** (recommandé) : coller la clé dans « Clé API ElevenLabs » → Enregistrer
   → « 🔊 Tester la voix » (`Test de voix SAM Vision.`).
2. **`local.properties`** (debug, gitignoré), seules ces lignes comptent :

```properties
tts.api.key=sk_…votre-cle…
tts.voice.id=EXAVITQu4vr4xnSDxMaL
```

Pour parler avec Jade US : `tts.voice.id=TOXPlnYC7z5SQSV2Zgc5` (compte avec crédits).
Sans clé, bouton « 🔊 » affiche **clé ElevenLabs absente — renseigne-la dans Réglages**,
zéro requête réseau (comme le LLM). La clé est chiffrée au repos (Keystore), jamais commitée.

## Coût

Compteur du compte le 24/10 : **321 / 130 005 caractères** consommés (tier `creator`)
au premier relevé, plus les essais de vérification (~200 caractères) et le rendu de
l'intro (~309 caractères, une seule fois). Une lecture coûte ~1 caractère par caractère lu.

## Reste ouvert

- Pas de recette d'écoute sur téléphone pour cette branche (aucun appareil piloté ici) :
  la recette est décrite dans `docs/TESTING.md`.
- La clé du compte n'a pas `voices_read` : le champ voix est un ID libre, pas une liste
  réelle du compte.
- Le découpage en phrases (`SpeechUseCase`) n'est pas encore branché sur la lecture : les
  réponses dépassant 5 000 caractères échoueraient en `400 text_too_long`.