package dev.sam3vision.app.ihear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Fond du panneau : sombre et translucide, lisible sur une image claire comme sur une sombre. */
private val PanelBackground = Color(0xD010161C)
private val PanelDetail = Color(0xFFAFC0CC)

/**
 * Panneau de debug de SAM sur la page caméra : la ligne d'état (« SAM · 1,2 ips ·
 * 640 ms · 8 objets · A100 ») puis le détail utile quand quelque chose cloche —
 * endpoint, taille de frame, p95, nombre de frames et de masques, dernière erreur.
 *
 * Purement informatif : aucun clic (les gestes de la page sont captés à la racine).
 */
@Composable
fun LiveDebugPanel(
    state: LiveProbeState,
    modifier: Modifier = Modifier,
    dense: Boolean = false,
) {
    if (!state.debugVisible || state.idle) return
    Column(
        modifier
            .statusBarsPadding()
            .padding(
                horizontal = if (dense) 4.dp else 12.dp,
                vertical = if (dense) 6.dp else 34.dp,
            )
            .background(PanelBackground, RoundedCornerShape(if (dense) 8.dp else 12.dp))
            .padding(horizontal = if (dense) 6.dp else 12.dp, vertical = if (dense) 4.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (dense) 1.dp else 2.dp),
    ) {
        Text(
            liveProbeHeadline(state),
            color = Color.White,
            fontSize = if (dense) 9.sp else 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = if (dense) 2 else Int.MAX_VALUE,
        )
        panelDetails(state, dense).forEach { line ->
            Text(line, color = PanelDetail, fontSize = if (dense) 8.sp else 11.sp, maxLines = 1)
        }
    }
}

/**
 * Lignes du détail. En mode [dense] (fenêtre caméra flottante, ~500 px de large), on
 * garde ce qui se lit d'un coup d'œil : latence, distances, phrase dite, erreur,
 * concepts — l'endpoint complet n'y tiendrait pas.
 */
private fun panelDetails(state: LiveProbeState, dense: Boolean): List<String> {
    val lines = liveProbeDetails(state)
    if (!dense) return lines
    // En dense, on garde ce qui se lit d'un coup d'œil : la fenêtre est petite et
    // l'endpoint, la taille de frame et le nom du GPU n'y apprennent rien de plus.
    return lines.filterNot { line ->
        line.startsWith("endpoint") || line.startsWith("frame :") || line.startsWith("gpu :")
    }
}