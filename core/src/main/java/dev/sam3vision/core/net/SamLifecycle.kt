package dev.sam3vision.core.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Délai de l'extinction : long, parce que la requête peut arriver sur un conteneur
 * endormi — elle attend alors son démarrage (2 à 7 s avec le snapshot, ~24 s la première
 * fois). Un délai court échouerait au pire moment, celui où l'app se ferme.
 */
const val RELEASE_TIMEOUT_SECONDS: Long = 45L

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
): Result<ReleaseOutcome> {
    if (baseUrl.isBlank()) return Result.success(ReleaseOutcome(false, 0, "base vide"))
    val request = Request.Builder()
        .url(joinUrl(baseUrl, "/release"))
        .header("User-Agent", USER_AGENT)
        .header(RELEASE_HEADER, "confirm")
        .post(EMPTY_JSON.toRequestBody(JSON_MEDIA))
        .build()
    val once = client.execute(request).map { outcome -> outcome.toRelease() }
    // Un conteneur qui démarre au moment exact de l'appel peut répondre 500 : on retient.
    if (once.getOrNull()?.released == true) return once
    return client.execute(request).map { outcome -> outcome.toRelease() }
}

/** Réponse de `/release` : le verdict, le code HTTP et le corps (pour le diagnostic). */
data class ReleaseOutcome(val released: Boolean, val code: Int, val body: String)

private fun HttpOutcome.toRelease(): ReleaseOutcome = ReleaseOutcome(
    released = code in 200..299 && parse(body)?.optBoolean("released", false) == true,
    code = code,
    body = body.take(200),
)

private const val EMPTY_JSON = "{}"
