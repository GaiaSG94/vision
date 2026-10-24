package dev.sam3vision.core.net

import dev.sam3vision.core.model.Analysis
import dev.sam3vision.core.model.ChatTurn
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.LiveHealth
import dev.sam3vision.core.model.SegmentResult

/** Client LLM vision (InferHub, API compatible OpenAI). */
interface LlmClient {
    suspend fun describe(jpegB64: String, labels: List<String>): Result<Analysis>

    suspend fun ask(
        jpegB64: String,
        question: String,
        history: List<ChatTurn>,
    ): Result<String>
}

/** Client de segmentation SAM 3.1 (endpoint HTTP optionnel, contrat de l'ancien backend Modal). */
interface SamClient {
    suspend fun segment(pngOrJpegB64: String, prompts: List<String>?): Result<SegmentResult>

    suspend fun health(): Result<Boolean>
}

/**
 * Flux live (endpoint `/live` du direct) : une frame à la fois, jamais de file d'attente.
 * [live] renvoie [Result.failure] avec une [ApiError] typée sur erreur réseau/HTTP/parsing.
 */
interface LiveClient {
    suspend fun live(
        jpegB64: String,
        prompts: List<String>?,
        workEdge: Int,
        maxObjects: Int,
    ): Result<LiveFrame>

    suspend fun health(): Result<LiveHealth>
}

/**
 * Synthèse vocale (ElevenLabs TTS) : la réponse du LLM devient de l'audio.
 * [synthesize] renvoie des octets MP3, ou [Result.failure] avec une [ApiError] typée —
 * notamment [ApiError.PaymentRequired] quand le compte n'a pas de crédits.
 */
interface TtsClient {
    suspend fun synthesize(text: String): Result<ByteArray>
}

/**
 * Transcription vocale (Meta Muse ASR) : un WAV PCM 16 bits mono -> texte.
 * [transcribe] renvoie [Result.failure] avec une [ApiError] typée ; un texte
 * vide n'est pas une erreur réseau (le cas d'usage décide quoi en faire).
 */
interface SpeechClient {
    suspend fun transcribe(wav: ByteArray): Result<String>
}
