package dev.sam3vision.app.ihear

import java.io.File

/** Le seul écran iHear : le chat plein écran, la caméra vit dans une fenêtre flottante. */

/** Une bulle de l'écran chat : le texte du user, celui lu à voix haute, ou l'intro. */
sealed interface ChatEntry {
    val id: Long
    val text: String
    val audioPath: String?

    data class UserBubble(override val id: Long, override val text: String, override val audioPath: String?) : ChatEntry
    data class AiBubble(override val id: Long, override val text: String, override val audioPath: String?) : ChatEntry

    /** La bulle d'intro : posée en tête du chat pendant la première écoute. */
    data class IntroBubble(override val id: Long, override val text: String) : ChatEntry {
        override val audioPath: String? get() = null
    }
}

/** État complet de l'écran iHear (chat + fenêtre caméra). */
data class IhearUiState(
    val entries: List<ChatEntry> = emptyList(),

    /** La bulle d'intro est posée en tête du chat tant que l'audio pousse (1re ouverture). */
    val introVisible: Boolean = false,

    /** Dernière photo du double tap, affichée dans la fenêtre flottante avant d'évaporer. */
    val photoPath: String? = null,

    val selected: Int = -1,
    val firstSwipeDownDone: Boolean = false,
    val holding: Boolean = false,
    val transcribing: Boolean = false,
    val thinking: Boolean = false,
    val speakingEntryId: Long? = null,
    val recordingVideo: Boolean = false,
    val status: String? = null,
) {
    val lastSelected: Boolean get() = selected >= entries.lastIndex
}

/** Les actions que seule la caméra de la fenêtre flottante peut exécuter. */
interface IhearCameraActions {
    val ready: Boolean
    fun startRecording(file: File): Boolean
    suspend fun stopRecording(): File?

    /**
     * Photo immédiate du double tap ; `true` = demande acceptée. [onSaved] est rappelé
     * plus tard sur le thread principal : `true` si la photo est écrite, `false` sinon.
     */
    fun takePhoto(target: File, onSaved: (Boolean) -> Unit): Boolean
}

/** ID de la bulle d'intro : en dehors du domaine des lignes Room. */
const val INTRO_BUBBLE_ID = -2L
