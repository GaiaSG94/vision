package dev.sam3vision.core.net

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        private var onOpen: (() -> Unit)? = null
        var opened = false
            private set

        override fun open(
            url: String,
            onText: (String) -> Unit,
            onClosed: (Throwable?) -> Unit,
            onOpen: () -> Unit,
        ): Boolean {
            openedUrl = url
            this.onText = onText
            this.onClosed = onClosed
            this.onOpen = onOpen
            if (openResult) {
                opened = true
                onOpen()          // le handshake aboutit : le client peut dater les « en-têtes »
            }
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
    fun `les jalons reseau datent en-tetes puis corps`() = runBlocking {
        // Le transport appelle onOpen au handshake et le client onBody à la réception :
        // la chronologie de l'écran peut alors séparer l'attente de la descente.
        val seen = mutableListOf<String>()
        val marks = object : WireMarks {
            override fun onHeaders() { seen += "headers" }
            override fun onBody() { seen += "body" }
        }
        val transport = FakeTransport { json -> listOf(frameReply(json.getInt("id"))) }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient(), marks = marks)

        client.live("QQ==", null, 640, 4).getOrThrow()

        assertEquals(listOf("headers", "body"), seen)
    }

    @Test
    fun `un message orphelin ne pose pas de jalon corps`() = runBlocking {
        val seen = mutableListOf<String>()
        val marks = object : WireMarks {
            override fun onHeaders() { seen += "headers" }
            override fun onBody() { seen += "body" }
        }
        // Réponse portant un id qu'aucune requête n'attend : ignorée, donc non datée.
        val transport = FakeTransport { listOf("{\"id\":99,\"elapsed_ms\":1}") }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient(), marks = marks)

        client.live("QQ==", null, 640, 4)

        assertEquals(listOf("headers"), seen)
    }

    @Test
    fun `le message WS demande des masques bits, comme le POST`() = runBlocking {
        // Un seul corps de frame pour les deux transports : le champ `mask_encoding` ne
        // doit pas exister sur l'un et pas sur l'autre (sinon le WS resterait en PNG).
        val transport = FakeTransport { json -> listOf(frameReply(json.getInt("id"))) }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient())

        client.live("QQ==", null, 640, 4).getOrThrow()

        val sent = JSONObject(transport.sent.single())
        assertEquals("bits", sent.getString("mask_encoding"))
        assertTrue("prompts null doit rester null", sent.isNull("prompts"))
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
    fun `la reponse porte l encodage applique et les chronos serveur`() = runBlocking {
        val transport = FakeTransport { json ->
            listOf(
                """{"id":${json.getInt("id")},"width":512,"height":1138,"elapsed_ms":88,
                    "mask_encoding":"bits","timings_ms":{"vision":41.5,"total":88.2},
                    "objects":[{"label":"person","score":0.9,"box":[1,2,3,4],"mask_b64":"TTEIAAIA"}]}""",
            )
        }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient())

        val frame = client.live("QQ==", listOf("person"), 640, 4).getOrThrow()

        assertEquals("bits", frame.maskEncoding)
        assertEquals(41.5, frame.timingsMs.getValue("vision"), 0.0001)
        assertEquals(88L, frame.elapsedMs)
    }

    /** Backend sans `FAST_TIMING` : pas de `timings_ms`, et `mask_encoding` absent ⇒ `png`. */
    @Test
    fun `une reponse sans champs optionnels reste lisible`() = runBlocking {
        val transport = FakeTransport { json -> listOf(frameReply(json.getInt("id"))) }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient())

        val frame = client.live("QQ==", null, 640, 4).getOrThrow()

        assertEquals("png", frame.maskEncoding)
        assertTrue(frame.timingsMs.isEmpty())
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

    @Test
    fun `isConnected_reflete_l_etat_du_socket`() = runBlocking {
        val transport = FakeTransport(fun(json: JSONObject): List<String> = listOf(frameReply(json.getInt("id"))))
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient())

        assertFalse(client.isConnected)
        client.live("AA==", listOf("person"), 640, 4).getOrThrow()
        assertTrue(client.isConnected)

        client.close()
        assertFalse(client.isConnected)
    }

    @Test
    fun `un silence du serveur referme le socket pour que la frame suivante le rouvre`() = runBlocking {
        var opens = 0
        val transport = object : WsTransport {
            override fun open(
                url: String,
                onText: (String) -> Unit,
                onClosed: (Throwable?) -> Unit,
                onOpen: () -> Unit,
            ): Boolean {
                opens += 1
                return true
            }

            override fun send(text: String): Boolean = true

            override fun close() {}
        }
        val client = WsLiveClient(base, transport, timeoutMs = 120, http = RecordingLiveClient())

        assertTrue(client.live("QQ==", listOf("person"), 640, 4).exceptionOrNull() is ApiError.Timeout)
        assertFalse("le socket muet doit être considéré fermé", client.isConnected)
        assertEquals(1, opens)

        client.live("QQ==", listOf("person"), 640, 4)
        assertEquals("la frame suivante doit retenter un handshake", 2, opens)
    }
}
