package dev.sam3vision.app.glasses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.app.AppColors
import dev.sam3vision.app.BigButton
import dev.sam3vision.app.StatusBanner

/**
 * Bloc « Lunettes » des Réglages : état lisible et trois gestes utiles
 * (enregistrer auprès de Meta AI, démarrer la session, prendre une photo).
 * Les lunettes simulées n'apparaissent qu'en build debug.
 */
@Composable
fun GlassesSection(
    state: GlassesUiState,
    debugBuild: Boolean,
    onRegister: () -> Unit,
    onConnect: () -> Unit,
    onCapture: () -> Unit,
    onDisconnect: () -> Unit,
    onStartMock: () -> Unit,
    onMockPress: () -> Unit,
    onStopMock: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Lunettes Ray-Ban Meta", color = AppColors.text, fontSize = 15.sp)
        GlassesStatusLines(state)
        state.message?.let { StatusBanner(it, AppColors.surfaceHigh) }

        BigButton(
            label = if (state.registered) "Ré-enregistrer auprès de Meta AI" else "Enregistrer auprès de Meta AI",
            enabled = state.sdkReady,
            color = AppColors.primary,
            onClick = onRegister,
        )
        BigButton(
            label = when {
                state.connected -> "Arrêter la session"
                state.phase == SessionPhase.STARTING -> "Session en cours de démarrage…"
                else -> "Démarrer la session"
            },
            enabled = state.connected || state.canConnect,
            color = AppColors.surfaceHigh,
            onClick = { if (state.connected) onDisconnect() else onConnect() },
        )
        BigButton(
            label = "Prendre une photo",
            enabled = state.connected,
            color = AppColors.surfaceHigh,
            onClick = onCapture,
        )
        if (debugBuild) {
            MockControls(state, onStartMock, onMockPress, onStopMock)
        }
    }
}

/** État technique en quatre lignes, en français lisible. */
@Composable
private fun GlassesStatusLines(state: GlassesUiState) {
    val device = state.deviceName ?: "aucune paire détectée"
    val battery = state.battery?.let { "$it %" } ?: "batterie inconnue"
    val wear = if (state.worn) "portées" else "non portées"
    Text("SDK : ${if (state.sdkReady) "prêt" else "inactif"}${if (state.devMode) " (dev mode)" else ""}", color = AppColors.textMuted, fontSize = 12.sp)
    Text("Enregistrement : ${if (state.registered) "enregistrée" else "non enregistrée"}", color = AppColors.textMuted, fontSize = 12.sp)
    Text("Appareil : $device · $battery · $wear", color = AppColors.textMuted, fontSize = 12.sp)
    Text(
        "Session : ${state.phase.name.lowercase()} · caméra ${if (state.cameraAllowed) "autorisée" else "non autorisée"} · flux ${if (state.streaming) "actif" else "arrêté"}",
        color = AppColors.textMuted,
        fontSize = 12.sp,
    )
}

/** Commandes de simulation, visibles uniquement en debug. */
@Composable
private fun MockControls(
    state: GlassesUiState,
    onStartMock: () -> Unit,
    onMockPress: () -> Unit,
    onStopMock: () -> Unit,
) {
    Text("Démo sans matériel", color = AppColors.text, fontSize = 13.sp)
    if (!state.mockActive) {
        BigButton("Simuler des lunettes", true, AppColors.surfaceHigh, onStartMock)
    } else {
        BigButton("Simuler un appui sur le bouton capture", state.connected, AppColors.surfaceHigh, onMockPress)
        BigButton("Retirer les lunettes simulées", true, AppColors.surfaceHigh, onStopMock)
    }
}