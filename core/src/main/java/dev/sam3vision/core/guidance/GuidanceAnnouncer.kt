package dev.sam3vision.core.guidance

import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Déclencheur de la voix de guidage, calqué sur la réalité du direct : 1 à 3 frames par
 * seconde, des boîtes qui tremblent, et une phrase ElevenLabs qui prend ~1 s à sortir.
 *
 * Quatre garde-fous, dans cet ordre :
 * 1. **silence** — [Guidance.Unknown] ne dit rien et remet la stabilité à zéro : on ne
 *    promet jamais « c'est libre » sans avoir mesuré quelque chose ;
 * 2. **stabilité** — une instruction doit être vue [stableFrames] fois de suite, sauf
 *    l'arrêt ([Guidance.Halt]) qui part **dès la première frame** : c'est le seul cas où
 *    attendre coûte un pas de trop ;
 * 3. **nouveauté** — une instruction déjà dite ([GuidanceText.key]) reste muette ;
 * 4. **délai** — jamais deux instructions à moins de [minIntervalMs], sauf l'arrêt qui
 *    peut se répéter toutes les [haltIntervalMs] tant que l'obstacle est là.
 *
 * Pur et testé en JVM : aucune horloge implicite, [atMs] est fourni.
 */
class GuidanceAnnouncer(
    private val stableFrames: Int = DEFAULT_STABLE_FRAMES,
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val haltIntervalMs: Long = DEFAULT_HALT_INTERVAL_MS,
) {

    private var pendingKey: String? = null
    private var pendingCount: Int = 0
    private var spokenKey: String? = null
    private var lastSpokenAtMs: Long? = null

    /** Dernière phrase produite (`null` tant que rien n'a été dit). */
    var lastPhrase: String? = null
        private set

    /**
     * Frame observée à [atMs] : la phrase à prononcer, ou `null` s'il faut se taire.
     * [language] est la langue de la voix (Réglages) : elle ne change ni les seuils ni la
     * mémoire d'instruction.
     */
    fun onFrame(
        atMs: Long,
        guidance: Guidance,
        language: SpeechLanguage = SpeechLanguage.Fr,
    ): String? {
        val key = GuidanceText.key(guidance) ?: return silence()
        val halt = guidance is Guidance.Halt
        advance(key)
        val needed = if (halt) 1 else stableFrames
        if (pendingCount < needed) return null
        if (!due(key, halt, atMs)) return null
        val phrase = GuidanceText.say(guidance, language) ?: return null
        spokenKey = key
        lastSpokenAtMs = atMs
        lastPhrase = phrase
        return phrase
    }

    /** Nouvelle session : l'instruction suivante sera dite même si elle ressemble. */
    fun reset() {
        pendingKey = null
        pendingCount = 0
        spokenKey = null
        lastSpokenAtMs = null
        lastPhrase = null
    }

    /** Faut-il parler maintenant ? Nouveauté d'abord, délai ensuite. */
    private fun due(key: String, halt: Boolean, atMs: Long): Boolean {
        val elapsed = elapsedSince(atMs)
        if (key == spokenKey) return halt && elapsed >= haltIntervalMs
        return halt || elapsed >= minIntervalMs
    }

    /** Délai écoulé depuis la dernière phrase ; « infiniment » avant la première. */
    private fun elapsedSince(atMs: Long): Long = lastSpokenAtMs?.let { atMs - it } ?: Long.MAX_VALUE

    /**
     * Rien de mesuré : on se tait, la stabilité repart de zéro et l'instruction oubliée —
     * sinon un objet qui revient après une frame muette ne serait jamais ré-annoncé.
     */
    private fun silence(): String? {
        pendingKey = null
        pendingCount = 0
        spokenKey = null
        return null
    }

    /** Compteur de stabilité : même instruction deux frames de suite. */
    private fun advance(key: String) {
        if (key == pendingKey) {
            pendingCount += 1
        } else {
            pendingKey = key
            pendingCount = 1
        }
    }

    companion object {
        /** Frames consécutives avant de parler (≈ 1 s à 1,5-2 fps). */
        const val DEFAULT_STABLE_FRAMES = 2

        /** Délai minimal entre deux instructions (le temps d'en dire une). */
        const val DEFAULT_MIN_INTERVAL_MS = 1_500L

        /** Répétition d'un « stop » tant que l'obstacle est là. */
        const val DEFAULT_HALT_INTERVAL_MS = 2_000L
    }
}
