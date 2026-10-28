package dev.sam3vision.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.sam3vision.core.settings.Settings
import java.net.URLEncoder

/**
 * Partage le suivi parent : ouvre WhatsApp avec le lien du site pré-rempli.
 * Sans WhatsApp, bascule sur wa.me dans le navigateur, puis sur l'app Messages
 * (SMS), puis sur le sélecteur Android générique. Lien générique (Réglages) :
 * pas de cloisonnement par proche.
 */
fun shareParentSite(context: Context, siteUrl: String, shareId: String) {
    val link = shareLink(siteUrl, shareId)
    val text = "Suivi de Léo en direct : $link"
    val whatsapp = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .setPackage("com.whatsapp")
        .putExtra(Intent.EXTRA_TEXT, text)
    if (startShare(context, whatsapp)) return
    val encoded = URLEncoder.encode(text, Charsets.UTF_8.name())
    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/?text=$encoded"))
    if (startShare(context, web)) return
    // Ni WhatsApp ni navigateur : bascule sur l'app Messages (SMS, destinataire à choisir).
    val sms = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))
        .putExtra("sms_body", text)
    if (startShare(context, sms)) return
    val chooser = Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
        "Partager le suivi",
    )
    startShare(context, chooser)
}

/**
 * Identifiant de flux assaini : le relais n'accepte que `[a-z0-9-]{1,32}`
 * (`validId` côté site) et lit `?share=` en minuscules. Sans ça, « Léo » ou
 * « leo_1 » partait en POST mais répondait 400, pendant que la page lisait un
 * autre flux — suivi noir des deux côtés, sans un mot d'erreur.
 */
fun normalizeShareId(raw: String): String {
    val ascii = java.text.Normalizer.normalize(raw.trim().lowercase(), java.text.Normalizer.Form.NFD)
    return ascii.replace(Regex("[\u0300-\u036f]"), "")
}

/**
 * URL du relais (`POST /api/share`), même origine que la page (pas de CORS).
 * Un champ vide retombe sur le site par défaut : avant, `"" + "/api/share"`
 * donnait le chemin relatif `/api/share`, et l'envoi mourait dans OkHttp.
 */
fun relayUrl(siteUrl: String): String =
    siteUrl.ifBlank { Settings.DEFAULT_PARENT_SITE_URL }.trim().trimEnd('/') + SHARE_PATH

/** Chemin du relais sur le site parent (même origine, pas de CORS). */
const val SHARE_PATH = "/api/share"

/**
 * Lien envoyé au proche : le site lit `?share=` pour ouvrir le bon flux.
 * Un lien déjà paramétré (token manuel) est conservé tel quel.
 */
fun shareLink(siteUrl: String, shareId: String): String {
    val base = siteUrl.ifBlank { Settings.DEFAULT_PARENT_SITE_URL }
    val id = normalizeShareId(shareId).ifBlank { Settings.DEFAULT_SHARE_ID }
    if (base.contains("?share=") || base.contains("&share=")) return base
    val sep = if (base.contains("?")) "&" else "?"
    return base.trim().trimEnd('/') + sep + "share=" + id
}

/** Lance un partage ; `false` si aucune app ne le prend en charge. */
private fun startShare(context: Context, intent: Intent): Boolean {
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
