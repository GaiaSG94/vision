package dev.sam3vision.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Bloc explicatif animé de l'intro : trois panneaux côte à côte, chacun avec un doigt
 * dessiné (Canvas, aucun asset embarqué) qui rejoue en boucle **tap = vidéo**,
 * **double tap = photo**, **appui long = dicter**. La scène est exprimée en fractions
 * de la carte : elle se rend sans déformation quelle que soit la taille de l'écran.
 *
 * Le dessin du doigt est stylisé-réaliste (dégradé de peau, ombre portée, brillance
 * de phalange, ongle) : pas d'image photoréaliste embarquée, aucun asset à télécharger.
 */

/** Les trois gestes expliqués à l'écran, dans l'ordre du discours d'accueil. */
enum class IntroGestureKind { Tap, DoubleTap, Hold }

/** Texte d'une carte : numéro + action en haut, conséquence en bas. */
private data class IntroPanelSpec(val kind: IntroGestureKind, val title: String, val hint: String)

private val INTRO_PANELS = listOf(
    IntroPanelSpec(IntroGestureKind.Tap, "1 · Tap once", "Video starts. Tap again to stop."),
    IntroPanelSpec(IntroGestureKind.DoubleTap, "2 · Tap twice", "Takes a photo instantly."),
    IntroPanelSpec(IntroGestureKind.Hold, "3 · Press & hold", "Speak while held, release to send."),
)

/** Uniform header over the three panels: le geste compte partout — « n'importe où sur l'écran ». */
@Composable
fun IntroGestureRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        INTRO_PANELS.forEach { spec -> Box(Modifier.weight(1f)) { IntroGestureCard(spec) } }
    }
}

