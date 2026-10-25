package dev.sam3vision.core.net

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Direct par WebSocket, testé **sans réseau** : [FakeTransport] capture les messages
 * envoyés et injecte les réponses du serveur. Le serveur répond de façon synchrone dans
 * `send`, exactement comme un socket chaud — le client enregistre l'attente avant d'envoyer.
 */
class WsLiveClientTest {

    private val base = "https://sam.example"

    private fun frameReply(id: Int, width: Int = 512): String = """
        {"id":$id,"width":$width,"height":1138,"elapsed_ms":118,"objects":[
          {"label":"person","score":0.91,"box":[1,2,30,40],"mask_b64":"QUJD"}
        ]}
    """.trimIndent()

    private fun errorReply(id: Int): String =
        """{"id":$id,"error":{"status":400,"detail":"prompts inconnus"}}"""

    /** Transport sans réseau : messages capturés, réponses injectées par [reply]. */
    private class FakeTransport(
        private val reply: (JSONObject) -> List<String> = { emptyList() },
    ) : WsTransport {

        val sent = mutableListOf<String>()
        var openedUrl: String? = null
        var openResult = true
        var sendResult = true
        var closed = false
            private set
        private var onText: ((String) -> Unit)? = null
        private var onClosed: ((Throwable?) -> Unit)? = null

        override fun open(url: String, onText: (String) -> Unit, onClosed: (Throwable?) -> Unit): Boolean {
            openedUrl = url
            this.onText = onText
            this.onClosed = onClosed
            return openResult
        }

        override fun send(text: String): Boolean {
            sent += text
            if (!sendResult) return false
            reply(JSONObject(text)).forEach { message -> onText?.invoke(message) }
            return true
        }

        override fun close() {
            closed = true
        }

        /** Fermeture côté serveur (ou coupure réseau) pendant qu'une frame est en vol. */
        fun disconnect(error: Throwable? = null) = onClosed?.invoke(error)
    }

    @Test
    fun `le message envoye respecte le contrat`() = runBlocking {
        val transport = FakeTransport { json -> listOf(frameReply(json.getInt("id"))) }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient())

        client.live("SUVLFw==", listOf("person", "door"), 640, 4).getOrThrow()

        val sent = JSONObject(transport.sent.single())
        assertEquals("SUVLFw==", sent.getString("image_b64"))
        assertEquals("person", sent.getJSONArray("prompts").getString(0))
        assertEquals("door", sent.getJSONArray("prompts").getString(1))
        assertEquals(4, sent.getInt("max_objects"))
        assertEquals(640, sent.getInt("work_edge"))
        assertEquals(1, sent.getInt("id"))
        assertEquals("wss://sam.example/live/ws", transport.openedUrl)
    }

    @Test
    fun `la reponse du serveur devient une LiveFrame`() = runBlocking {
        val transport = FakeTransport { json -> listOf(frameReply(json.getInt("id"))) }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient())

        val frame = client.live("QQ==", listOf("person"), 640, 4).getOrThrow()

        assertEquals(512, frame.width)
        assertEquals(1138, frame.height)
        assertEquals(118L, frame.elapsedMs)
        val obj = frame.objects.single()
        assertEquals("person", obj.label)
        assertEquals(0.91f, obj.score, 0.001f)
        assertEquals(listOf(1f, 2f, 30f, 40f), obj.box)
        assertEquals("QUJD", obj.maskPngB64)
    }

    @Test
    fun `un id non correle est ignore`() = runBlocking {
        // Réponse hors sujet (id inconnu, largeur 999) puis la bonne : seule la seconde compte.
        val transport = FakeTransport { json ->
            val id = json.getInt("id")
            listOf(frameReply(id + 100, width = 999), frameReply(id))
        }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient())

        val frame = client.live("QQ==", listOf("person"), 640, 4).getOrThrow()

        assertEquals(512, frame.width)
    }

    @Test
    fun `un message d erreur du serveur donne BadResponse`() = runBlocking {
        val transport = FakeTransport { json -> listOf(errorReply(json.getInt("id"))) }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient())

        val failure = client.live("QQ==", listOf("person"), 640, 4).exceptionOrNull()

        assertTrue("attendu BadResponse, obtenu $failure", failure is ApiError.BadResponse)
        assertTrue(failure?.message.orEmpty().contains("prompts inconnus"))
    }

    @Test
    fun `sans reponse le delai est depasse`() = runBlocking {
        val transport = FakeTransport()
        val client = WsLiveClient(base, transport, timeoutMs = 120, http = RecordingLiveClient())

        val failure = client.live("QQ==", listOf("person"), 640, 4).exceptionOrNull()

        assertTrue("attendu Timeout, obtenu $failure", failure is ApiError.Timeout)
    }

    @Test
    fun `une fermeture du socket fait echouer la frame en vol`() = runBlocking {
        var transport: FakeTransport? = null
        transport = FakeTransport { _ ->
            transport?.disconnect(IOException("coupure"))
            emptyList()
        }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient())

        val failure = client.live("QQ==", listOf("person"), 640, 4).exceptionOrNull()

        assertTrue("attendu Network, obtenu $failure", failure is ApiError.Network)
    }

    @Test
    fun `un socket qui ne s ouvre pas donne Network`() = runBlocking {
        val transport = FakeTransport().apply { openResult = false }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient())

        val failure = client.live("QQ==", listOf("person"), 640, 4).exceptionOrNull()

        assertTrue("attendu Network, obtenu $failure", failure is ApiError.Network)
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `close ferme le transport`() {
        val transport = FakeTransport()
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient())

        client.close()

        assertTrue(transport.closed)
    }

    @Test
    fun `health est delegue au client HTTP`() = runBlocking {
        val http = RecordingLiveClient()
        val client = WsLiveClient(base, FakeTransport(), 1_000, http)

        val health = client.health().getOrThrow()

        assertEquals(SAMPLE_HEALTH, health)
        assertEquals(1, http.healthCalls)
    }
}
