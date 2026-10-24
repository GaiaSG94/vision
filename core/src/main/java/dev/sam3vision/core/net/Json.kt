package dev.sam3vision.core.net

import org.json.JSONArray
import org.json.JSONObject

/** Accès tolérants au JSON : jamais d'exception, valeurs par défaut sinon. */
internal fun JSONObject.str(key: String, def: String = ""): String =
    if (!has(key) || isNull(key)) def else optString(key, def)

internal fun JSONObject.intOr(key: String, def: Int = 0): Int =
    if (!has(key) || isNull(key)) def else optInt(key, def)

internal fun JSONObject.floatOr(key: String, def: Float = 0f): Float =
    if (!has(key) || isNull(key)) def else optDouble(key, def.toDouble()).toFloat()

internal fun JSONObject.floats(key: String): List<Float> {
    val arr = optJSONArray(key) ?: return emptyList()
    return (0 until arr.length()).map { i -> arr.optDouble(i, 0.0).toFloat() }
}

internal fun JSONObject.strings(key: String): List<String> {
    val arr = optJSONArray(key) ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
}

internal fun JSONObject.objects(key: String): List<JSONObject> {
    val arr = optJSONArray(key) ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i) }
}

/**
 * Champs communs à tout objet segmenté par SAM, quel que soit le modèle Kotlin cible
 * (`SegObject` pour `/segment` et `/live`, `TrackedObject` pour le tracker).
 * Un seul endroit lit `label` / `score` / `box` / `mask_b64`.
 */
internal data class MaskedFields(
    val label: String,
    val score: Float,
    val box: List<Float>,
    val maskB64: String,
)

/** Lit les champs d'un objet segmenté, avec les mêmes valeurs par défaut partout. */
internal fun JSONObject.maskedFields(): MaskedFields = MaskedFields(
    label = str("label", DEFAULT_LABEL),
    score = floatOr("score"),
    box = floats("box"),
    maskB64 = str("mask_b64"),
)

/**
 * Extrait le premier objet JSON équilibré d'un texte libre (le modèle encadre
 * souvent sa réponse de ``` ou de phrases). `null` si aucun objet complet.
 */
internal fun extractFirstJsonObject(text: String): String? {
    val start = text.indexOf('{')
    if (start < 0) return null
    var depth = 0
    var inString = false
    var escaped = false
    for (i in start until text.length) {
        val c = text[i]
        when {
            escaped -> escaped = false
            c == '\\' && inString -> escaped = true
            c == '"' -> inString = !inString
            inString -> Unit
            c == '{' -> depth++
            c == '}' -> {
                depth--
                if (depth == 0) return text.substring(start, i + 1)
            }
        }
    }
    return null
}

/** Chemins connus du texte dans les réponses de transcription (Meta et autres). */
private val TRANSCRIPT_PATHS = listOf(
    "text",
    "transcript",
    "transcription",
    "output_text",
    "result.text",
    "result.transcript",
    "output.text",
    "data.text",
)

/**
 * Texte d'une réponse de transcription : JSON (champs connus, `segments`
 * recollés) ou texte brut. `null` si rien d'exploitable n'est trouvé.
 */
internal fun extractTranscript(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return trimmed
    TRANSCRIPT_PATHS.forEach { path -> json.textAt(path)?.let { return it } }
    val segments = json.segments("").ifEmpty { json.segments("result") }
    return segments.joinToString(" ").takeIf { it.isNotBlank() }
}

/** Valeur texte à un chemin pointé (`result.text`), `null` si absente ou nulle. */
internal fun JSONObject.textAt(path: String): String? {
    val parent = path.substringBeforeLast('.', "")
    val key = path.substringAfterLast('.')
    val node = objectAt(parent) ?: return null
    if (!node.has(key) || node.isNull(key)) return null
    return node.optString(key)
}

/** Sous-objet pointé (`""` = soi-même), `null` si un étage manque. */
internal fun JSONObject.objectAt(path: String): JSONObject? =
    if (path.isBlank()) this else path.split('.').fold(this as JSONObject?) { node, part -> node?.optJSONObject(part) }

/** `segments` = liste de chaînes ou d'objets `{text}`, recollés par une espace. */
internal fun JSONObject.segments(path: String): List<String> {
    val arr = objectAt(path)?.optJSONArray("segments") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        when (val item = arr.opt(i)) {
            is String -> item.takeIf { it.isNotBlank() }
            is JSONObject -> item.optString("text").takeIf { it.isNotBlank() }
            else -> null
        }
    }
}

/** Construit un tableau JSON de chaînes. */
internal fun jsonArrayOf(values: List<String>): JSONArray = JSONArray().apply { values.forEach { put(it) } }

/** Label d'un objet sans nom dans la réponse du backend. */
internal const val DEFAULT_LABEL = "objet"
