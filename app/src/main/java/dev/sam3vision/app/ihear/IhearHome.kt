package dev.sam3vision.app.ihear

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * L'écran d'accueil iHear : un seul écran, le chat plein écran. La caméra vit dans une
 * fenêtre flottante (angles arrondis, moins d'un demi-écran) posée en haut à droite :
 * aperçu pendant la vidéo du tap, photo + flash pendant quelques secondes, puis
 * évaporation en fondu. Toute l'interaction passe par les gestes de [IhearRootLayout];
 * les panneaux Compose restent muets.
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

    Box(Modifier.fillMaxSize()) {
        IhearChatPane(state = state, modifier = Modifier.fillMaxSize())
        // Fenêtre caméra flottante : composée en permanence (la caméra reste liée),
        // invisible et muette hors photo/vidéo.
        IhearCameraWindow(state = state, viewModel = viewModel, modifier = Modifier.align(Alignment.TopEnd))
    }
}
