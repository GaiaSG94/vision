package dev.sam3vision.app

import android.content.Context
import dev.sam3vision.core.net.ElevenLabsTtsClient
import dev.sam3vision.core.settings.Settings
import java.io.File

/**
 * Voix de l'app : **ElevenLabs uniquement** (pas de moteur TTS système).
 *
 * Synthèse par [ElevenLabsTtsClient] avec la clé des réglages, lecture du MP3 par
 * [SpeechPlayer]. Le texte à lire est passé tel quel : c'est l'appelant qui décide
 * quoi annoncer (bandeau « J'écoute », résultat, erreurs).
 *
 * Un échec (clé absente, 402, réseau) reste silencieux côté audio : l'information
 * est déjà affichée en gros caractères et exposée à TalkBack par le bandeau.
 */
class VoiceAnnouncer(context: Context) {

    private val player = SpeechPlayer(File(context.cacheDir, "voice"))

    /** Synthétise puis lit [text] ; sans effet si le texte ou la clé sont vides. */
    suspend fun say(settings: Settings, text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || settings.ttsApiKey.isBlank()) return
        ElevenLabsTtsClient(settings.ttsApiKey, settings.ttsVoiceId).synthesize(clean).onSuccess { audio ->
            player.play(audio)
        }
    }

    fun stop() = player.stop()

    fun release() = player.release()
}