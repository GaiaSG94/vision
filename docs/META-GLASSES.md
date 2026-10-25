# Lunettes Ray-Ban Meta — activer SAM Vision

> Vérifié sur les docs Meta du 20/10/2026 et sur les AAR `com.meta.wearable:mwdat-*:1.0.1`
> (décompilés pour relever les signatures exactes). Branche : `feat/meta-glasses`.
> Revu le 25/10/2026 après la fusion de `master` (refonte iHear, mode Direct) : les chemins de
> fichiers cités plus bas sont ceux du dépôt fusionné.

## Ce que ça fait

Les lunettes ne font tourner aucun code : c'est **l'app Android qui reste le cerveau**.
Le Device Access Toolkit (DAT) relie l'app au matériel via Bluetooth Classic.

- Lunettes → **photo** (bouton capture) → app → SAM 3.1 → description lue par le TTS.
- **Enregistrement** de l'app auprès de l'app Meta AI, géré par le SDK.
- **Mode dégradé** : tout le reste de l'app fonctionne sans lunettes (voir plus bas).

Ce que ça ne fait **pas** (Ray-Ban Meta = pas d'écran) : aucun overlay de masques, rien
d'affiché sur le verre, pas de mode Direct sur les lunettes (il existe dans l'app, sur le
téléphone, mais n'y est pas porté), pas de publication publique (developer preview Meta).

## Où atterrit la photo dans l'app

Depuis la fusion de `master`, l'accueil n'est plus l'écran photo : c'est **iHear**, le chat
plein écran avec une fenêtre caméra flottante. Les lunettes n'ont pas d'écran dédié ; leur
photo suit donc **le même chemin que la capture téléphone** : `app/AppNav.kt` (`GlassesBridge`)
appelle `AppViewModel.onPhoto`, puis ouvre l'analyse ou le chat selon le geste.

Le point d'entrée reste **Réglages → bloc « Lunettes Ray-Ban Meta »** (enregistrement, session,
capture, lunettes simulées en build debug), accessible depuis l'accueil iHear.

## Prérequis, une fois

| Élément | Minimum | Où le vérifier |
|---|---|---|
| Téléphone | Android 10+ (API 29) | — |
| App Meta AI | **v292** | Settings → App Info |
| Firmware lunettes | **v128** | Devices → réglages → General → About → Version |
| Developer Mode | activé | Settings → App Info → taper 5× sur le numéro de version → Enable |

**Ce que ça change pour les téléphones** : `minSdk` passe de 26 à **29**, parce que les trois
AAR `mwdat-*` déclarent `android:minSdkVersion="29"`. L'app exige donc **Android 10 ou plus** :
un appareil en Android 8 ou 9 (API 26 à 28) ne peut plus installer l'APK
(`INSTALL_FAILED_OLDER_SDK`). Ce plancher vient du SDK Meta, pas de la paire elle-même : même
sans lunettes appairées, l'APK reste en API 29 minimum.

**Piège v128** : après la mise à jour OTA en v128, la capture caméra ne fonctionne pas
tant que les lunettes n'ont pas redémarré (bug connu Meta). Redémarre-les avant de conclure
que quelque chose est cassé.

Aucun compte partenaire, aucun projet Developer Center, aucune clé : le manifeste déclare
`APPLICATION_ID = 0` et `CLIENT_TOKEN = 0`, ce qui est le mode prévu par Meta pour les
builds en Developer Mode (l'attestation est désactivée).

## Activer, dans l'ordre

1. Appairer les lunettes dans l'app Meta AI, et garder l'app Meta AI ouverte pendant
   l'enregistrement.
2. Dans SAM Vision : **Réglages → Lunettes Ray-Ban Meta → Enregistrer auprès de Meta AI**.
3. Dans l'app Meta AI : accepter la demande. L'app apparaît dans
   *Settings → App connections → Developer mode apps*.
4. Toujours dans Meta AI : **autoriser la caméra en « Always allow »** pour SAM Vision
   (sinon la permission est redemandée à chaque session).
5. Revenir dans SAM Vision : **Démarrer la session**, puis **Prendre une photo**.

En cas de doute, les lignes d'état affichent SDK, enregistrement, appareil (nom, batterie,
port), session, permission caméra et flux.

## Gestes

| Geste | Effet | Remarque |
|---|---|---|
| **Appui court** sur le bouton capture | photo → analyse | segmentation SAM puis description, comme une photo prise à la main |
| **Appui long** (`HOLD`) | photo → **chat** | même photo, mais la question se pose sur le téléphone |
| **Double appui** | mode Direct | **pas porté sur les lunettes** : message d'explication, rien ne démarre |
| Tap / tap-long sur le pavé | *rien* | **réservés par le système** : pause/reprise et arrêt de la session |

Le double appui n'est pas un trou : le mode Direct existe dans l'app (Réglages → Autres écrans →
« Mode Direct (vidéo en continu) »), il tourne sur le téléphone. Sur les lunettes, `GlassesController.onGesture`
affiche exactement : « Mode Direct : pas encore porté sur lunettes, il reste sur le téléphone. »

