package dev.sam3vision.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.core.model.ChatTurn
import dev.sam3vision.core.model.Role

/** Chat sur la photo courante : historique visible, envoi de la question, réponse du LLM. */
@Composable
fun ChatScreen(state: UiState, onBack: () -> Unit, onAsk: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    val send: () -> Unit = {
        val question = draft.trim()
        if (question.isNotEmpty() && !state.asking) {
            onAsk(question)
            draft = ""
        }
    }
    Column(Modifier.fillMaxSize().background(AppColors.background).statusBarsPadding()) {
        ScreenHeader("Questions sur la photo", null, onBack, null)
        if (state.chat.isEmpty() && !state.asking) {
            StatusBanner("Pose ta question sur la photo — le modèle répond à partir de l'image.", AppColors.surfaceHigh)
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            reverseLayout = false,
        ) {
            items(state.chat) { turn -> ChatBubble(turn) }
            if (state.asking) {
                item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(strokeWidth = 3.dp, color = AppColors.primary, modifier = Modifier.padding(8.dp)) } }
            }
        }
        state.statusLine?.let { StatusBanner(it, AppColors.warning) }
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Ex. : qu'y a-t-il à gauche ?", color = AppColors.textMuted, fontSize = 14.sp) },
                singleLine = true,
                enabled = !state.asking,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = send, enabled = draft.isNotBlank() && !state.asking) { Text("Envoyer", fontSize = 15.sp) }
        }
    }
}

/** Bulle de conversation, alignée selon le rôle. */
@Composable
private fun ChatBubble(turn: ChatTurn) {
    val fromUser = turn.role == Role.USER
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Text(
            turn.content,
            color = if (fromUser) ColorWhite else AppColors.text,
            fontSize = 14.sp,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(if (fromUser) AppColors.primary else AppColors.surface, RoundedCornerShape(14.dp))
                .padding(12.dp),
        )
    }
}

private val ColorWhite = androidx.compose.ui.graphics.Color.White
