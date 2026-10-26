package dev.sam3vision.app.ihear

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.core.net.ElevenLabsTtsClient
import dev.sam3vision.core.settings.Settings

/**
 * Voix de proximité de la fenêtre caméra : dit chaque instruction de [LiveProbe]
 * (« Chaise devant, à 2 mètres. Décale à gauche de 50 centimètres. »).
 *
 * Le déclenchement — stabilité, nouveauté, délais — est décidé en amont par le guidage
 * (`GuidanceAnnouncer`) : ici on ne filtre **plus** les répétitions, sinon un « stop »
 * répété toutes les deux secondes ne s'entendrait jamais. Rien n'est dit sans clé
 * ElevenLabs, et jamais par-dessus la réponse du chat (une seule source sonore).
 */
class ProximityVoice(private val player: SpeechPlayer, private val settings: () -> Settings) {

    /**
     * Nouvelle instruction de SAM. [playerIdle] vrai = aucune bulle du chat en cours de
     * lecture ; sinon on ne dit rien (et l'instruction est perdue : elle est déjà datée).
     */
    suspend fun onPhrase(phrase: String, playerIdle: Boolean) {
        val current = settings()
        if (!playerIdle || !current.ttsConfigured) return
        val bytes = ElevenLabsTtsClient(current.ttsApiKey, current.ttsVoiceId)
            .synthesize(phrase)
            .getOrElse { return }
        if (bytes.isNotEmpty()) player.play(bytes)
    }
}