package dev.sam3vision.app.ihear

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier

/**
 * L'écran d'accueil iHear : la page caméra **et** la page chat sont posées l'une sur
 * l'autre — le chat recouvre la caméra, dont l'aperçu reste vivant (le swipe de page
 * ne coupe jamais le flux vidéo : c'était la consigne). Toute la navigation et les
 * actions vivent dans [IhearRootLayout], à la racine de la fenêtre.
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
        // La caméra reste composée et liée même sous le chat : jamais coupée.
        IhearCameraPane(state = state, viewModel = viewModel, modifier = Modifier.fillMaxSize())
        if (state.page == IhearPage.Chat) {
            IhearChatPane(state = state, modifier = Modifier.fillMaxSize())
        }
    }
}
