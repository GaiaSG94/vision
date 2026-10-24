package dev.sam3vision.app

import androidx.activity.compose.BackHandler
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

/** Racine de navigation : pile de routes + distribution de l'état du [AppViewModel]. */
@Composable
fun AppNav(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsState()
    var stack by remember { mutableStateOf(listOf<Route>(Route.Capture)) }
    val route = stack.first()
    val push: (Route) -> Unit = { stack = listOf(it) + stack }
    val pop: () -> Unit = { if (stack.size > 1) stack = stack.drop(1) }
    BackHandler(enabled = stack.size > 1) { pop() }

    when (route) {
        Route.Capture -> CaptureScreen(
            onPhoto = { file ->
                viewModel.onPhoto(file)
                push(Route.Analysis)
            },
            onOpenSettings = { push(Route.Settings) },
        )

        Route.Analysis -> AnalysisScreen(
            state = state,
            onBack = pop,
            onOpenChat = { push(Route.Chat) },
            onToggleMasks = viewModel::toggleMasks,
            onRetry = viewModel::retryAnalysis,
            onOpenSettings = { push(Route.Settings) },
        )

        Route.Chat -> ChatScreen(
            state = state,
            onBack = pop,
            onAsk = viewModel::ask,
            onStartDictation = viewModel::startDictation,
            onStopDictation = viewModel::stopDictation,
        )

        Route.Settings -> SettingsScreen(
            settings = state.settings,
            statusLine = state.statusLine,
            llmConfigured = state.settings.llmConfigured,
            onSave = { viewModel.saveSettings(it); pop() },
            onTestLlm = viewModel::testLlm,
            onTestSam = viewModel::testSam,
            onTestAsr = viewModel::testAsr,
            onBack = pop,
        )
    }
}
