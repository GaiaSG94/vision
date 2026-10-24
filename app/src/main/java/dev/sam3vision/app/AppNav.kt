package dev.sam3vision.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState

/** Routes de l'app (navigation par état, sans dépendance navigation-compose). */
sealed interface Route {
    data object Capture : Route
    data object Analysis : Route
    data object Chat : Route
    data object Settings : Route
}

/** Racine de navigation : distribue l'état du [AppViewModel] aux quatre écrans. */
@Composable
fun AppNav(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsState()
    var route by remember { mutableStateOf<Route>(Route.Capture) }

    when (route) {
        Route.Capture -> CaptureScreen(
            onPhoto = { file ->
                viewModel.onPhoto(file)
                route = Route.Analysis
            },
            onOpenSettings = { route = Route.Settings },
        )

        Route.Analysis -> AnalysisScreen(
            state = state,
            onBack = { route = Route.Capture },
            onOpenChat = { route = Route.Chat },
            onToggleMasks = viewModel::toggleMasks,
            onRetry = viewModel::retryAnalysis,
            onOpenSettings = { route = Route.Settings },
        )

        Route.Chat -> ChatScreen(
            state = state,
            onBack = { route = Route.Analysis },
            onAsk = viewModel::ask,
        )

        Route.Settings -> SettingsScreen(
            settings = state.settings,
            statusLine = state.statusLine,
            llmConfigured = state.settings.llmConfigured,
            onSave = { viewModel.saveSettings(it); route = if (state.hasPhoto) Route.Analysis else Route.Capture },
            onTestLlm = viewModel::testLlm,
            onTestSam = viewModel::testSam,
            onBack = { route = if (state.hasPhoto) Route.Analysis else Route.Capture },
        )
    }
}
