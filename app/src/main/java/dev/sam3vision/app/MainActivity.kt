package dev.sam3vision.app

import android.Manifest
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
 * Activité unique : Compose + navigation par état, thème sombre.
 *
 * La racine n'est pas le ComposeView mais [LongPressRootLayout] : c'est elle qui
 * voit chaque geste de la surface de l'app et ouvre la dictée après 300 ms d'appui
 * maintenu, quel que soit l'écran affiché.
 */
class MainActivity : ComponentActivity() {

    private lateinit var viewModel: AppViewModel

    /** Permission demandée au premier lancement ; un refus est annoncé à la voix (bandeau + ElevenLabs). */
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) viewModel.onMicDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[AppViewModel::class.java]
        val root = LongPressRootLayout(this).apply {
            onHoldStart = viewModel::onHoldStart
            onHoldEnd = viewModel::onHoldEnd
            addView(
                ComposeView(this@MainActivity).apply { setContent { SamRoot(viewModel) } },
                MATCH_PARENT,
                MATCH_PARENT,
            )
        }
        setContentView(root)
        requestMicIfNeeded()
    }

    private fun requestMicIfNeeded() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
}

/** Thème + navigation : une seule racine Compose sous [LongPressRootLayout]. */
@Composable
private fun SamRoot(viewModel: AppViewModel) {
    MaterialTheme(colorScheme = SamColorScheme) {
        Surface(color = AppColors.background) {
            AppNav(viewModel = viewModel)
        }
    }
}