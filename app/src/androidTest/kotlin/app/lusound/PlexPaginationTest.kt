package app.lusound

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lusound.cloud.*
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlexPaginationTest {
    @Test fun usesHeaderPaginationAndReadsEveryPage() = runBlocking {
        val fixture = MockWebServer()
        fixture.enqueue(json("""{"size":1,"Directory":[{"key":"10","type":"artist"}]}"""))
        fixture.enqueue(json("""{"size":1,"Metadata":[{"ratingKey":"1","type":"track","title":"First"}]}""")
            .setHeader("X-Plex-Container-Total-Size", 2).setHeader("X-Plex-Container-Start", 0))
        fixture.enqueue(json("""{"size":1,"Metadata":[{"ratingKey":"2","type":"track","title":"Second"}]}""")
            .setHeader("X-Plex-Container-Total-Size", 2).setHeader("X-Plex-Container-Start", 1))
        fixture.enqueue(json("""{"size":0,"Metadata":[]}""").setHeader("X-Plex-Container-Total-Size", 0))
        fixture.start()
        try {
            val result = client(fixture).readLibrary()
            assertEquals(listOf("1", "2"), result.songs.map { it.id })
            val calls = List(4) { requireNotNull(fixture.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)) }
            assertEquals("1", calls[2].getHeader("X-Plex-Container-Start"))
        } finally { fixture.shutdown() }
    }

    @Test fun rejectsRepeatedPageAndIncompletePagination() = runBlocking {
        val invalidPages = listOf(
            listOf(json("""{"size":1,"totalSize":2,"offset":0,"Metadata":[{"ratingKey":"1","type":"track","title":"First"}]}"""),
                json("""{"size":1,"totalSize":2,"offset":1,"Metadata":[{"ratingKey":"1","type":"track","title":"Repeated"}]}""")),
            listOf(json("""{"size":1,"Metadata":[{"ratingKey":"1","type":"track","title":"Missing total"}]}""")),
            listOf(json("""{"size":0,"totalSize":0,"Metadata":[]}""").setHeader("X-Plex-Container-Total-Size", 2)),
        )
        for (pages in invalidPages) {
            val fixture = MockWebServer()
            fixture.enqueue(json("""{"size":1,"Directory":[{"key":"10","type":"artist"}]}"""))
            pages.forEach(fixture::enqueue)
            fixture.enqueue(MockResponse().setResponseCode(500))
            fixture.start()
            try {
                try { client(fixture).readLibrary(); fail("Incomplete or inconsistent snapshot must not sync") }
                catch (error: IOException) { assertTrue(error.message.orEmpty().contains("分页")) }
            } finally { fixture.shutdown() }
        }
    }
}

private fun json(body: String): MockResponse = MockResponse().setHeader("Content-Type", "application/json").setBody("""{"MediaContainer":$body}""")
private fun client(fixture: MockWebServer): PlexClient = PlexClient(Server("test-server", "Test", fixture.url("/").toString(), "", "", 0, null, "PLEX", null), "test-token")
