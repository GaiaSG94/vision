package dev.sam3vision.core.live

import dev.sam3vision.core.model.SeenTrack
import dev.sam3vision.core.model.TimelineSummary
import java.util.Locale

/**
 * Mise en forme du journal caméra pour le LLM et pour l'UI.
 *
 * Les temps sont **relatifs** (« il y a 3 s ») : le modèle n'a pas l'horloge,
 * et l'utilisateur non plus. Les positions sont des repères simples
 * (« à gauche », « au centre », « à droite »).
 */
object TimelineText {

    /** Contexte complet transmis au LLM : la fenêtre globale + le point focal demandé. */
    fun context(global: TimelineSummary, focus: TimelineSummary? = null, focusLabel: String? = null): String {
        val parts = mutableListOf<String>()
        parts += describe(global, "Ce que la caméra a vu récemment")
        if (focus != null && !focus.empty) {
            parts += describe(focus, focusLabel ?: "Au moment demandé")
        }
        return parts.joinToString("\n\n")
    }

    /** Une ligne d'en-tête + une ligne par objet suivi. */
    fun describe(summary: TimelineSummary, title: String = "Journal caméra"): String {
        val header = "$title (de ${ago(summary.toMs, summary.fromMs)} à maintenant, " +
            "${summary.tracks.size} objet(s)) :"
        if (summary.empty) return "$header\n- rien de visible"
        return (listOf(header) + summary.tracks.map { trackLine(summary.toMs, it) }).joinToString("\n")
    }

    /** Une ligne par objet : position, dernière vue, apparition, occurrences. */
    fun trackLine(nowMs: Long, track: SeenTrack): String = buildString {
        append("- « ").append(track.label).append(" » (suivi n°").append(track.id).append(") : ")
        append(position(track))
        append(", vue la dernière fois ").append(ago(nowMs, track.lastAtMs))
        append(", apparue ").append(ago(nowMs, track.firstAtMs))
        append(", présente sur ").append(track.frames).append(" image(s)")
    }

    /** Étiquette compacte pour l'UI : « personne · vu il y a 3 s ». */
    fun chipLabel(nowMs: Long, track: SeenTrack): String =
        "${track.label} · ${ago(nowMs, track.lastAtMs)}"

    /** Repère de position lisible : « en haut à gauche », « au centre », « à droite ». */
    fun position(track: SeenTrack): String {
        val horizontal = when {
            track.centerX < 0.34f -> "à gauche"
            track.centerX > 0.66f -> "à droite"
            else -> "au centre"
        }
        val vertical = when {
            track.centerY < 0.34f -> "en haut"
            track.centerY > 0.66f -> "en bas"
            else -> null
        }
        return listOfNotNull(vertical, horizontal).joinToString(" ")
    }

    /** Durée relative : « il y a 3 s », « il y a 1 min 05 s », « à l'instant ». */
    fun ago(nowMs: Long, atMs: Long): String {
        val delta = (nowMs - atMs).coerceAtLeast(0L)
        if (delta < 1_000L) return "à l'instant"
        val seconds = delta / 1_000L
        if (seconds < 60L) return "il y a $seconds s"
        val minutes = seconds / 60L
        val rest = seconds % 60L
        return "il y a $minutes min ${String.format(Locale.FRANCE, "%02d", rest)} s"
    }
}
