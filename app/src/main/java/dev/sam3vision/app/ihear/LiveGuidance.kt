package dev.sam3vision.app.ihear

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.guidance.CorridorPlanner
import dev.sam3vision.core.guidance.GuidanceAnnouncer
import dev.sam3vision.core.guidance.GuidanceGeometry
import dev.sam3vision.core.guidance.OpeningConcepts
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.toDetections
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Le guidage de la fenêtre caméra : de la frame SAM à **une instruction de marche**.
 *
 * Même matériau que les pastilles (boîtes + distances sténopé), autre usage : la scène
 * est ramenée dans le repère du marcheur ([GuidanceGeometry]), les passages (portes)
 * sont écartés ([OpeningConcepts]), le corridor décide ([CorridorPlanner]), la voix ne
 * parle que si l'instruction est nouvelle ([GuidanceAnnouncer]). Aucun appel réseau de
 * plus, aucun LLM : tout est déterministe et testé en JVM.
 */
internal class LiveGuidance(
    private val planner: CorridorPlanner = CorridorPlanner(),
    private val announcer: GuidanceAnnouncer = GuidanceAnnouncer(),
) {

    /** Nouvelle session : la prochaine instruction sera dite même si elle ressemble. */
    fun reset() = announcer.reset()

    /**
     * Phrase de guidage pour cette frame, ou `null` pour rester muet. [focalPx] est la
     * focale de l'image analysée ; sans elle, pas de repère latéral, donc pas de phrase.
     */
    fun phrase(
        frame: LiveFrame,
        readings: List<DistanceReading>,
        focalPx: Float,
        atMs: Long,
        language: SpeechLanguage,
    ): String? {
        if (frame.width <= 0 || frame.height <= 0 || focalPx <= 0f) return null
        val metersByBox = readings.associate { it.box to it.meters }
        val scene = GuidanceGeometry.scene(
            detections = frame.objects.toDetections(frame.width, frame.height),
            metersByBox = metersByBox,
            focalPx = focalPx,
        ).filterNot { OpeningConcepts.isOpening(it.label) }
        return announcer.onFrame(atMs, planner.plan(scene), language)
    }
}
