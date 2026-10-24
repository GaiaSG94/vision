package dev.sam3vision.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.core.settings.Settings

/**
 * Section « Voix ElevenLabs » des réglages : clé API (masquée), ID de voix et test de
 * synthèse. La clé reste chiffrée dans le store : elle n'est jamais écrite dans le code.
 */
@Composable
fun TtsSettings(
    draft: Settings,
    speaking: SpeakState,
    onChange: (Settings) -> Unit,
    onSpeak: (String) -> Unit,
    onStop: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Voix ElevenLabs (lecture à voix haute)",
            color = AppColors.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        LabeledField("Clé API ElevenLabs", draft.ttsApiKey, true) { onChange(draft.copy(ttsApiKey = it)) }
        LabeledField("ID de voix ElevenLabs", draft.ttsVoiceId, false) { onChange(draft.copy(ttsVoiceId = it)) }
        Text(
            "Voix offertes (premade) : Sarah EXAVITQu4vr4xnSDxMaL · George JBFqnCBsd6RMkjVDRZzb · " +
                "pFZP5JQG7iQjIQuC4Bku. Les voix de bibliothèque répondent 402.",
            color = AppColors.textMuted,
            fontSize = 11.sp,
        )
        SpeakRow(
            state = speaking,
            text = TEST_VOICE_TEXT,
            onSpeak = onSpeak,
            onStop = onStop,
            actionLabel = "Tester la voix",
        )
        SwitchRow(
            label = "Lire les réponses automatiquement",
            checked = draft.ttsAutoplay,
        ) { onChange(draft.copy(ttsAutoplay = it)) }
        Text(
            "Activé : chaque réponse du chat est lue dès qu'elle arrive. Désactivé : bouton 🔊 seulement.",
            color = AppColors.textMuted,
            fontSize = 11.sp,
        )
        SwitchRow(
            label = introToggleLabel(draft.introEnabled),
            checked = draft.introEnabled,
        ) { onChange(draft.copy(introEnabled = it)) }
        Text(
            "Intro embarquée (voix Jade US, hors ligne) : jouée une seule fois, au premier " +
                "appui sur l'écran d'accueil.",
            color = AppColors.textMuted,
            fontSize = 11.sp,
        )
    }
}

/** Ligne « libellé + interrupteur » : une seule implémentation pour les bascules de voix. */
@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = AppColors.text, fontSize = 13.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}