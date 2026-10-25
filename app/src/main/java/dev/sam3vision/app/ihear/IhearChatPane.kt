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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Palette claire du chat iHear : page blanche, bulles modernes arrondies. */
object IhearPalette {
    val page = Color.White
    val text = Color(0xFF1B1F24)
    val textMuted = Color(0xFF6B7280)
    val userBubble = Color(0xFFD7E9FF)
    val aiBubble = Color(0xFFF1F3F6)
    val accent = Color(0xFF2563EB)
}

/**
 * La page chat d'iHear : fond blanc, bulles user / IA aux angles bien ronds, une
 * seule bulle sélectionnée à la fois (bordure noire + ombre portée), une icône par
 * bulle — deux barres verticales (❚❚) quand elle parle, rectangle (⏹) sinon.
 * Aucune interaction tactile : tout passe par les gestes de [IhearRootLayout].
 */
@Composable
fun IhearChatPane(state: IhearUiState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.entries.size) {
        if (state.entries.isNotEmpty()) listState.scrollToItem(state.entries.lastIndex)
    }
    Column(
        modifier
            .fillMaxSize()
            .background(IhearPalette.page)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text("iHear", color = IhearPalette.textMuted, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        state.status?.let {
            Text(
                it,
                color = IhearPalette.textMuted,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 6.dp),
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.entries.isEmpty() && !state.holding && !state.thinking) {
                item { EmptyHint() }
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

/** Hint de démarrage, discret et gris. */
@Composable
private fun EmptyHint() {
    Text(
        "Hold anywhere to talk to iHear.",
        color = IhearPalette.textMuted,
        fontSize = 14.sp,
        modifier = Modifier.padding(vertical = 24.dp),
    )
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
            color = IhearPalette.textMuted,
            fontSize = 15.sp,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .background(IhearPalette.aiBubble, BubbleShape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

/** Deux barres verticales (en lecture) / rectangle (silencieux). */
const val GLYPH_PLAYING = "❚❚"
const val GLYPH_IDLE = "⏹"

/** Angles fortement arrondis, avec la pointe côté locuteur. */
val BubbleShape = RoundedCornerShape(20.dp)

private val UserCorner = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 6.dp, bottomEnd = 20.dp)
private val AiCorner = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp)

/** Une bulle de chat : alignement, coins, sélection et icône d'état. */
@Composable
private fun ChatBubble(entry: ChatEntry, selected: Boolean, speaking: Boolean) {
    val fromUser = entry is ChatEntry.UserBubble
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (fromUser) VoiceGlyph(speaking)
            BubbleText(entry, fromUser, selected, 1f)
            if (!fromUser) VoiceGlyph(speaking)
        }
    }
}

/** Glyph d'état : ❚❚ pendant la lecture, ⏹ sinon. */
@Composable
private fun VoiceGlyph(speaking: Boolean) {
    Text(
        if (speaking) GLYPH_PLAYING else GLYPH_IDLE,
        fontSize = 13.sp,
        color = if (speaking) IhearPalette.accent else IhearPalette.textMuted,
    )
}

/** Corps de la bulle : ombre + bordure noire quand elle est sélectionnée. */
@Composable
private fun BubbleText(entry: ChatEntry, fromUser: Boolean, selected: Boolean, scale: Float) {
    val shape = if (fromUser) UserCorner else AiCorner
    val base = if (fromUser) IhearPalette.userBubble else IhearPalette.aiBubble
    Text(
        entry.text,
        color = IhearPalette.text,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        modifier = Modifier
            .widthIn(max = 300.dp)
            .shadow(if (selected) 8.dp else 2.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .background(base, shape)
            .then(if (selected) Modifier.border(2.dp, Color.Black, shape) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
