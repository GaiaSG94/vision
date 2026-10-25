package dev.sam3vision.core.net

/**
 * Client du direct : **WebSocket uniquement**, avec le délai qu'on veut.
 *
 * `master` a abandonné `POST /live` pour les frames : un seul handshake par session et
 * une trame par frame au lieu de deux (~31 ms par frame). Le client HTTP ne reste que
 * pour `/health` (réveil du conteneur), délégué par [WsLiveClient].
 *
 * Le délai du **handshake** suit celui de la frame : mesuré sur le téléphone, un
 * transport figé à 10 s échouait pendant les 26-50 s de réveil du conteneur. Une seule
 * fabrique pour les deux écrans qui parlent à SAM (Direct et page caméra iSee).
 */
fun liveClient(
    baseUrl: String,
    timeoutSeconds: Long = LIVE_TIMEOUT_SECONDS,
): LiveClient = liveClientWith(baseUrl, timeoutSeconds) { milliseconds ->
    OkHttpWsTransport(defaultHttpClient(milliseconds / 1000))
}

/** Fabrique interne, avec le transport injectable : testée sans réseau ni GPU. */
internal fun liveClientWith(
    baseUrl: String,
    timeoutSeconds: Long,
    transportFor: (Long) -> WsTransport,
): LiveClient = WsLiveClient(
    baseUrl,
    transport = transportFor(timeoutSeconds * 1000),
    timeoutMs = timeoutSeconds * 1000,
    http = HttpLiveClient(baseUrl, client = defaultHttpClient(timeoutSeconds)),
)
