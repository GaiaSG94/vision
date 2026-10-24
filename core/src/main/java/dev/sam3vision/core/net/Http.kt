package dev.sam3vision.core.net

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request

/** User-Agent identifiable côté backend. */
internal const val USER_AGENT = "sam3-vision-android/1.0"

internal val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/** Délai par défaut imposé par la spec : 60 s. */
const val DEFAULT_TIMEOUT_SECONDS: Long = 60L

/** Un seul endroit qui fabrique le client HTTP (timeouts identiques pour LLM et SAM). */
fun defaultHttpClient(timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

/** Réponse HTTP brute. */
internal data class HttpOutcome(val code: Int, val body: String)

internal fun joinUrl(baseUrl: String, path: String): String =
    baseUrl.trim().trimEnd('/') + path

/** Exécute une requête hors du thread principal et normalise toutes les erreurs. */
internal suspend fun OkHttpClient.execute(request: Request): Result<HttpOutcome> =
    withContext(Dispatchers.IO) {
        try {
            val response = newCall(request).execute()
            response.use { Result.success(HttpOutcome(it.code, it.body?.string().orEmpty())) }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Result.failure(e.toApiError())
        }
    }

internal fun Throwable.toApiError(): ApiError = when (this) {
    is ApiError -> this
    is SocketTimeoutException -> ApiError.Timeout()
    is InterruptedIOException -> ApiError.Timeout()
    is IOException -> ApiError.Network(cause = this)
    else -> ApiError.Network(message = message ?: "backend injoignable", cause = this)
}

/** 401/429 ont des messages dédiés, le reste tombe dans [ApiError.Http]. */
internal fun httpError(code: Int, body: String): ApiError = when (code) {
    401, 403 -> ApiError.InvalidKey(if (code == 403) "accès refusé (403)" else "clé invalide (401)")
    429 -> ApiError.RateLimited()
    else -> ApiError.Http(code, body)
}

/** Vérifie un code 2xx, sinon renvoie l'erreur typée correspondante. */
internal fun HttpOutcome.requireSuccess(): Result<String> =
    if (code in 200..299) Result.success(body) else Result.failure(httpError(code, body))

/** Enveloppe un bloc qui lève [ApiError] (ou autre) en `Result`. */
internal inline fun <R> catchingResult(block: () -> R): Result<R> =
    try {
        Result.success(block())
    } catch (c: CancellationException) {
        throw c
    } catch (e: ApiError) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(ApiError.BadResponse(e.message ?: "réponse illisible"))
    }


/** Chaînon : garde l'erreur de gauche, enchaîne une opération qui renvoie déjà un Result. */
internal inline fun <T, R> Result<T>.then(block: (T) -> Result<R>): Result<R> =
    fold(onSuccess = { block(it) }, onFailure = { Result.failure(it) })
