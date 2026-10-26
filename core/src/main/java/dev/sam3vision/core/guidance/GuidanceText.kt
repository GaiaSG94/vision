package dev.sam3vision.core.guidance

import dev.sam3vision.core.distance.DistanceText
import dev.sam3vision.core.speech.SpeechLanguage
import kotlin.math.roundToInt

/**
 * Mise en mots de la décision de guidage — courte, impérative, une action par phrase.
 *
 * Formes produites (fr) :
 * - `Tout droit, c'est libre.`
 * - `Chaise devant, à 2,1 mètres. Décale à gauche de 60 centimètres.`
 * - `Personne devant, à 1,4 mètre. Passe à droite.` (quand le côté est déjà libre)
 * - `Stop. Chaise à 1 mètre.`
 *
 * Les distances passent par [DistanceText.spoken] : mêmes mots que le reste de la voix,
 * singulier français jusqu'à deux mètres compris. Tout est pur et testé en JVM.
 */
object GuidanceText {

    /** Phrase à dire, ou `null` pour rester muet ([Guidance.Unknown]). */
    fun say(guidance: Guidance, language: SpeechLanguage = SpeechLanguage.Fr): String? =
        when (guidance) {
            Guidance.Unknown -> null
            Guidance.Clear -> clear(language)
            is Guidance.Pass -> pass(guidance, language)
            is Guidance.Halt -> halt(guidance, language)
        }

    /**
     * Signature de l'instruction : deux décisions de même signature n'ont rien de neuf à
     * dire. La distance est arrondie (0,5 m) et le décalage aussi (25 cm), pour qu'un
     * objet qui s'approche soit ré-annoncé sans bavarder à chaque frame.
     */
    fun key(guidance: Guidance): String? = when (guidance) {
        Guidance.Unknown -> null
        Guidance.Clear -> "clear"
        is Guidance.Pass -> "pass|${guidance.label.trim().lowercase()}|${guidance.side.name}|" +
            "${bucket(guidance.meters, HALF_METER)}|${bucket(guidance.shiftMeters, QUARTER_METER)}"
        is Guidance.Halt -> "halt|${guidance.label.trim().lowercase()}|${bucket(guidance.meters, HALF_METER)}"
    }

    /** « Tout droit, c'est libre. » */
    private fun clear(language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> "Tout droit, c'est libre."
        SpeechLanguage.En -> "Straight ahead is clear."
    }

    /** « Chaise devant, à 2,1 mètres. Décale à gauche de 60 centimètres. » */
    private fun pass(guidance: Guidance.Pass, language: SpeechLanguage): String {
        val head = if (language == SpeechLanguage.Fr) {
            "${label(guidance.label)} devant, à ${DistanceText.spoken(guidance.meters, language)}."
        } else {
            "${label(guidance.label)} ahead, ${DistanceText.spoken(guidance.meters, language)}."
        }
        return "$head ${move(guidance.side, shiftCentimeters(guidance.shiftMeters), language)}"
    }

    /** L'instruction de déplacement : un pas chiffré, ou un simple côté. */
    private fun move(side: PassSide, centimeters: Int, language: SpeechLanguage): String {
        val left = side == PassSide.Left
        val small = centimeters <= SMALL_SHIFT_CENTIMETERS
        return when {
            language == SpeechLanguage.Fr && small -> if (left) "Passe à gauche." else "Passe à droite."
            language == SpeechLanguage.Fr -> if (left) {
                "Décale à gauche de $centimeters centimètres."
            } else {
                "Décale à droite de $centimeters centimètres."
            }
            small -> if (left) "Keep left." else "Keep right."
            left -> "Shift left by $centimeters centimeters."
            else -> "Shift right by $centimeters centimeters."
        }
    }

    /** « Stop. Chaise à 1 mètre. » */
    private fun halt(guidance: Guidance.Halt, language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> "Stop. ${label(guidance.label)} à ${DistanceText.spoken(guidance.meters, language)}."
        SpeechLanguage.En -> "Stop. ${label(guidance.label)} ${DistanceText.spoken(guidance.meters, language)} ahead."
    }

    /** Décalage arrondi à 10 cm, jamais nul : « 60 centimètres ». */
    private fun shiftCentimeters(meters: Float): Int =
        ((meters * 100f) / 10f).roundToInt().coerceAtLeast(1) * 10

    /** Label en tête de phrase, première lettre en capitale. */
    private fun label(raw: String): String =
        raw.trim().ifEmpty { "Objet" }.replaceFirstChar { it.uppercaseChar() }

    /** Valeur arrondie au pas [step], pour la signature d'instruction. */
    private fun bucket(value: Float, step: Float): Int =
        if (!value.isFinite()) 0 else (value / step).roundToInt()

    /** Pas d'arrondi de la signature : 50 cm pour la distance, 25 cm pour le décalage. */
    private const val HALF_METER = 0.5f
    private const val QUARTER_METER = 0.25f

    /** Sous 15 cm, on ne chiffre pas le pas. */
    private const val SMALL_SHIFT_CENTIMETERS = 15
}
