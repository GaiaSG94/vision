package dev.sam3vision.app.ihear

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.sam3vision.app.IHEAR_INTRO_TEXT
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Palette sombre du chat iHear : page noire, barres système couvertes, bulles claires. */
object IhearPalette {
    /** Fond de la page chat : le noir couvre aussi la zone d'heure/batterie du haut
     * et le bandeau des 3 boutons système du bas (le fond passe sous les insets). */
    val page = Color.Black
    val text = Color(0xFF1B1F24)
    val textOnDark = Color.White
    val title = Color.White
    val textMuted = Color(0xFF6B7280)
    /** Toutes les bulles, user comme IA, sont blanches sur la page noire. */
    val userBubble = Color.White
    val aiBubble = Color.White
    /** Bulle sélectionnée : noire à contour blanc (le texte reste lisible). */
    val selectedBubble = Color.Black
    val selectionBorder = Color.White
    /** Fond d'avatar discret (gris clair façon icône de profil). */
    val avatar = Color(0xFFE5E7EB)
    val accent = Color(0xFF2563EB)
}

/**
 * La page chat d'iHear : fond noir (couvrant aussi les barres système), bulles
 * blanches user à droite et IA à gauche, une seule bulle sélectionnée à la fois
 * (noire, contour blanc + ombre), et à côté de la sélection une icône ronde façon
 * icône de profil portant ▶ (silence) ou ❚❚ (lecture). Aucune interaction tactile :
 * tout passe par les gestes de [IhearRootLayout]. Les bulles anciennes sont en haut,
 * les plus récentes en bas (le fil suit l'écriture), et la bulle d'intro reste posée
 * en premier message pendant la lecture (puis pour toute l'ouverture en cours).
 */
@Composable
fun IhearChatPane(state: IhearUiState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    // La bulle d'intro décale d'une ligne : le bas du fil reste l'item le plus récent.
    val bottomIndex = state.entries.lastIndex + if (state.introVisible) 1 else 0
    LaunchedEffect(bottomIndex) {
        if (bottomIndex >= 0) listState.scrollToItem(bottomIndex)
    }
    Column(
        modifier
            .fillMaxSize()
            .background(IhearPalette.page)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        ChatHeader(state.status)
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
        if (state.introVisible) {
            item {
                ChatBubble(
                    entry = ChatEntry.IntroBubble(INTRO_BUBBLE_ID, IHEAR_INTRO_TEXT),
                    selected = false,
                    speaking = state.speakingEntryId == INTRO_BUBBLE_ID,
                )
            }
        }
        itemsIndexed(state.entries) { index, entry ->
            ChatBubble(
                entry,
                selected = index == state.selected,
                speaking = state.speakingEntryId == entry.id,
            )
        }
            if (state.thinking) item { ThinkingBubble() }
        }
        HoldBar(state, Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
    }
}

/** En-tête du chat : titre « iHear » en blanc sur le fond noir, plus l'état éventuel. */
@Composable
private fun ChatHeader(status: String?) {
    Text(
        "iHear",
        color = IhearPalette.title,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
    )
    status?.let {
        Text(
            it,
            color = IhearPalette.textMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 6.dp),
        )
    }
}

/** Zone basse : indicateur d'écoute / transcription, sans champ de saisie. */
@Composable
private fun HoldBar(state: IhearUiState, modifier: Modifier = Modifier) {
    val label = when {
        state.holding -> "Listening… release to send"
        state.transcribing -> "Transcribing…"
        else -> null
    }
    if (label == null) return
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Text(
            label,
            color = IhearPalette.accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .background(IhearPalette.userBubble, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/** Bulle du pseudo « IA réfléchit » pendant la réponse du LLM. */
@Composable
private fun ThinkingBubble() {
    Box(Modifier.fillMaxWidth()) {
        Text(
            "…",
            color = IhearPalette.text,
            fontSize = 15.sp,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .background(IhearPalette.aiBubble, BubbleShape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

/** Deux barres verticales (lecture) / triangle (silence), affichés dans l'avatar. */
const val GLYPH_PLAYING = "❚❚"
const val GLYPH_PLAY = "▶"

/** Angles fortement arrondis, avec la pointe côté locuteur. */
val BubbleShape = RoundedCornerShape(20.dp)

private val UserCorner = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 6.dp, bottomEnd = 20.dp)
private val AiCorner = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp)

/**
 * Une bulle de chat : user à droite, IA à gauche, coins arrondis, sélection noire à
 * contour blanc. À côté de la sélection seulement : l'avatar rond [VoiceIcon], côté
 * gauche pour une bulle user, côté droit pour une bulle IA, aligné haut de bulle.
 */
@Composable
private fun ChatBubble(entry: ChatEntry, selected: Boolean, speaking: Boolean) {
    val fromUser = entry is ChatEntry.UserBubble
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (fromUser && selected) VoiceIcon(speaking)
            BubbleText(entry, fromUser, selected, 1f)
            if (!fromUser && selected) VoiceIcon(speaking)
        }
    }
}

/** Avatar rond façon icône de profil : ▶ quand la voix ne parle pas, ❚❚ sinon. */
@Composable
private fun VoiceIcon(speaking: Boolean) {
    Box(
        modifier = Modifier.size(28.dp).background(IhearPalette.avatar, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (speaking) GLYPH_PLAYING else GLYPH_PLAY,
            fontSize = 11.sp,
            color = IhearPalette.text,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Corps de la bulle : blanche par défaut ; sélectionnée, noire à contour blanc et
 * texte blanc, avec ombre portée.
 */
@Composable
private fun BubbleText(entry: ChatEntry, fromUser: Boolean, selected: Boolean, scale: Float) {
    val shape = if (fromUser) UserCorner else AiCorner
    val fillColor = if (selected) IhearPalette.selectedBubble else if (fromUser) IhearPalette.userBubble else IhearPalette.aiBubble
    Text(
        entry.text,
        color = if (selected) IhearPalette.textOnDark else IhearPalette.text,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        modifier = Modifier
            .widthIn(max = 300.dp)
            .shadow(if (selected) 8.dp else 2.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .background(fillColor, shape)
            .then(if (selected) Modifier.border(2.dp, IhearPalette.selectionBorder, shape) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
