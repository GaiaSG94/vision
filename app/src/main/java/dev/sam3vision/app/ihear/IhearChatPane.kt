package dev.sam3vision.app.ihear

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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

/** Palette sombre du chat iSee : page noire, barres système couvertes, bulles claires. */
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
    /** Cadre de la bulle média (caméra) : sombre, liséré discret. */
    val mediaFrame = Color(0xFF14161B)
    val mediaBorder = Color(0x33FFFFFF)
    val selectionBorder = Color.White
    /** Fond d'avatar discret (gris clair façon icône de profil). */
    val avatar = Color(0xFFE5E7EB)
    val accent = Color(0xFF2563EB)
}

/**
 * La page chat d'iSee : fond noir (couvrant aussi les barres système), bulles
 * blanches user à droite et IA à gauche, une seule bulle sélectionnée à la fois
 * (noire, contour blanc + ombre), et à côté de la sélection une icône ronde façon
 * icône de profil portant ▶ (silence) ou ❚❚ (lecture). Aucune interaction tactile :
 * tout passe par les gestes de [IhearRootLayout]. Les bulles anciennes sont en haut,
 * les plus récentes en bas (le fil suit l'écriture), et la bulle d'intro reste posée
 * en premier message pendant la lecture (puis pour toute l'ouverture en cours).
 */
@Composable
fun IhearChatPane(state: IhearUiState, viewModel: IhearViewModel, modifier: Modifier = Modifier) {
    // Liaison caméra vivante en permanence (prête au premier geste) ; sa vue ne
    // vit que dans la bulle média, en bas du fil.
    val glue = rememberCameraGlue(viewModel)
    val listState = rememberLazyListState()
    val mediaActive = state.recordingVideo || state.photoPath != null
    val totalItems = 1 + state.entries.size + (if (mediaActive) 1 else 0) + (if (state.thinking) 1 else 0)
    LaunchedEffect(totalItems, mediaActive, state.thinking) {
        listState.scrollToItem(totalItems - 1)
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
            // Marge latérale : les bulles ne touchent jamais les bords de l'écran
            // (16.dp de chaque côté, y compris ombre/bordure de la sélection).
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item {
                // L'intro écrite est toujours le premier message ; seule à l'écran,
                // elle porte la sélection et son icône.
                ChatBubble(
                    entry = ChatEntry.IntroBubble(INTRO_BUBBLE_ID, IHEAR_INTRO_TEXT),
                    selected = state.entries.isEmpty(),
                    speaking = state.speakingEntryId == INTRO_BUBBLE_ID,
                )
            }
            itemsIndexed(state.entries) { index, entry ->
                ChatBubble(
                    entry,
                    selected = index == state.selected,
                    speaking = state.speakingEntryId == entry.id,
                )
            }
            if (mediaActive) item { MediaBubble(state, glue, viewModel) }
            if (state.thinking) item { ThinkingBubble() }
        }
        HoldBar(state, Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
    }
}

/** En-tête du chat : titre « iSee » en blanc sur le fond noir, plus l'état éventuel. */
@Composable
private fun ChatHeader(status: String?) {
    Text(
        "iSee",
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

/** La caméra dans une bulle du chat : aperçu pendant la vidéo du tap, photo +
 * pastilles SAM après le double tap. Le cadre bulle la rattache au fil. */
@Composable
private fun MediaBubble(state: IhearUiState, glue: CameraGlue, viewModel: IhearViewModel) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            Modifier
                .shadow(2.dp, BubbleShape)
                .background(IhearPalette.mediaFrame, BubbleShape)
                .border(1.dp, IhearPalette.mediaBorder, BubbleShape)
                .padding(6.dp),
        ) {
            IhearCameraWindow(state = state, glue = glue, viewModel = viewModel, modifier = Modifier)
        }
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
        // Ligne interne pleine largeur : la bulle (poids flexible, max 280.dp)
        // ne déborde jamais sur l'avatar ni sur le bord opposé ; le contentPadding
        // de la liste garantit 16.dp du côté du locuteur.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(
                6.dp,
                if (fromUser) Alignment.End else Alignment.Start,
            ),
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
private fun RowScope.BubbleText(entry: ChatEntry, fromUser: Boolean, selected: Boolean, scale: Float) {
    val shape = if (fromUser) UserCorner else AiCorner
    val fillColor = if (selected) IhearPalette.selectedBubble else if (fromUser) IhearPalette.userBubble else IhearPalette.aiBubble
    Text(
        entry.text,
        color = if (selected) IhearPalette.textOnDark else IhearPalette.text,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        modifier = Modifier
            .weight(1f, fill = false)
            .widthIn(max = 280.dp)
            .shadow(if (selected) 8.dp else 2.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .background(fillColor, shape)
            .then(if (selected) Modifier.border(2.dp, IhearPalette.selectionBorder, shape) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
