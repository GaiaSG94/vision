package dev.sam3vision.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Page Caregiver : la carte de suivi du proche, telle que la page du site la
 * dessine (point et objets sur la carte, badge LIVE / SIGNAL LOST / OFFLINE,
 * bandeau sous la carte, zoom au pincement et boutons +/−).
 *
 * C'est **la même page** que celle envoyée par WhatsApp, tenue dans une WebView :
 * la carte et ses gestes sont ceux du site, donc la page évolue sans reconstruire
 * l'app, et aucun SDK de cartes n'entre dans le téléphone. [link] est le lien du
 * flux (`…?share=<identifiant>`), construit par [shareLink].
 */
@Composable
fun CaregiverScreen(link: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var loading by remember(link) { mutableStateOf(link.isNotBlank()) }
    var error by remember(link) { mutableStateOf<String?>(null) }
    // La WebView est créée pendant la composition, jamais dans le `factory` d'AndroidView :
    // y toucher à l'état de composition la faisait détruire aussitôt (fenêtre noire).
    val web = remember(link) {
        if (link.isBlank()) {
            null
        } else {
            caregiverWeb(context, link, onLoading = { loading = it }, onError = { error = it })
        }
    }
    DisposableEffect(web) {
        onDispose {
            web?.stopLoading()
            web?.destroy()
        }
    }
    Column(
        Modifier.fillMaxSize().background(AppColors.background)
            .statusBarsPadding().navigationBarsPadding(),
    ) {
        CaregiverBar(
            onBack = onBack,
            onReload = {
                error = null
                loading = true
                web?.reload()
            },
        )
        if (web == null) {
            CaregiverMissingLink()
        } else {
            CaregiverWeb(web, loading, error, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

/** Barre de la page : retour à l'app et rafraîchissement de la carte. */
@Composable
private fun CaregiverBar(onBack: () -> Unit, onReload: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedButton(onBack) { Text("Retour", fontSize = 13.sp) }
        Text(
            "Caregiver",
            color = AppColors.text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onReload) { Text("Actualiser", fontSize = 13.sp) }
    }
}

/** Lien vide (réglage effacé) : on le dit, au lieu d'une page blanche. */
@Composable
private fun CaregiverMissingLink() {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Aucun lien de suivi", color = AppColors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            "Renseigne le lien du site et l'identifiant du flux dans Réglages → Suivi parent, " +
                "puis reviens sur cette page.",
            color = AppColors.textMuted,
            fontSize = 13.sp,
        )
    }
}

/**
 * La page du site, telle quelle : JavaScript et stockage local, fond noir.
 * Avant, une carte qui ne chargeait pas (hors ligne, site en panne) restait une
 * fenêtre noire sans un mot : l'état de chargement et l'erreur s'affichent.
 */
@Composable
private fun CaregiverWeb(web: WebView, loading: Boolean, error: String?, modifier: Modifier) {
    Box(modifier) {
        AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AppColors.primary)
            }
        }
        if (error != null && !loading) {
            Column(
                Modifier.fillMaxSize().padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Carte indisponible", color = AppColors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(error, color = AppColors.textMuted, fontSize = 13.sp)
            }
        }
    }
}

/** Client qui éteint le sablier et traduit un échec en phrase lisible. */
private class CaregiverClient(
    private val onLoading: (Boolean) -> Unit,
    private val onError: (String?) -> Unit,
) : WebViewClient() {
    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        onError(null)
        onLoading(true)
    }

    override fun onPageFinished(view: WebView, url: String) {
        onLoading(false)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) {
            onLoading(false)
            onError("La page n'a pas chargé (${error.errorCode}) : vérifie la connexion, puis « Actualiser ».")
        }
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        if (request.isForMainFrame) {
            onLoading(false)
            onError("Le site répond ${response.statusCode} : le lien de suivi est peut-être faux, puis « Actualiser ».")
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun caregiverWeb(
    context: Context,
    link: String,
    onLoading: (Boolean) -> Unit,
    onError: (String?) -> Unit,
): WebView = WebView(context).apply {
    setBackgroundColor(Color.BLACK)
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    // La carte du site se zoome au pincement : la WebView ne doit pas le bloquer.
    settings.setSupportZoom(true)
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    settings.loadWithOverviewMode = true
    settings.useWideViewPort = true
    webViewClient = CaregiverClient(onLoading, onError)
    loadUrl(link)
}
