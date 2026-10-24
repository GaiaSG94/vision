package dev.sam3vision.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Bandeau du haut : retour, réglages, ligne d'état du direct, erreurs. */
@Composable
fun LiveTopBar(
    state: LiveUiState,
    failure: String?,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().statusBarsPadding().padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Arrêter", AppColors.danger, onBack)
            Chip("Réglages", AppColors.surfaceHigh, onOpenSettings)
        }
        Text(
            state.statusLine,
            color = AppColors.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .padding(top = 8.dp)
                .background(AppColors.surface.copy(alpha = 0.85f), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        (failure ?: state.error)?.let { StatusBanner(it, AppColors.danger) }
    }
}

/** Bandeau du bas : labels suivis et accès aux questions. */
@Composable
fun LiveBottomBar(state: LiveUiState, onAsk: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val labels = state.frame?.objects?.map { it.label }?.distinct()?.take(5).orEmpty()
        if (labels.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                labels.forEach { Chip(it, AppColors.primary, null) }
            }
        }
        BigButton("Poser une question", true, AppColors.primary, onAsk)
    }
}

/** Pastille : cliquable si [onClick] fourni, sinon simple étiquette. */
@Composable
fun Chip(label: String, color: Color, onClick: (() -> Unit)?) {
    val shape = RoundedCornerShape(12.dp)
    val decorated = Modifier.background(color.copy(alpha = 0.9f), shape).padding(horizontal = 12.dp, vertical = 7.dp)
    if (onClick == null) {
        Box(decorated) { Text(label, color = Color.White, fontSize = 13.sp, maxLines = 1) }
    } else {
        TextButton(onClick = onClick, modifier = decorated) {
            Text(label, color = Color.White, fontSize = 13.sp, maxLines = 1)
        }
    }
}
