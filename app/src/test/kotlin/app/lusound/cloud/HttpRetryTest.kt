package app.lusound.cloud

import app.lusound.BuildConfig
import java.util.concurrent.TimeUnit
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Retry and identity policy of [baseHttpClient], which every protocol client is built on.
 *
 * The fake server counts the requests it actually received, which is the only way to distinguish a real
 * replay from a client that merely re-read one response; a queue of extra responses is left behind so an
 * unexpected attempt cannot silently consume it.
 */
class HttpRetryTest {
    private fun call(server: MockWebServer): Response =
        baseHttpClient().newCall(Request.Builder().url(server.url("/library/tracks")).build()).execute()

    private fun fixture(vararg responses: MockResponse): MockWebServer {
        val server = MockWebServer()
        responses.forEach(server::enqueue)
        server.start()
        return server
    }

    @Test
    fun transientStatusesAreRetriedAndTheCallerSeesTheSuccessfulResponse() {
        for (status in listOf(408, 429, 500, 502, 503, 504)) {
            val server = fixture(
                MockResponse().setResponseCode(status),
                MockResponse().setResponseCode(200).setBody("recovered"),
            )
            try {
                call(server).use { response ->
                    assertEquals("HTTP $status must not surface once the server recovers", 200, response.code)
                    assertEquals("recovered", response.body.string())
                }
                assertEquals("HTTP $status must be retried exactly once", 2, server.requestCount)
            } finally {
                server.shutdown()
            }
        }
    }

    @Test
    fun aTransientStatusIsRetriedTwiceAndSucceedsOnTheThirdAttempt() {
        val server = fixture(
            MockResponse().setResponseCode(503),
            MockResponse().setResponseCode(503),
            MockResponse().setResponseCode(200).setBody("third time"),
        )
        try {
            call(server).use { response ->
                assertEquals(200, response.code)
                assertEquals("third time", response.body.string())
            }
            assertEquals(3, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun clientErrorsAreReturnedImmediatelyWithoutARetry() {
        for (status in listOf(400, 404)) {
            val server = fixture(
                MockResponse().setResponseCode(status).setBody("rejected"),
                MockResponse().setResponseCode(200).setBody("must never be read"),
            )
            try {
                call(server).use { response ->
                    assertEquals(status, response.code)
                    assertEquals("rejected", response.body.string())
                }
                assertEquals("HTTP $status must not be replayed", 1, server.requestCount)
            } finally {
                server.shutdown()
            }
        }
    }

    @Test
    fun aPermanentlyFailingStatusIsAttemptedAtMostThreeTimes() {
        val server = fixture(
            MockResponse().setResponseCode(503).setBody("down"),
            MockResponse().setResponseCode(503).setBody("down"),
            MockResponse().setResponseCode(503).setBody("down"),
            MockResponse().setResponseCode(200).setBody("must never be reached"),
        )
        try {
            call(server).use { response ->
                assertEquals(503, response.code)
                assertEquals("down", response.body.string())
            }
            assertEquals("a permanent failure must stop after three attempts", 3, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun everyAttemptCarriesTheApplicationUserAgent() {
        val server = fixture(
            MockResponse().setResponseCode(503),
            MockResponse().setResponseCode(200).setBody("ok"),
        )
        try {
            call(server).close()
            val first = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            val retry = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            for (request in listOf(first, retry)) {
                val agent = request.getHeader("User-Agent").orEmpty()
                assertTrue(agent, agent.startsWith("LuSound/${BuildConfig.VERSION_NAME} "))
            }
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
