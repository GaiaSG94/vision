package dev.sam3vision.core.model

/** Un objet segmenté par SAM : label, score de confiance, boîte [x1,y1,x2,y2], masque PNG gris en base64. */
data class SegObject(
    val label: String,
    val score: Float,
    val box: List<Float>,
    val maskPngB64: String,
)

/** Résultat brut d'un appel /segment. */
data class SegmentResult(
    val width: Int,
    val height: Int,
    val objects: List<SegObject>,
)

/** Analyse finale : description libre + tags + objets segmentés (vide si segmentation désactivée). */
data class Analysis(
    val description: String,
    val tags: List<String>,
    val objects: List<SegObject>,
)

/** Un tour de conversation sur la photo courante. */
data class ChatTurn(val role: Role, val content: String)

enum class Role { USER, ASSISTANT }

/** Résultat brut d'un appel /live : une frame du direct, avec sa durée serveur. */
data class LiveFrame(
    val width: Int,
    val height: Int,
    val elapsedMs: Long,
    val objects: List<SegObject>,
)

/** État du backend live : `GET /health`. */
data class LiveHealth(
    val ok: Boolean,
    val model: String,
    val device: String,
    val gpu: String,
)