/** Une carte = scène Canvas en haut + légende en bas. Boucle infinie par carte. */
@Composable
private fun IntroGestureCard(spec: IntroPanelSpec) {
    val period = when (spec.kind) {
        IntroGestureKind.Tap -> 2_000
        IntroGestureKind.DoubleTap -> 2_600
        IntroGestureKind.Hold -> 3_200
    }
    val transition = rememberInfiniteTransition(label = "intro-${spec.kind.name}")
    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(tween(durationMillis = period, easing = LinearEasing)),
        label = "intro-time-${spec.kind.name}",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            drawIntroStage(spec.kind, introPose(spec.kind, time))
        }
        Text(
            text = spec.title,
            color = AppColors.text,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = spec.hint,
            color = AppColors.textMuted,
            fontSize = 9.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

// ————————————————————— Pose du doigt (fonction pure, pas d'Android) —————————————————————

/** Position du doigt sur une carte : ce snapshot pousse tout le dessin, sans état caché. */
private data class IntroPose(
    val tip: Float,           // position verticale de la pointe (0..1 de la carte)
    val pressed: Boolean,     // la pointe touche le mini-écran
    val press: Float,         // 0..1 : lissage pour l'écrasement et l'ombre
    val ripple: Float,        // -1 = off, sinon 0..1 (anneau du tap)
    val flash: Float,         // -1 = off, sinon 0..1 (éclat photo du double tap)
    val wavePhase: Float,     // -1 = off, sinon 0..1 (ondes de l'appui long)
    val recOn: Boolean,       // pastille REC du mini-écran allumée
    val cameraFlash: Boolean, // icône photo allumée sur le mini-écran
)

/** Repères verticaux partagés : pointe au repos vs pointe en contact avec l'écran. */
private const val REST_Y = 0.20f
private const val TOUCH_Y = 0.70f

/** Une pression lissée (montée, tenue, descente) sur la fenêtre [a..d]. */
private fun pressAmount(t: Float, a: Float, b: Float, c: Float, d: Float): Float = when {
    t <= a || t >= d -> 0f
    t < b -> ease((t - a) / (b - a))
    t <= c -> 1f
    else -> ease((d - t) / (d - c))
}

/** Smoothstep : démarrage et arrêt très doux, le geste reste lisible. */
private fun ease(x: Float) = x * x * (3f - 2f * x)

/** Cas 1 — tap simple : une pression, la vidéo tourne, une nouvelle pression stoppe. */
private fun tapPose(t: Float): IntroPose {
    val press = pressAmount(t, 0.14f, 0.22f, 0.56f, 0.68f)
    val ripple = if (t >= 0.22f && t < 0.56f) ((t - 0.22f) / 0.34f) else -1f
    return pose(t, press, ripple, -1f, -1f, recOn = press > 0.5f, cameraFlash = false)
}

/** Cas 2 — double tap : deux pressions rapprochées, éclat photo juste après la 2e. */
private fun doubleTapPose(t: Float): IntroPose {
    val first = pressAmount(t, 0.10f, 0.16f, 0.30f, 0.38f)
    val second = pressAmount(t, 0.40f, 0.46f, 0.62f, 0.70f)
    val press = max(first, second)
    val ripple = when {
        t in 0.16f..0.38f -> (t - 0.16f) / 0.22f
        t in 0.46f..0.70f -> (t - 0.46f) / 0.24f
        else -> -1f
    }
    val flash = if (t in 0.46f..0.82f) ((t - 0.46f) / 0.36f) else -1f
    return pose(t, press, ripple, flash, -1f, recOn = false, cameraFlash = t in 0.46f..0.82f)
}

/** Cas 3 — appui long : le doigt reste posé, les ondes « voix » pulsent. */
private fun holdPose(t: Float): IntroPose {
    val press = if (t < 0.14f) ease(t / 0.14f) else 1f
    val wavePhase = if (t >= 0.18f) ((t - 0.18f) / 0.82f) else -1f
    return pose(t, press, -1f, -1f, wavePhase, recOn = false, cameraFlash = false)
}

/** Fusionne un cas en [IntroPose] : pointe interpolée + états du mini-écran. */
private fun pose(
    t: Float,
    press: Float,
    ripple: Float,
    flash: Float,
    wavePhase: Float,
    recOn: Boolean,
    cameraFlash: Boolean,
): IntroPose = IntroPose(
    tip = REST_Y + (TOUCH_Y - REST_Y) * press,
    pressed = press > 0.5f,
    press = press,
    ripple = ripple,
    flash = flash,
    wavePhase = wavePhase,
    recOn = recOn,
    cameraFlash = cameraFlash,
)

private fun introPose(kind: IntroGestureKind, t: Float): IntroPose = when (kind) {
    IntroGestureKind.Tap -> tapPose(t)
    IntroGestureKind.DoubleTap -> doubleTapPose(t)
    IntroGestureKind.Hold -> holdPose(t)
}

// ————————————————————— Rendu (DrawScope, tout en fractions de la carte) —————————————————————

private val StageTop = Color(0xFF10151F)
private val StageBottom = Color(0xFF182034)  // léger halo bleu en bas de la carte
private val ScreenBg = Color(0xFF0C1018)
private val ScreenEdge = Color(0xFF2A3446)
private val SkinLight = Color(0xFFF6D2B3)
private val SkinBase = Color(0xFFE8B492)
private val SkinShade = Color(0xFFC68B62)
private val RecRed = Color(0xFFE5484D)
private val AccentBlue = Color(0xFF7CB6FF)
private val PressGlow = Color(0x447CB6FF)

/** Rect horizontal du mini-écran, en fractions de la carte. */
private const val SCREEN_LEFT = 0.10f
private const val SCREEN_RIGHT = 0.90f
private const val SCREEN_TOP = 0.66f
private const val SCREEN_BOTTOM = 0.97f

/** Arrière-plan de la carte : dégradé doux + halo bleu autour du contact. */
private fun DrawScope.drawStage() {
    drawRect(
        Brush.verticalGradient(
            listOf(StageTop, StageBottom),
            startY = 0f,
            endY = size.height,
        ),
    )
    val r = size.width * 0.40f
    drawOval(
        Brush.radialGradient(listOf(Color(0x22508EF7), Color.Transparent)),
        topLeft = Offset(size.width * 0.5f - r, SCREEN_TOP * size.height - r * 0.7f),
        size = Size(r * 2f, r * 1.4f),
    )
}

/** Le mini-écran en bas de la carte + ses indices UI (barres, REC, photo). */
private fun DrawScope.drawMiniScreen(pose: IntroPose) {
    val left = SCREEN_LEFT * size.width
    val top = SCREEN_TOP * size.height
    val right = SCREEN_RIGHT * size.width
    val bottom = SCREEN_BOTTOM * size.height
    val radius = CornerRadius(14f, 14f)
    drawRoundRect(color = ScreenBg, topLeft = Offset(left, top), size = Size(right - left, bottom - top), cornerRadius = radius)
    drawRoundRect(color = ScreenEdge, topLeft = Offset(left, top), size = Size(right - left, bottom - top), cornerRadius = radius, style = Stroke(width = 3f))
    val innerLeft = left + (right - left) * 0.08f
    drawRoundRect(
        color = Color(0xFF2B3648),
        topLeft = Offset(innerLeft, top + (bottom - top) * 0.45f),
        size = Size((right - left) * 0.52f, (bottom - top) * 0.11f),
        cornerRadius = CornerRadius(10f, 10f),
    )
    drawRoundRect(
        color = Color(0xFF252E3E),
        topLeft = Offset(innerLeft, top + (bottom - top) * 0.68f),
        size = Size((right - left) * 0.74f, (bottom - top) * 0.11f),
        cornerRadius = CornerRadius(10f, 10f),
    )
    drawRecDot(pose.recOn, left, top, right)
    if (pose.cameraFlash) drawPhotoIcon(left, top, right, bottom)
}

/**
 * Pastille du mode vidéo : rouge pendant la « vidéo », grise sinon — le même
 * code couleur que le vrai badge REC de la page caméra de l'app.
 */
private fun DrawScope.drawRecDot(on: Boolean, left: Float, top: Float, right: Float) {
    val cx = right - (right - left) * 0.18f
    val cy = top + (size.height - top) * 0.18f
    drawCircle(
        color = if (on) RecRed else Color(0xFF39445A),
        radius = size.width * 0.035f,
        center = Offset(cx, cy),
    )
}

/** Petit boîtier photo (rect + objectif) allumé par l'éclat du double tap. */
private fun DrawScope.drawPhotoIcon(left: Float, top: Float, right: Float, bottom: Float) {
    val w = (right - left) * 0.16f
    val h = w * 0.72f
    val boxLeft = right - (right - left) * 0.34f
    val boxTop = top + (bottom - top) * 0.12f
    drawRoundRect(
        color = AccentBlue,
        topLeft = Offset(boxLeft, boxTop),
        size = Size(w, h),
        cornerRadius = CornerRadius(6f, 6f),
    )
    drawCircle(
        color = Color.White,
        radius = w * 0.22f,
        center = Offset(boxLeft + w / 2f, boxTop + h / 2f),
    )
}

/** Halo bleu doux sous la pointe quand elle touche le mini-écran. */
private fun DrawScope.drawPressGlow(pose: IntroPose) {
    if (pose.press <= 0.01f) return
    val r = size.width * 0.26f
    drawOval(
        Brush.radialGradient(listOf(PressGlow.copy(alpha = PressGlow.alpha * pose.press), Color.Transparent)),
        topLeft = Offset(size.width * 0.5f - r, pose.tip * size.height - r * 0.5f),
        size = Size(r * 2f, r),
    )
}

/** Anneau du tap : un cercle qui grandit et s'estompe depuis le point de contact. */
private fun DrawScope.drawTapRipple(pose: IntroPose) {
    val k = pose.ripple
    if (k !in 0f..1f) return
    val radius = size.width * (0.10f + 0.24f * k)
    drawCircle(
        color = AccentBlue.copy(alpha = 0.65f * (1f - k)),
        radius = radius,
        center = Offset(size.width * 0.5f, SCREEN_TOP * size.height + (SCREEN_BOTTOM - SCREEN_TOP) * size.height * 0.20f),
        style = Stroke(width = size.width * 0.018f * (1f - k * 0.4f)),
    )
}

/** Éclat blanc de la photo : rayons qui sortent du contact et s'effacent. */
private fun DrawScope.drawFlashBurst(pose: IntroPose) {
    val k = pose.flash
    if (k !in 0f..1f) return
    val alpha = (1f - k) * 0.85f
    val center = Offset(size.width * 0.5f, SCREEN_TOP * size.height + (SCREEN_BOTTOM - SCREEN_TOP) * size.height * 0.20f)
    val radius = size.width * (0.16f + 0.30f * k)
    drawCircle(color = Color.White.copy(alpha = alpha * 0.30f), radius = radius * 1.4f, center = center)
    repeat(6) { i ->
        val angle = (i * 60f) * (Math.PI / 180.0).toFloat() + (k * 0.6f)
        drawLine(
            color = Color.White.copy(alpha = alpha),
            start = center,
            end = Offset(center.x + sin(angle) * radius, center.y - cos(angle) * radius),
            strokeWidth = size.width * 0.02f,
        )
    }
}

/** Ondes concentriques de la voix pendant l'appui maintenu. */
private fun DrawScope.drawHoldWaves(pose: IntroPose) {
    val phase = pose.wavePhase
    if (phase < 0f) return
    val center = Offset(size.width * 0.5f, SCREEN_TOP * size.height + (SCREEN_BOTTOM - SCREEN_TOP) * size.height * 0.22f)
    repeat(3) { i ->
        val k = (phase + i / 3f) % 1f
        drawCircle(
            color = AccentBlue.copy(alpha = 0.45f * (1f - k)),
            radius = size.width * (0.16f + 0.34f * k),
            center = center,
            style = Stroke(width = size.width * 0.016f),
        )
    }
}

/** Ombre portée du doigt, corps du doigt, brillance de phalange. */
private fun DrawScope.drawFinger(pose: IntroPose) {
    val w = size.width
    val h = size.height
    val cx = w * 0.5f
    val tipY = pose.tip * h
    val shaftTop = tipY - h * (0.62f + 0.10f * pose.press)
    val halfW = w * 0.155f

    // Ombre sur le mini-écran : plus dense quand le doigt est proche.
    drawOval(
        Brush.radialGradient(
            listOf(Color.Black.copy(alpha = 0.16f + 0.24f * pose.press), Color.Transparent),
        ),
        topLeft = Offset(cx - halfW * 1.35f, tipY + h * 0.10f),
        size = Size(halfW * 2.7f, halfW * 0.9f),
    )

    val body = fingerBody(cx, tipY, shaftTop, halfW)
    drawPath(body, Brush.verticalGradient(listOf(SkinLight, SkinBase, SkinShade), startY = shaftTop, endY = tipY))
    drawPath(
        body,
        Brush.verticalGradient(listOf(Color(0x20C68B62), Color(0x408A5A32)), startY = shaftTop, endY = tipY),
        style = Stroke(width = 2f),
    )
    drawCircle(
        color = Color(0x33FFFFFF),
        radius = halfW * 0.55f,
        center = Offset(cx - halfW * 0.25f, tipY - h * 0.26f),
    )
}

/** Silhouette du doigt : fût vertical + pointe arrondie vers le bas. */
private fun fingerBody(cx: Float, tipY: Float, shaftTop: Float, halfW: Float): Path = Path().apply {
    moveTo(cx - halfW, shaftTop)
    lineTo(cx - halfW, tipY - halfW * 0.9f)
    quadraticTo(cx - halfW + halfW * 0.1f, tipY - halfW * 0.2f, cx, tipY + halfW * 0.16f)
    quadraticTo(cx + halfW - halfW * 0.1f, tipY - halfW * 0.2f, cx + halfW, tipY - halfW * 0.9f)
    lineTo(cx + halfW, shaftTop)
    close()
}

/** L'ensemble d'une scène, de l'arrière-plan jusqu'au doigt par-dessus. */
private fun DrawScope.drawIntroStage(kind: IntroGestureKind, pose: IntroPose) {
    drawStage()
    drawMiniScreen(pose)
    drawPressGlow(pose)
    drawTapRipple(pose)
    if (kind == IntroGestureKind.Hold) drawHoldWaves(pose)
    if (pose.flash >= 0f) drawFlashBurst(pose)
    drawFinger(pose)
}
