package dev.sam3vision.app

import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lit l'audio MP3 renvoyé par ElevenLabs : fichier temporaire dans le cache privé de
 * l'app, puis `MediaPlayer`. Aucune dépendance ajoutée (pas d'ExoPlayer) et aucun
 * accès réseau ici — les octets sont déjà téléchargés par le client `:core`.
 *
 * Deux usages, un seul moteur de lecture :
 * - [play] : phrase du chat/de l'analyse, écrite dans `voice.mp3` (le cache est vidé) ;
 * - [playFile] : clip déjà présent sur disque (mots du mode tap), aucun vidage — les
 *   clips « Launched »/« Sent »/« Failed » persistent donc entre deux lectures.
 *
 * Une seule lecture à la fois : chaque entrée arrête la précédente.
 *
 * Un message **de la personne** (enregistrement micro) est amplifié à la lecture
 * ([play] / [playFile] avec `boostMb`) : les MP3 d'ElevenLabs sont normalisés et calés
 * pour le volume système, un enregistrement au téléphone l'est beaucoup moins, et on
 * passait d'un message audible à un message qu'on n'entendait pas.
 */
class SpeechPlayer(
    private val dir: File,
    private val onComplete: () -> Unit = {},
    private val onPlaybackError: (String) -> Unit = {},
) {
    private var player: MediaPlayer? = null

    /** Amplificateur de la lecture en cours (null : gain unitaire, le cas de l'IA). */
    private var enhancer: LoudnessEnhancer? = null

    /** Joue [bytes] ; renvoie l'échec au lieu de le lever (l'UI affiche le message). */
    suspend fun play(bytes: ByteArray, boostMb: Int = 0): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { playBlocking(writeCache(bytes), boostMb) }
    }

    /**
     * Joue un fichier déjà écrit dans le stockage privé (clip pré-généré). Aucun vidage
     * du répertoire : [dir] peut contenir les trois clips du mode tap.
     *
     * [boostMb] est un gain en millibels appliqué à la session audio (0 = inchangé,
     * [USER_AUDIO_BOOST_MB] pour un enregistrement de la personne). `LoudnessEnhancer`
     * amplifie **au-delà** de l'unité, ce que `MediaPlayer.setVolume` ne sait pas faire,
     * avec son propre limiteur pour ne pas saturer.
     */
    suspend fun playFile(file: File, boostMb: Int = 0): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { playBlocking(file, boostMb) }
    }

    /** Corps commun : arrêt de la lecture précédente, préparation, démarrage, gain. */
    private fun playBlocking(file: File, boostMb: Int) {
        stop()
        val mp = MediaPlayer()
        player = mp
        mp.setDataSource(file.absolutePath)
        mp.setOnCompletionListener { onComplete() }
        mp.setOnErrorListener { _, what, extra ->
            onPlaybackError("lecture audio impossible ($what/$extra)")
            true
        }
        mp.prepare()
        mp.start()
        // Après `start()` : la session audio est alors toujours attribuée (`audioSessionId`).
        amplify(mp, boostMb)
    }

    /** Attache un gain à la session audio ; un échec laisse la lecture à volume normal. */
    private fun amplify(mp: MediaPlayer, boostMb: Int) {
        if (boostMb <= 0) return
        runCatching {
            LoudnessEnhancer(mp.audioSessionId).apply {
                setTargetGain(boostMb)
                enabled = true
            }
        }.onSuccess { enhancer = it }
    }

    /** Arrête et libère la lecture en cours. */
    fun stop() {
        enhancer?.let { runCatching { it.release() } }
        enhancer = null
        player?.let { mp ->
            runCatching { if (mp.isPlaying) mp.stop() }
            runCatching { mp.reset() }
            runCatching { mp.release() }
        }
        player = null
    }

    /** Libération finale (onCleared du ViewModel). */
    fun release() = stop()

    companion object {
        /**
         * Gain des messages de la personne (+8 dB). Choisi pour combler l'écart constaté
         * avec les MP3 ElevenLabs sans rendre une voix déjà forte désagréable : le
         * limiteur de `LoudnessEnhancer` s'occupe des pics.
         */
        const val USER_AUDIO_BOOST_MB = 800
    }

    /** Une seule prise dans le cache : l'ancienne est supprimée avant d'écrire la neuve. */
    private fun writeCache(bytes: ByteArray): File {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        return File(dir, "voice.mp3").apply { writeBytes(bytes) }
    }
}