package dev.sam3vision.core.net

import dev.sam3vision.core.model.Analysis
import dev.sam3vision.core.model.ChatTurn
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
