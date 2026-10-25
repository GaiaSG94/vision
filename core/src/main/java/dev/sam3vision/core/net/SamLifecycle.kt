package dev.sam3vision.core.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Délai court : une extinction est un appel de service, pas un calcul. */
const val RELEASE_TIMEOUT_SECONDS: Long = 10L

/** En-tête exigé par le backend : sans lui, `/release` répond 400 (pas d'extinction par erreur). */
internal const val RELEASE_HEADER = "x-sam-release"

/**
 * Éteint le conteneur GPU (`POST /release`), appelé quand l'app se ferme.
 *
 * Sans ça, la carte A100 reste allumée jusqu'à la fenêtre de réduintage — et tant
 * qu'un client sonde `/health`, elle ne s'éteint jamais. Le backend tue son propre
 * processus après avoir répondu ; `min_containers=0` fait que Modal ne le relance pas,
 * et le prochain réveil repart d'un snapshot restauré (2 à 7 s mesurées).
 *
 * `Result.success(false)` = le serveur a refusé (400) ; `failure` = réseau.
 */
suspend fun releaseSam(
    baseUrl: String,
    client: OkHttpClient = defaultHttpClient(RELEASE_TIMEOUT_SECONDS),
): Result<Boolean> {
    if (baseUrl.isBlank()) return Result.success(false)
    val request = Request.Builder()
        .url(joinUrl(baseUrl, "/release"))
        .header("User-Agent", USER_AGENT)
        .header(RELEASE_HEADER, "confirm")
        .post(EMPTY_JSON.toRequestBody(JSON_MEDIA))
        .build()
    return client.execute(request).map { outcome ->
        outcome.code in 200..299 && parse(outcome.body)?.optBoolean("released", false) == true
    }
}

private const val EMPTY_JSON = "{}"
