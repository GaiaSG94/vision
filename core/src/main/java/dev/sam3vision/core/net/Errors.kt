package dev.sam3vision.core.net

/**
 * Erreurs réseau typées, avec un message directement affichable dans l'UI.
 * [InvalidKey] = 401, [RateLimited] = 429, [Timeout] = délai dépassé,
 * [Network] = backend injoignable, [Http] = autre code, [BadResponse] = corps illisible.
 */
sealed class ApiError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    class InvalidKey(message: String = "clé invalide (401)") : ApiError(message)

    class RateLimited(message: String = "limite atteinte (429)") : ApiError(message)

    /** ElevenLabs refuse la voix tant que le compte n'a pas de crédits (plan free). */
    class PaymentRequired(
        message: String = "voix ElevenLabs refusée (402) : compte sans crédits — " +
            "ajoute 5 \$ de crédits ou passe au plan Creator",
    ) : ApiError(message)

    class Timeout(message: String = "backend injoignable : délai dépassé") : ApiError(message)

    class Network(
        message: String = "backend injoignable : réseau",
        cause: Throwable? = null,
    ) : ApiError(message, cause)

    class Http(val code: Int, val body: String) :
        ApiError("erreur HTTP $code" + if (body.isBlank()) "" else " : ${body.take(160)}")

    class BadResponse(detail: String) : ApiError("réponse inattendue : $detail")
}
