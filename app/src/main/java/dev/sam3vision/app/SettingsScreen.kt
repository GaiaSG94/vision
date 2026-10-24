package dev.sam3vision.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.core.settings.Settings

/** Réglages : LLM (base URL, clé, modèle) + endpoint SAM optionnel, avec tests d'accès. */
@Composable
fun SettingsScreen(
    settings: Settings,
    statusLine: String?,
    llmConfigured: Boolean,
    onSave: (Settings) -> Unit,
    onTestLlm: () -> Unit,
    onTestSam: () -> Unit,
    onBack: () -> Unit,
) {
    var draft by remember { mutableStateOf(settings) }

    Column(
        Modifier
            .fillMaxSize()
            .background(AppColors.background)
            .statusBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader("Réglages du backend", "Aucune clé n'est écrite dans le code.", onBack, null)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LabeledField("Base URL LLM", draft.llmBaseUrl, false) { draft = draft.copy(llmBaseUrl = it) }
            LabeledField("Clé API LLM", draft.llmApiKey, true) { draft = draft.copy(llmApiKey = it) }
            LabeledField("Modèle LLM (vision)", draft.llmModel, false) { draft = draft.copy(llmModel = it) }
            LabeledField("Base URL SAM 3.1 (vide = désactivé)", draft.samBaseUrl, false) {
                draft = draft.copy(samBaseUrl = it)
            }
            BigButton("Enregistrer", true, AppColors.primary, onClick = { onSave(draft) })
            BigButton("Tester le LLM", llmConfigured, AppColors.surfaceHigh, onTestLlm)
            BigButton("Tester SAM", draft.samBaseUrl.isNotBlank(), AppColors.surfaceHigh, onTestSam)
            statusLine?.let { StatusBanner(it, AppColors.surfaceHigh) }
        }
    }
}

@Composable
private fun LabeledField(label: String, value: String, secret: Boolean, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = AppColors.textMuted, fontSize = 12.sp)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = !secret,
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
