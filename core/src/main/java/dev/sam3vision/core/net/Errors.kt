package dev.sam3vision.core.net

/**
 * Erreurs réseau typées, avec un message directement affichable dans l'UI.
 * [InvalidKey] = 401, [RateLimited] = 429, [Timeout] = délai dépassé,
 * [Network] = backend injoignable, [Http] = autre code, [BadResponse] = corps illisible.
 */
sealed class ApiError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    class InvalidKey(message: String = "clé invalide (401)") : ApiError(message)

    class RateLimited(message: String = "limite atteinte (429)") : ApiError(message)

    /**
     * 402 : compte sans crédits ou facturation non configurée (Meta ASR), ou voix de
     * bibliothèque ElevenLabs refusée en plan free — prendre alors une voix premade.
     */
    class PaymentRequired(
        message: String = "paiement requis (402) : compte sans crédits ou facturation non " +
            "configurée (voix ElevenLabs de bibliothèque ? prends une premade, ex. Sarah)",
    ) : ApiError(message)

    class Timeout(message: String = "backend injoignable : délai dépassé") : ApiError(message)

    class Network(
        message: String = "backend injoignable : réseau",
        cause: Throwable? = null,
    ) : ApiError(message, cause)

    class Http(val code: Int, val body: String) :
        ApiError("erreur HTTP $code" + if (body.isBlank()) "" else " : ${body.take(160)}")

    class BadResponse(detail: String) : ApiError("réponse inattendue : $detail")

    /** Le moteur principal et son repli ont tous deux échoué (messages déjà rédigés). */
    class AllProviders(detail: String) : ApiError("dictée impossible : $detail")
}
