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

/** Construit un tableau JSON de chaînes. */
internal fun jsonArrayOf(values: List<String>): JSONArray = JSONArray().apply { values.forEach { put(it) } }
