package dev.sam3vision.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import dev.sam3vision.app.glasses.GlassesController
import dev.sam3vision.app.ihear.IhearViewModel

private val SamColorScheme = darkColorScheme(
    primary = AppColors.primary,
    onPrimary = Color.White,
    secondary = AppColors.success,
    background = AppColors.background,
    surface = AppColors.surface,
    onBackground = AppColors.text,
    onSurface = AppColors.text,
    error = AppColors.danger,
    onError = Color.White,
)

/**
 * Activité unique : Compose + navigation par état, thème sombre pour les écrans
 * d'origine, iSee en page d'accueil, lunettes Ray-Ban Meta.
 *
 * La racine n'est pas le ComposeView mais [IhearRootLayout] : elle voit chaque geste
 * de la surface de l'app — appui long (micro maintenu, transcription Muse), tap
 * simple (vidéo), double tap (photo), balayages horizontal (page) et vertical
 * (sélection de bulle, avec haptique dédiée). Les gestes ne s'appliquent que quand
 * les panneaux iSee sont à l'écran.
 */
class MainActivity : ComponentActivity() {

    private lateinit var viewModel: AppViewModel
    private lateinit var ihear: IhearViewModel
    private lateinit var glasses: GlassesController

    /** Permission demandée au premier lancement ; un refus est annoncé à la voix. */
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) viewModel.onMicDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        glasses = GlassesController(applicationContext).also {
            it.bind(this)
            it.initialize()
        }
        viewModel = ViewModelProvider(this)[AppViewModel::class.java]
        ihear = ViewModelProvider(this)[IhearViewModel::class.java]
        setContentView(buildRoot())
        requestMicIfNeeded()
        routeIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeIntent(intent)
    }

    /** Callbacks de l'app Meta AI (enregistrement, invocation vocale) passés au SDK. */
    private fun routeIntent(intent: Intent) {
        glasses.handleIntent(intent)
    }

    private fun buildRoot() = IhearRootLayout(this).apply {
        gesturesEnabled = { ihear.active }
        onHoldStart = ihear::onHoldStart
        onHoldEnd = ihear::onHoldEnd
        onSingleTap = ihear::onSingleTap
        onDoubleTap = ihear::onDoubleTap
        onVerticalDragStart = ihear::onVerticalDragStart
        onVerticalDragMove = { downward -> ihear.onVerticalDragMove(downward) }
        onVerticalSwipeEnd = { committed, downward -> ihear.onVerticalSwipeEnd(committed, downward) }
        addView(
            ComposeView(this@MainActivity).apply { setContent { SamRoot(viewModel, ihear, glasses) } },
            MATCH_PARENT,
            MATCH_PARENT,
        )
    }

    /** Option C : minuteur d'inactivité démarré / annulé autour de l'arrière-plan. */
    override fun onPause() {
        ihear.onBackground()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        ihear.onForeground()
    }

    /** Option A : purge totale de la table Room et du cache à la fermeture. */
    override fun onDestroy() {
        ihear.onAppClosed()
        if (isFinishing) glasses.close()
        super.onDestroy()
    }

    private fun requestMicIfNeeded() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
}

/** Thème + navigation : une seule racine Compose sous [IhearRootLayout]. */
@Composable
private fun SamRoot(viewModel: AppViewModel, ihear: IhearViewModel, glasses: GlassesController) {
    MaterialTheme(colorScheme = SamColorScheme) {
        Surface(color = AppColors.background) {
            AppNav(viewModel = viewModel, ihear = ihear, glasses = glasses)
        }
    }
}
