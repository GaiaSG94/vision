package dev.sam3vision.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Distances du direct, posées sur l'aperçu : une pastille « ≈ 3,2 m » au-dessus
 * de chaque objet mesuré, dans la couleur de son masque.
 *
 * Les positions sont recalculées avec la même transformation que l'aperçu
 * (`ContentScale.Crop`) — [cropTransform] et [screenLabels] restent purs et
 * testés en JVM, seule la mise en forme vit ici. Rien à afficher : rien de composé.
 */
@Composable
fun LiveDistanceLayer(
    layer: DistanceLayer?,
    topInset: Dp = TOP_SAFE_INSET,
    withLabel: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (layer == null || layer.empty) return
    BoxWithConstraints(modifier.fillMaxSize()) {
        val transform = cropTransform(
            imageWidth = layer.width,
            imageHeight = layer.height,
            boxWidth = constraints.maxWidth.toFloat(),
            boxHeight = constraints.maxHeight.toFloat(),
        )
        val lift = with(LocalDensity.current) { LABEL_LIFT.toPx() }
        val topInsetPx = with(LocalDensity.current) { topInset.toPx() }
        val boxWidth = constraints.maxWidth.toFloat()
        val fontSize = (BASE_FONT_SP * sqrt(transform.scale)).coerceIn(MIN_FONT_SP, MAX_FONT_SP).sp
        screenLabels(layer, transform, withLabel).forEach { label ->
            val anchorX = label.x.roundToInt().toFloat()
            val anchorY = (label.y - lift).roundToInt().coerceAtLeast(0).toFloat()
            Text(
                text = label.text,
                color = Color.White,
                fontSize = fontSize,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier
                    .offset { IntOffset(anchorX.toInt(), anchorY.toInt()) }
                    .graphicsLayer {
                        translationX = pillTranslationX(anchorX, size.width.toFloat(), boxWidth)
                        translationY = pillTranslationY(anchorY, topInsetPx)
                    }
                    .background(maskColorAt(label.colorIndex).copy(alpha = 0.92f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** Hauteur de pastille laissée libre au-dessus de la boîte (le texte ne masque pas l'objet). */
private val LABEL_LIFT = 30.dp

/**
 * Bandeau du haut (boutons + état + concepts) : aucune pastille ne s'y cache.
 * Les autres hôtes — la fenêtre flottante d'iSee — passent leur propre marge.
 */
private val TOP_SAFE_INSET = 230.dp

/** Corps de la police : adapté à la résolution de l'aperçu, borné pour rester lisible. */
private const val BASE_FONT_SP = 11f
private const val MIN_FONT_SP = 10f
private const val MAX_FONT_SP = 20f