package dev.sam3vision.core.net

import dev.sam3vision.core.TestHttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TEST_KEY = "cle-eleven-test"
private const val TEST_VOICE = "21m00Tcm4TlvDq8ikWAM"
private val AUDIO = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00, 0x42)

/** Réponse 200 contenant de l'audio factice. */
private fun audioServer(bytes: ByteArray = AUDIO) = TestHttpServer {
    TestHttpServer.Response(200, "", bodyBytes = bytes, contentType = "audio/mpeg")
}

/** Réponse d'erreur JSON, comme ElevenLabs les renvoie. */
private fun errorServer(code: Int) = TestHttpServer {
    TestHttpServer.Response(
        code,
        """{"detail":{"status":"paid_plan_required","message":"Free users cannot use library voices"}}""",
    )
}

class ElevenLabsTtsClientTest {

    @Test
    fun `audio 200 renvoye tel quel et requete conforme`() = runBlocking {
        audioServer().use { server ->
            val client = ElevenLabsTtsClient(TEST_KEY, TEST_VOICE, baseUrl = server.baseUrl)
            val bytes = client.synthesize("Bonjour SAM Vision.").getOrThrow()

            assertArrayEquals(AUDIO, bytes)
            val request = server.requests.single()
            assertEquals("POST", request.method)
            assertTrue(request.path, request.path.startsWith("/text-to-speech/$TEST_VOICE"))
            assertTrue(request.path, request.path.contains("output_format=mp3_44100_128"))
            assertEquals(TEST_KEY, request.headers["xi-api-key"])
            val body = JSONObject(request.body)
            assertEquals("Bonjour SAM Vision.", body.getString("text"))
            assertEquals("eleven_v4_turbo", body.getString("model_id"))
            assertTrue(body.has("voice_settings"))
        }
    }

    @Test
    fun `402 sans credits remonte PaymentRequired avec un message clair`() = runBlocking {
        errorServer(402).use { server ->
            val client = ElevenLabsTtsClient(TEST_KEY, TEST_VOICE, baseUrl = server.baseUrl)
            val error = client.synthesize("Bonjour.").exceptionOrNull()

            assertTrue(error.toString(), error is ApiError.PaymentRequired)
            assertTrue(error!!.message!!.contains("402"))
            assertTrue(error.message!!.contains("crédits"))
        }
    }

    @Test
    fun `401 remonte InvalidKey et 429 RateLimited`() = runBlocking {
        errorServer(401).use { server ->
            val error = ElevenLabsTtsClient(TEST_KEY, TEST_VOICE, baseUrl = server.baseUrl)
                .synthesize("Bonjour.").exceptionOrNull()
            assertTrue(error.toString(), error is ApiError.InvalidKey)
        }
        errorServer(429).use { server ->
            val error = ElevenLabsTtsClient(TEST_KEY, TEST_VOICE, baseUrl = server.baseUrl)
                .synthesize("Bonjour.").exceptionOrNull()
            assertTrue(error.toString(), error is ApiError.RateLimited)
        }
    }

    @Test
    fun `texte vide et cle absente echouent sans appel reseau`() = runBlocking {
        audioServer().use { server ->
            val client = ElevenLabsTtsClient(TEST_KEY, TEST_VOICE, baseUrl = server.baseUrl)
            val vide = client.synthesize("   ").exceptionOrNull()
            val sansCle = ElevenLabsTtsClient("", TEST_VOICE, baseUrl = server.baseUrl)
                .synthesize("Bonjour.").exceptionOrNull()

            assertTrue(vide.toString(), vide is ApiError.BadResponse)
            assertTrue(sansCle.toString(), sansCle is ApiError.InvalidKey)
            assertTrue(sansCle!!.message!!.contains("Réglages"))
            assertEquals(0, server.requests.size)
        }
    }

    @Test
    fun `voix absente refusee localement`() = runBlocking {
        audioServer().use { server ->
            val error = ElevenLabsTtsClient(TEST_KEY, "", baseUrl = server.baseUrl)
                .synthesize("Bonjour.").exceptionOrNull()

            assertTrue(error.toString(), error is ApiError.BadResponse)
            assertEquals(0, server.requests.size)
        }
    }

    @Test
    fun `reponse 200 sans audio remonte une erreur lisible`() = runBlocking {
        TestHttpServer { TestHttpServer.Response(200, """{"detail":"rate limited"}""") }.use { server ->
            val error = ElevenLabsTtsClient(TEST_KEY, TEST_VOICE, baseUrl = server.baseUrl)
                .synthesize("Bonjour.").exceptionOrNull()

            assertTrue(error.toString(), error is ApiError.BadResponse)
            assertTrue(error!!.message!!.contains("audio vide"))
        }
    }

    @Test
    fun `le voice id est echappe dans l URL`() = runBlocking {
        audioServer().use { server ->
            ElevenLabsTtsClient(TEST_KEY, "voix bizarro/../x", baseUrl = server.baseUrl)
                .synthesize("Bonjour.")

            val path = server.requests.single().path
            assertTrue(path, path.contains("voix%20bizarro"))
            assertTrue(path, !path.contains("../"))
        }
    }
}