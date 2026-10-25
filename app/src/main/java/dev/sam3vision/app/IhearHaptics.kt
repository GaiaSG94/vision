package dev.sam3vision.app

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Haptique iHear : deux motifs **distinguables d'emblée**, comme demandé :
 * - appui long (micro) : une salve franche au démarrage (deux temps, amplitude forte),
 * - balayage vertical : un créneau léger continu tant que le doigt glisse.
 */
class IhearHaptics(context: Context) {

    private val vibrator: Vibrator

    init {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    /** Appui long : salve perceptible unique (deux temps forts séparés d'un creux). */
    fun holdStart() {
        vibrate(
            VibrationEffect.createWaveform(
                longArrayOf(0, HOLD_ON_MS, HOLD_OFF_MS, HOLD_ON_MS),
                intArrayOf(0, HOLD_AMP, 0, HOLD_AMP),
                -1,
            ),
        )
    }

    /** Relâchement : micro-signal de fin, volontairement court. */
    fun holdEnd() {
        vibrate(VibrationEffect.createOneShot(END_ON_MS, END_AMP))
    }

    /**
     * Balayage vertical démarré : créneau léger **répété** (doigt qui glisse) —
     * ressentit nettement différent de la salve de l'appui long.
     */
    fun verticalSwipeStart() {
        if (swiping) return
        swiping = true
        vibrate(
            VibrationEffect.createWaveform(
                longArrayOf(0, SWIPE_ON_MS, SWIPE_OFF_MS),
                intArrayOf(0, SWIPE_AMP),
                1, // répète la boucle « marche/creux » tant que le doigt glisse
            ),
        )
    }

    /** Fin du balayage vertical : créneau arrêté. */
    fun verticalSwipeEnd() {
        if (!swiping) return
        swiping = false
        vibrator.cancel()
    }

    fun cancelAll() {
        swiping = false
        vibrator.cancel()
    }

    private fun vibrate(effect: VibrationEffect) {
        runCatching { vibrator.vibrate(effect) }
    }

    private var swiping = false

    companion object {
        private const val HOLD_ON_MS = 70L
        private const val HOLD_OFF_MS = 90L
        private const val HOLD_AMP = 255
        private const val END_ON_MS = 35L
        private const val END_AMP = 120
        private const val SWIPE_ON_MS = 30L
        private const val SWIPE_OFF_MS = 60L
        private const val SWIPE_AMP = 96
    }
}
