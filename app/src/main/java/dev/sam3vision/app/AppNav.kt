package dev.sam3vision.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.sam3vision.app.tap.TapCaptureScreen

/** Routes de l'app (navigation par état, sans dépendance navigation-compose). */
sealed interface Route {
    data object Capture : Route
    data object Analysis : Route
    data object Chat : Route
    data object Live : Route
    data object Settings : Route

    /**
     * Mode vidéo (tap court) : vidéo → SAM 3.1, annonces ElevenLabs.
     * [autoStart] = lancé par un tap sur l'aperçu de l'accueil : la session démarre
     * d'elle-même, sans deuxième tap.
     */
    data class Tap(val autoStart: Boolean = false) : Route
}

/**
 * Racine de navigation : pile de routes + distribution de l'état du [AppViewModel].
 * Le bandeau de dictée vit au-dessus de toutes les routes : il s'affiche depuis
 * n'importe quel écran, même après un appui long.
 */
@Composable
fun AppNav(viewModel: AppViewModel, liveViewModel: LiveViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    var stack by remember { mutableStateOf(listOf<Route>(Route.Capture)) }
    val route = stack.first()
    val push: (Route) -> Unit = { stack = listOf(it) + stack }
    val pop: () -> Unit = { if (stack.size > 1) stack = stack.drop(1) }
    BackHandler(enabled = stack.size > 1) { pop() }

    Box(Modifier.fillMaxSize()) {
        RouteContent(route, state, viewModel, liveViewModel, push, pop)
        state.dictation.overlay?.let { overlay ->
            DictationOverlay(overlay = overlay, onDismiss = viewModel::dismissOverlay)
        }
    }
}

/** L'écran demandé ; le bandeau de dictée est dessiné par-dessus, dans [AppNav]. */
@Composable
private fun RouteContent(
    route: Route,
    state: UiState,
    viewModel: AppViewModel,
    liveViewModel: LiveViewModel,
    push: (Route) -> Unit,
    pop: () -> Unit,
) {
    when (route) {
        Route.Capture -> CaptureRoute(viewModel, push)
        Route.Analysis -> AnalysisRoute(state, viewModel, push, pop)
        Route.Live -> LiveRoute(viewModel, liveViewModel, push, pop)
        Route.Chat -> ChatRoute(state, viewModel, pop)
        is Route.Tap -> TapCaptureScreen(state.settings, pop, route.autoStart)
        Route.Settings -> SettingsRoute(viewModel, state, pop)
    }
}

/** Accueil : photo → analyse, ouverture des réglages, mode tap, mode direct. */
@Composable
private fun CaptureRoute(viewModel: AppViewModel, push: (Route) -> Unit) {
    CaptureScreen(
        onPhoto = { file ->
            viewModel.onPhoto(file)
            push(Route.Analysis)
        },
        onOpenSettings = { push(Route.Settings) },
        onOpenTap = { startNow ->
            viewModel.stopSpeaking() // aucune voix du chat ne doit couvrir les annonces tap
            push(Route.Tap(autoStart = startNow))
        },
        onOpenLive = { push(Route.Live) },
    )
}

@Composable
private fun AnalysisRoute(
    state: UiState,
    viewModel: AppViewModel,
    push: (Route) -> Unit,
    pop: () -> Unit,
) {
    AnalysisScreen(
        state = state,
        onBack = pop,
        onOpenChat = { push(Route.Chat) },
        onToggleMasks = viewModel::toggleMasks,
        onRetry = viewModel::retryAnalysis,
        onSpeak = viewModel::speak,
        onStopVoice = viewModel::stopSpeaking,
        onOpenSettings = { push(Route.Settings) },
    )
}

/** Direct : le flux a son propre ViewModel ; une question part au chat avec sa frame. */
@Composable
private fun LiveRoute(
    viewModel: AppViewModel,
    liveViewModel: LiveViewModel,
    push: (Route) -> Unit,
    pop: () -> Unit,
) {
    LiveScreen(
        viewModel = liveViewModel,
        onBack = { liveViewModel.stop(); pop() },
        onAsk = {
            viewModel.useLiveFrame(liveViewModel.state.value.lastJpegB64)
            push(Route.Chat)
        },
        onOpenSettings = { push(Route.Settings) },
    )
}

@Composable
private fun ChatRoute(state: UiState, viewModel: AppViewModel, pop: () -> Unit) {
    ChatScreen(
        state = state,
        onBack = pop,
        onAsk = viewModel::ask,
        onSpeak = viewModel::speak,
        onStopVoice = viewModel::stopSpeaking,
        onStartDictation = viewModel::startDictation,
        onStopDictation = viewModel::stopDictation,
    )
}

/** Réglages : délègue au ViewModel et ferme l'écran après enregistrement. */
@Composable
private fun SettingsRoute(viewModel: AppViewModel, state: UiState, pop: () -> Unit) {
    SettingsScreen(
        settings = state.settings,
        statusLine = state.statusLine,
        llmConfigured = state.settings.llmConfigured,
        speaking = state.speak,
        onSave = { viewModel.saveSettings(it); pop() },
        onTestLlm = viewModel::testLlm,
        onTestSam = viewModel::testSam,
        onTestAsr = viewModel::testAsr,
        onSpeak = viewModel::speak,
        onStopVoice = viewModel::stopSpeaking,
        onBack = pop,
    )
}
