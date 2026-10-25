package dev.sam3vision.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La fabrique partagée des deux écrans qui parlent à SAM : un seul transport (le
 * WebSocket) et **le même délai pour le handshake que pour la frame**. Sans cela, un
 * conteneur endormi (26-50 s de démarrage) faisait échouer le handshake à 10 s.
 */
class LiveClientFactoryTest {

    @Test
    fun `le delai demande s applique au handshake comme a la frame`() {
        val seen = mutableListOf<Long>()
        val client = liveClientWith(
            baseUrl = "https://exemple.invalid",
            timeoutSeconds = 60L,
            transportFor = { milliseconds ->
                seen += milliseconds
                FailingWsTransport()
            },
        )
        assertTrue(client is WsLiveClient)
        assertEquals(listOf(60_000L), seen)
    }

    @Test
    fun `la fabrique par defarde garde le delai court du direct`() {
        val client = liveClient("https://exemple.invalid")
        assertTrue(client is WsLiveClient)
        client.close()
    }
}

/** Transport qui ne se connecte jamais : sertOnly à vérifier les paramètres reçus. */
private class FailingWsTransport : WsTransport {
    override fun open(url: String, onText: (String) -> Unit, onClosed: (Throwable?) -> Unit): Boolean = false

    override fun send(text: String): Boolean = false

    override fun close() {}
}
