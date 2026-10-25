package dev.sam3vision.app

import dev.sam3vision.core.settings.Settings

/**
 * Réglages persistés complétés par les valeurs injectées au **build** (debug,
 * `local.properties` gitignoré) : c'est le seul chemin par lequel une clé entre
 * dans l'app sans être tapée à la main.
 *
 * Règle : une valeur déjà saisie dans Réglages gagne toujours, jamais écrasée par
 * le build. Utilisé par **toutes** les vues qui parlent ou appellent un backend —
 * `AppViewModel` (chat, analyse, dictée) comme `LiveViewModel` (mode Direct) :
 * sinon le Direct serait muet alors que le chat parle.
 */
fun seededFromBuild(loaded: Settings): Settings = withTtsDefaults(seedVoice(withLlmKey(loaded)))

/** Clé LLM de build : uniquement si rien n'a encore été saisi. */
internal fun withLlmKey(loaded: Settings): Settings {
    val fromBuild = BuildConfig.LLM_API_KEY
    if (loaded.llmApiKey.isNotBlank() || fromBuild.isBlank()) return loaded
    return loaded.copy(
        llmApiKey = fromBuild,
        llmBaseUrl = loaded.llmBaseUrl.ifBlank { BuildConfig.LLM_BASE_URL },
        llmModel = loaded.llmModel.ifBlank { BuildConfig.LLM_MODEL },
        samBaseUrl = loaded.samBaseUrl.ifBlank { BuildConfig.SAM_BASE_URL },
    )
}

/** Clé ElevenLabs de build : voix du chat, de la dictée et de l'annonce des positions. */
internal fun seedVoice(loaded: Settings): Settings {
    if (loaded.ttsApiKey.isNotBlank() || BuildConfig.TTS_API_KEY.isBlank()) return loaded
    return loaded.copy(
        ttsApiKey = BuildConfig.TTS_API_KEY,
        ttsVoiceId = loaded.ttsVoiceId.ifBlank { BuildConfig.TTS_VOICE_ID },
    )
}