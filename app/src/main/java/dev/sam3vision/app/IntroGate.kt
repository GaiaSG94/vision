package dev.sam3vision.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Écran de garde du premier lancement : la voix d'accueil ne sort qu'après un appui
 * sur l'écran (geste explicite, jamais un audio qui part tout seul). N'occupe
 * l'affichage que tant que [introGateVisible] est vrai, c'est-à-dire tant que
 * l'intro n'a pas été entendue une fois.
 */
@Composable
fun IntroGate(onTap: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.background.copy(alpha = 0.94f))
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Text("SAM Vision", color = AppColors.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(
                "Touchez l'écran pour commencer",
                color = AppColors.primary,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(AppColors.surfaceHigh.copy(alpha = 0.9f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
            Text(
                "La voix d'accueil ne se joue qu'à la première ouverture.",
                color = AppColors.textMuted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
