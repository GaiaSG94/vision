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
 *
 * Le contenu : bloc explicatif animé ([IntroGestureRow]) qui montre les trois gestes
 * dispo en haut de l'écran, tap vidéo / double-tap photo / appui long = dicter,
 * suivi de la phrase d'attente « Tap the screen to hear the intro. ».
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 22.dp),
        ) {
            Text("Welcome to iHear", color = AppColors.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                "Anywhere on the screen:",
                color = AppColors.primary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(AppColors.surfaceHigh.copy(alpha = 0.9f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
            IntroGestureRow()
            Text(
                "Tap the screen to hear the intro.",
                color = AppColors.textMuted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
