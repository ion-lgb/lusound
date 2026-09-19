package app.lusound.cloud

import java.io.IOException
import java.security.MessageDigest
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Server address normalisation, credential placement and URL building.
 *
 * These are the rules that keep a password or token from being sent somewhere it does not belong,
 * so they are asserted directly rather than only through a live server or a mocked one.
 */
class CloudAuthTest {
    private fun server(
        id: String = "srv1",
        kind: String = "SUBSONIC",
        baseUrl: String = "https://music.example.com/",
        username: String = "admin",
    ) = Server(id, "Test", baseUrl, username, "cipher", 0, null, kind, null)

    private fun request(url: String) = Request.Builder().url(url).build()

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    // ---- normalizeServerUrl ---------------------------------------------------

    @Test
    fun serverUrlGetsATrailingSlashAndKeepsSubpaths() {
        assertEquals("https://music.example.com/", normalizeServerUrl("https://music.example.com"))
        assertEquals("https://music.example.com/", normalizeServerUrl("  https://music.example.com/  "))
        assertEquals("https://example.com/navidrome/", normalizeServerUrl("https://example.com/navidrome"))
        assertEquals("http://192.168.1.20:8096/", normalizeServerUrl("http://192.168.1.20:8096"))
    }

    @Test
    fun serverUrlRejectsAnythingThatWouldSmuggleCredentialsOrState() {
        // Credentials in the address would be persisted in the clear and could reach logs.
        assertThrows(IllegalArgumentException::class.java) { normalizeServerUrl("https://user:pass@music.example.com/") }
        assertThrows(IllegalArgumentException::class.java) { normalizeServerUrl("https://music.example.com/?u=admin") }
        assertThrows(IllegalArgumentException::class.java) { normalizeServerUrl("https://music.example.com/#fragment") }
        assertThrows(IllegalArgumentException::class.java) { normalizeServerUrl("not a url") }
        assertThrows(IllegalArgumentException::class.java) { normalizeServerUrl("ftp://music.example.com/") }
    }

    // ---- Plex resource paths --------------------------------------------------

    @Test
    fun plexResourcePathStaysInsideTheLibraryAndCarriesOnlyTheServerMarker() {
        val url = plexResourceUrl(server(kind = "PLEX", baseUrl = "https://plex.example.com/"), "/library/parts/1/file.flac")
        assertEquals("https://plex.example.com/library/parts/1/file.flac?lusound_server=srv1", url)
        assertFalse(url.contains("token", ignoreCase = true))
    }

    @Test
    fun plexResourcePathRespectsASubPathDeployment() {
        val url = plexResourceUrl(server(kind = "PLEX", baseUrl = "https://example.com/plex/"), "/library/parts/1/file.flac")
        assertTrue(url, url.startsWith("https://example.com/plex/library/parts/1/file.flac"))
    }

    @Test
    fun plexResourcePathRejectsAnythingOutsideTheLibrary() {
        val plex = server(kind = "PLEX")
        listOf("/libraryx/1", "/other/library/1", "/library/1?x=1", "/library/1#f", "/library/1\\2").forEach { key ->
            assertThrows(key, IOException::class.java) { plexResourceUrl(plex, key) }
        }
    }

    // ---- Plex request authentication -----------------------------------------

    @Test
    fun plexTokenIsRequiredAndMustBeHeaderSafe() {
        val plex = server(kind = "PLEX")
        val target = request("https://music.example.com/library/parts/1/file.flac")
        assertThrows(IOException::class.java) { authenticatePlex(target, plex, "") }
        assertThrows(IOException::class.java) { authenticatePlex(target, plex, "abc def") }
        assertThrows(IOException::class.java) { authenticatePlex(target, plex, "abc\ndef") }
    }

    @Test
    fun plexCredentialsNeverTravelToAnotherHost() {
        val plex = server(kind = "PLEX")
        assertThrows(IOException::class.java) {
            authenticatePlex(request("https://evil.example.com/library/parts/1/file.flac"), plex, "validtoken")
        }
    }

    @Test
    fun plexRequestCarriesTokenHeadersAndDropsTheServerMarker() {
        val plex = server(kind = "PLEX")
        val authenticated = authenticatePlex(
            request("https://music.example.com/library/parts/1/file.flac?lusound_server=srv1"), plex, "validtoken")
        assertEquals("validtoken", authenticated.header("X-Plex-Token"))
        assertEquals("srv1", authenticated.header("X-Plex-Client-Identifier"))
        assertEquals(app.lusound.BuildConfig.VERSION_NAME, authenticated.header("X-Plex-Version"))
        assertFalse(authenticated.url.toString(), authenticated.url.toString().contains("lusound_server"))
    }

    // ---- Subsonic request authentication -------------------------------------

    @Test
    fun subsonicCredentialsNeverTravelOutsideTheConfiguredRestScope() {
        val subsonic = server()
        assertThrows(SubsonicException::class.java) {
            authenticate(request("https://evil.example.com/rest/ping.view"), subsonic, "hunter2")
        }
        assertThrows(SubsonicException::class.java) {
            authenticate(request("https://music.example.com/api/ping"), subsonic, "hunter2")
        }
        assertThrows(SubsonicException::class.java) {
            authenticate(request("http://music.example.com/rest/ping.view"), subsonic, "hunter2")
        }
    }

    @Test
    fun subsonicRequestUsesTokenAndSaltAndNeverThePlainPassword() {
        val subsonic = server()
        val authenticated = authenticate(request("https://music.example.com/rest/ping.view?lusound_server=srv1"), subsonic, "hunter2")
        val url = authenticated.url
        val salt = requireNotNull(url.queryParameter("s"))
        assertEquals(32, salt.length)
        assertEquals("admin", url.queryParameter("u"))
        assertEquals(md5("hunter2$salt"), url.queryParameter("t"))
        assertEquals("1.16.1", url.queryParameter("v"))
        assertEquals("LuSound", url.queryParameter("c"))
        assertEquals("json", url.queryParameter("f"))
        assertFalse(url.toString(), url.toString().contains("hunter2"))
        assertFalse(url.toString(), url.toString().contains("lusound_server"))
    }

    // ---- Stream and cover URL shapes -----------------------------------------

    @Test
    fun subsonicStreamUrlPointsAtTheRestStreamEndpoint() {
        val url = streamUrl(server(), "song 1")
        assertTrue(url, url.startsWith("https://music.example.com/rest/stream.view"))
        assertTrue(url, url.contains("lusound_server=srv1"))
    }

    @Test
    fun jellyfinStreamUrlRequestsTheStaticFile() {
        val url = streamUrl(server(kind = "JELLYFIN"), "abc")
        assertTrue(url, url.startsWith("https://music.example.com/Audio/abc/stream"))
        assertTrue(url, url.contains("static=true"))
    }

    @Test
    fun unsupportedProtocolIsRejectedRatherThanGuessed() {
        assertThrows(SubsonicException::class.java) { streamUrl(server(kind = "KOEL"), "1") }
        assertThrows(SubsonicException::class.java) { coverUrl(server(kind = "KOEL"), "1") }
    }

    @Test
    fun coverUrlFollowsTheProtocolOfItsServer() {
        assertTrue(coverUrl(server(kind = "SUBSONIC"), "art1").startsWith("https://music.example.com/rest/getCoverArt.view"))
        assertTrue(coverUrl(server(kind = "JELLYFIN"), "art1").startsWith("https://music.example.com/Items/art1/Images/Primary"))
    }
}
