package dev.sam3vision.app.ihear

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier

/**
 * L'écran d'accueil iHear : un seul écran, le chat plein écran. La caméra vit dans
 * une bulle du chat, en bas du fil : aperçu pendant la vidéo du tap, photo +
 * pastilles SAM après le double tap, puis évaporation en fondu. Toute l'interaction
 * passe par les gestes de [IhearRootLayout] ; les panneaux Compose restent muets.
 */
@Composable
fun IhearHome(viewModel: IhearViewModel, onOpenSettings: () -> Unit) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshSettings() }
    DisposableEffect(Unit) {
        viewModel.active = true
        onDispose { viewModel.active = false }
    }
    // Retour système : ouvre les Réglages (clés API, tests) sans bouton sur la surface.
    BackHandler(enabled = true) { onOpenSettings() }

    IhearChatPane(state = state, viewModel = viewModel, modifier = Modifier.fillMaxSize())
}
