package dev.sam3vision.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.sam3vision.core.gesture.LongPressTrigger

/**
 * Racine de la fenêtre : seule vue qui voit **tous** les gestes de la surface de
 * l'app, quel que soit l'enfant touché (bouton, image, zone vide).
 *
 * Fonctionnement : `onInterceptTouchEvent` alimente un [LongPressTrigger]. Quand le
 * seuil de 300 ms est atteint sans glissement, la vue prend la main (`capturing`) :
 * les enfants reçoivent un ACTION_CANCEL, l'enregistrement démarre, et le premier
 * relâchement (ACTION_UP / ACTION_CANCEL) arrête la dictée.
 *
 * Ce qui n'est **jamais** traité : tout contact qui commence dans la zone système
 * du bas (barre de navigation à 3 boutons ou zone de gestes), en plus des touches
 * qui n'atteignent jamais la fenêtre de l'app. Rien n'est intercepté hors de cette
 * surface : TalkBack et les gestes système gardent la priorité.
 */
class LongPressRootLayout(context: Context) : FrameLayout(context) {

    var onHoldStart: (() -> Unit)? = null
    var onHoldEnd: (() -> Unit)? = null

    /** Hauteur de la zone système du bas en pixels (0 = pas d'exclusion). */
    var systemBottomPx: () -> Int = { bottomSystemInset(this) }

    private val handler = Handler(Looper.getMainLooper())
    private var capturing = false

    private val trigger = LongPressTrigger(
        scheduler = LongPressTrigger.DelayScheduler(::scheduleDelayed),
        listener = object : LongPressTrigger.Listener {
            override fun onHoldStart() {
                capturing = true
                onHoldStart?.invoke()
            }

            override fun onHoldEnd() {
                capturing = false
                onHoldEnd?.invoke()
            }
        },
    )

    /** Reporte [action] sur le thread principal ; rend l'annulation. */
    private fun scheduleDelayed(delayMs: Long, action: () -> Unit): () -> Unit {
        val runnable = Runnable { action() }
        handler.postDelayed(runnable, delayMs)
        return {
            handler.removeCallbacks(runnable)
            Unit
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // En capture, les événements sont traités par onTouchEvent (pas deux fois).
        if (!capturing) feed(ev)
        return capturing
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!capturing) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> trigger.onUp()
            MotionEvent.ACTION_POINTER_DOWN -> trigger.onPointerDown()
            else -> Unit
        }
        return true
    }

    /** Tous les gestes de la surface passent ici avant de descendre aux enfants. */
    private fun feed(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                if (inSystemZone(ev)) trigger.reset() else trigger.onDown(ev.x, ev.y)

            MotionEvent.ACTION_MOVE -> trigger.onMove(ev.x, ev.y)
            MotionEvent.ACTION_POINTER_DOWN -> trigger.onPointerDown()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> trigger.onUp()
        }
    }

    private fun inSystemZone(ev: MotionEvent): Boolean {
        val inset = systemBottomPx()
        return inset > 0 && ev.y >= height - inset
    }

    override fun onDetachedFromWindow() {
        trigger.reset()
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    private fun bottomSystemInset(view: View): Int {
        val insets = ViewCompat.getRootWindowInsets(view) ?: return 0
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val gestures = insets.getInsets(WindowInsetsCompat.Type.systemGestures())
        return maxOf(bars.bottom, gestures.bottom)
    }
}