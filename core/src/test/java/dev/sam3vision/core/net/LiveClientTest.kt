package dev.sam3vision.core.net

import dev.sam3vision.core.TestHttpServer
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.LiveHealth
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveClientTest {

    private val validLive = """
        {"width":640,"height":480,"elapsed_ms":512,"objects":[
          {"label":"person","score":0.91,"box":[1,2,30,40],"mask_b64":"QUJD"},
          {"label":"dog","score":0.42,"box":[5,6,7,8],"mask_b64":"RERG"}
        ]}
    """.trimIndent()

    private var liveBody = validLive
    private var liveCode = 200

    private val server = TestHttpServer { request ->
        when (request.path) {
            "/live" -> TestHttpServer.Response(liveCode, if (liveCode == 200) liveBody else """{"detail":"bad"}""")
            "/health" -> TestHttpServer.Response(200, """{"ok":true,"model":"sam3.1","device":"cuda","gpu":"A100"}""")
            else -> TestHttpServer.Response(404, "{}")
        }
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `live parse une reponse valide`() = runBlocking {
        val frame = HttpLiveClient(server.baseUrl).live("SUVLFw==", listOf("person", "dog"), 640, 12).getOrThrow()

        assertEquals(640, frame.width)
        assertEquals(480, frame.height)
        assertEquals(512L, frame.elapsedMs)
        assertEquals(2, frame.objects.size)
        assertEquals("person", frame.objects[0].label)
        assertEquals(0.91f, frame.objects[0].score, 0.001f)
        assertEquals(listOf(1f, 2f, 30f, 40f), frame.objects[0].box)
        assertEquals("QUJD", frame.objects[0].maskPngB64)
        assertEquals("RERG", frame.objects[1].maskPngB64)
    }

    @Test
    fun `live envoie le corps attendu avec un User-Agent propre`() = runBlocking {
        HttpLiveClient(server.baseUrl).live("SUVLFw==", listOf("person"), 512, 6).getOrThrow()
        val request = server.requests.single()
        assertEquals("/live", request.path)
        assertEquals("POST", request.method)
        assertEquals(LIVE_USER_AGENT, request.headers["user-agent"])
        assertTrue(!request.headers.containsKey("authorization"))
        val sent = JSONObject(request.body)
        assertEquals("SUVLFw==", sent.getString("image_b64"))
        assertEquals("person", sent.getJSONArray("prompts").getString(0))
        assertEquals(512, sent.getInt("work_edge"))
        assertEquals(6, sent.getInt("max_objects"))
    }

    @Test
    fun `live prompts null part a null dans le corps`() = runBlocking {
        HttpLiveClient(server.baseUrl).live("QQ==", null, 640, 12).getOrThrow()
        assertTrue(JSONObject(server.requests.single().body).isNull("prompts"))
    }

    @Test
    fun `live 400 donne une ApiError Http typée`() {
        liveCode = 400
        val failure = runBlocking { HttpLiveClient(server.baseUrl).live("QQ==", null, 640, 12) }.exceptionOrNull()
        assertTrue(failure is ApiError.Http)
        assertEquals(400, (failure as ApiError.Http).code)
    }

    @Test
    fun `live reponse malformee donne BadResponse`() {
        liveBody = "ceci n'est pas du json"
        val failure = runBlocking { HttpLiveClient(server.baseUrl).live("QQ==", null, 640, 12) }.exceptionOrNull()
        assertTrue("attendu BadResponse, obtenu $failure", failure is ApiError.BadResponse)
    }

    @Test
    fun `live delai depasse donne ApiError Timeout`() {
        val slow = TestHttpServer { TestHttpServer.Response(200, validLive, delayMs = 2_000) }
        val impatient = defaultHttpClient(LIVE_TIMEOUT_SECONDS).newBuilder()
            .callTimeout(300, TimeUnit.MILLISECONDS)
            .readTimeout(300, TimeUnit.MILLISECONDS)
            .build()
        try {
            val failure = runBlocking { HttpLiveClient(slow.baseUrl, impatient).live("QQ==", null, 640, 12) }
                .exceptionOrNull()
            assertNotNull(failure)
            assertTrue("attendu Timeout, obtenu $failure", failure is ApiError.Timeout)
        } finally {
            slow.close()
        }
    }

    @Test
    fun `health parse ok model device et gpu`() = runBlocking {
        val health = HttpLiveClient(server.baseUrl).health().getOrThrow()
        assertEquals(LiveHealth(true, "sam3.1", "cuda", "A100"), health)
    }

    @Test
    fun `health en code non 2xx donne une erreur typée`() {
        val down = TestHttpServer { TestHttpServer.Response(503, "{}") }
        try {
            val failure = runBlocking { HttpLiveClient(down.baseUrl).health() }.exceptionOrNull()
            assertTrue(failure is ApiError.Http && failure.code == 503)
        } finally {
            down.close()
        }
    }

    @Test
    fun `le client live par défaut est borné à 10 secondes`() {
        assertEquals(10L, LIVE_TIMEOUT_SECONDS)
        val expected = LiveFrame(0, 0, 0, emptyList())
        assertEquals(0L, expected.elapsedMs)
    }
}
