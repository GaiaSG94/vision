package dev.sam3vision.core.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bascule WebSocket → HTTP : le repli est décidé une fois, puis mémorisé. */
class AutoLiveClientTest {

    private val wsDown = { RecordingLiveClient { Result.failure(ApiError.Network("socket fermé")) } }
    private val httpUp = { RecordingLiveClient { Result.success(SAMPLE_FRAME) } }

    @Test
    fun `le WebSocket en echec bascule sur le HTTP et le resultat est un succes`() = runBlocking {
        val ws = wsDown()
        val http = httpUp()
        val client = AutoLiveClient(ws, http)

        val frame = client.live("QQ==", listOf("person"), 640, 4).getOrThrow()

        assertEquals(SAMPLE_FRAME, frame)
        assertEquals(1, ws.liveCalls)
        assertEquals(1, http.liveCalls)
        assertTrue(client.usingHttp)
        assertEquals("socket fermé", client.fallbackReason)
    }

    @Test
    fun `le repli HTTP est memorise pour la suite de la session`() = runBlocking {
        val ws = wsDown()
        val http = httpUp()
        val client = AutoLiveClient(ws, http)

        client.live("QQ==", listOf("person"), 640, 4).getOrThrow()
        client.live("QQ==", listOf("person"), 640, 4).getOrThrow()

        assertEquals("une seule tentative WebSocket", 1, ws.liveCalls)
        assertEquals(2, http.liveCalls)
    }

    @Test
    fun `le WebSocket qui reussit n appelle pas le HTTP`() = runBlocking {
        val ws = RecordingLiveClient()
        val http = httpUp()
        val client = AutoLiveClient(ws, http)

        client.live("QQ==", listOf("person"), 640, 4).getOrThrow()

        assertEquals(1, ws.liveCalls)
        assertEquals(0, http.liveCalls)
        assertFalse(client.usingHttp)
        assertNull(client.fallbackReason)
    }

    @Test
    fun `un delai depasse bascule aussi sur le HTTP`() = runBlocking {
        val ws = RecordingLiveClient { Result.failure(ApiError.Timeout()) }
        val http = httpUp()

        AutoLiveClient(ws, http).live("QQ==", listOf("person"), 640, 4).getOrThrow()

        assertEquals(1, http.liveCalls)
    }

    @Test
    fun `une erreur applicative ne bascule pas`() = runBlocking {
        val ws = RecordingLiveClient { Result.failure(ApiError.BadResponse("prompts inconnus")) }
        val http = httpUp()
        val client = AutoLiveClient(ws, http)

        val failure = client.live("QQ==", listOf("person"), 640, 4).exceptionOrNull()

        assertTrue(failure is ApiError.BadResponse)
        assertEquals(0, http.liveCalls)
        assertFalse(client.usingHttp)
    }

    @Test
    fun `si les deux transports echouent le message nomme les deux`() = runBlocking {
        val ws = wsDown()
        val http = RecordingLiveClient { Result.failure(ApiError.Timeout()) }
        val client = AutoLiveClient(ws, http)

        val failure = client.live("QQ==", listOf("person"), 640, 4).exceptionOrNull()

        assertTrue("attendu Network, obtenu $failure", failure is ApiError.Network)
        assertTrue(failure?.message.orEmpty().contains("socket fermé"))
        assertTrue(failure?.message.orEmpty().contains("délai dépassé"))
    }

    @Test
    fun `forceHttp demarre directement en HTTP`() = runBlocking {
        val ws = RecordingLiveClient()
        val http = httpUp()
        val client = AutoLiveClient(ws, http, forceHttp = true)

        client.live("QQ==", listOf("person"), 640, 4).getOrThrow()

        assertEquals(0, ws.liveCalls)
        assertEquals(1, http.liveCalls)
        assertTrue(client.usingHttp)
    }

    @Test
    fun `health interroge le client HTTP`() = runBlocking {
        val ws = RecordingLiveClient()
        val http = httpUp()
        val client = AutoLiveClient(ws, http)

        assertEquals(SAMPLE_HEALTH, client.health().getOrThrow())
        assertEquals(0, ws.healthCalls)
        assertEquals(1, http.healthCalls)
    }

    @Test
    fun `close ferme les deux transports`() {
        val ws = RecordingLiveClient()
        val http = httpUp()

        AutoLiveClient(ws, http).close()

        assertTrue(ws.closed)
        assertTrue(http.closed)
    }
}