Le SDK beta émet `SHORT_PRESS` **puis** `DOUBLE_PRESS` pour un double appui. Le buffer
`CaptureGestureBuffer` (dans `:core`, testé en JVM) retient donc un appui court pendant
350 ms et le jette si un double appui arrive. C'est lui qui décide : `HOLD` → `ASK`,
`DOUBLE` → `LIVE`, `SHORT` → `PHOTO` après le délai.

## La voix : trois voies, dans l'ordre du plus utile

1. **Bouton capture** — c'est ce qui est branché. Zéro configuration Meta, marche toujours.
2. **Ton propre wake word** (non branché) — la `StreamConfiguration` accepte un
   `audioCodec = AudioCodec.PCM(AudioSampleRate.RATE_16000, 1)` : le micro des lunettes
   arrive **dans le même flux que la vidéo**, sans passer en HFP. Conséquence utile :
   la sortie reste en **A2DP** (44,1/48 kHz stéréo), donc le TTS ElevenLabs garde sa
   qualité. En HFP à la place, on gagne le beamforming sur la voix du porteur mais la
   sortie tombe en mono 16 kHz (les deux profils sont exclusifs).
   Attention : l'audio in-stream est une capacité **beta** (dev/beta channels seulement).
3. **« Hey Meta, start SAM Vision »** — le système cold-launch l'app et pousse un
   `VoiceInvocationsStream`. Ça exige un projet dans le **Wearables Developer Center** :
   ajouter la config mobile (nom de package `dev.sam3vision.app`), donner un **nom d'app
   = mot-clé vocal**, puis **créer une version** (c'est la version qui porte la permission
   *Voice Invocation*). Gratuit et sans revue, mais un cran au-dessus du 0/0 actuel.
   Si la phrase ne résout pas, le bouton capture reste le plan A.

## Sans lunettes, l'app reste complète

Le bloc Lunettes est une **option** : aucun écran ni aucune fonction du téléphone n'en dépend.
Sans paire appairée, tout le reste marche (c'est le mode dégradé) :

| Fonction | Où | Doc |
|---|---|---|
| Direct : flux vidéo → LLM/SAM, masques, distances, narration continue | Réglages → Autres écrans → « Mode Direct (vidéo en continu) » | `docs/DISTANCES.md` |
| Voix ElevenLabs (annonces de position, lecture des réponses) | Réglages → section Voix | `docs/ELEVENLABS-TTS.md` |
| Dictée Meta (Muse), appui long 300 ms n'importe où | chat / n'importe quel écran | `docs/META-ASR.md` |
| Nettoyage des captures (Room + WorkManager) | silencieux, en arrière-plan | `docs/NETTOYAGE.md` |

## Démo sans matériel

En build **debug**, le bloc Lunettes propose **Simuler des lunettes** : Mock Device Kit
appaire une paire simulée (`GlassesModel.RAYBAN_META`), alimente le flux avec une scène
synthétique (`mock-scene.jpg`, formes franches) et accepte un appui injecté
(`services.input.capture(SHORT_PRESS)`). Le chemin de code est le même qu'avec du vrai
matériel : c'est le filet de sécurité du jour J.

## Fichiers

| Fichier | Rôle |
|---|---|
| `core/src/main/java/dev/sam3vision/core/glasses/CaptureGesture.kt` | buffer d'appuis, logique pure + tests JVM |
| `app/src/main/java/dev/sam3vision/app/glasses/GlassesController.kt` | enregistrement, session, permissions, état, routage des gestes |
| `app/src/main/java/dev/sam3vision/app/glasses/GlassesCamera.kt` | flux caméra (15 fps, MEDIUM) et `capture()` → `stream.capturePhoto()` |
| `app/src/main/java/dev/sam3vision/app/glasses/GlassesInputs.kt` | bouton capture → actions (seul `CAPTURE_BUTTON` est écouté) |
| `app/src/main/java/dev/sam3vision/app/glasses/GlassesFrames.kt` | frame/photo → Bitmap → JPEG du cache (`writeJpegToCache`) |
| `app/src/main/java/dev/sam3vision/app/glasses/GlassesState.kt` | `GlassesUiState` / `GlassesEvent` affichés |
| `app/src/main/java/dev/sam3vision/app/glasses/GlassesMock.kt` | Mock Device Kit + scène synthétique |
| `app/src/main/java/dev/sam3vision/app/glasses/GlassesSection.kt` | bloc « Lunettes » des Réglages |
| `app/src/main/java/dev/sam3vision/app/AppNav.kt` | `GlassesBridge` : photo des lunettes → `onPhoto`, puis analyse ou chat |

## Passer en production plus tard

1. Créer le projet dans le Wearables Developer Center, récupérer *Application ID* et
   *Client Token*, justifier l'accès caméra/micro (revue interne Meta).
2. Les mettre dans `local.properties` (`mwdat.application.id`, `mwdat.client.token`) —
   jamais commités : le manifeste les lit via `manifestPlaceholders`.
3. Créer une version, l'assigner au canal privé **Beta**, inviter les testeurs par e-mail
   (ils doivent avoir un compte Meta, un Managed Meta Account n'est pas accepté).
4. À savoir : une seule app tierce enregistrée à la fois en Developer Mode, et
   l'attestation n'est pas utilisée dans ce mode.