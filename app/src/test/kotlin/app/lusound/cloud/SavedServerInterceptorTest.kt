package app.lusound.cloud

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The credential-resolution path of [SavedServerInterceptor], driven on the JVM.
 *
 * The interceptor only needs a `ServerCredentials` lookup, so these tests supply a fake store plus a
 * fake decrypted secret and run the *real* interceptor inside a real OkHttp chain. The terminal
 * interceptor records the request the interceptor actually handed downstream, and the fake store counts
 * how often a secret was revealed: together they answer the only questions that matter here — which
 * server a marker resolved to, where the credential ended up, and whether a refused request was sent
 * at all.
 */
class SavedServerInterceptorTest {
    private val password = "hunter2"
    private val plexToken = "plex-token-123"
    private val jellyfinToken = "jellyfin-token-456"

    /** The request the interceptor produced, or null when it refused before reaching the network. */
    private val outgoing = AtomicReference<Request?>(null)

    /** Secrets revealed since the last [run]. Reset per run so each case is counted on its own. */
    private val decryptedIds = AtomicInteger(0)
    private val lookedUpIds = mutableListOf<String>()

    /** The refusal [run] caught, or null when the request was let through. */
    private var refusal: IOException? = null

    private fun server(
        id: String,
        kind: String = "SUBSONIC",
        baseUrl: String = "https://music.example.com/",
        username: String = "admin",
    ) = Server(id, "Test", baseUrl, username, "CIPHER:$id", 0, null, kind, null)

    /**
     * One marker resolves to exactly one server, as in the real store, so each protocol fixture carries
     * its own id. The ids are literals rather than properties on purpose: reading them from properties
     * put the fixtures at the mercy of initialisation order, which silently gave all three the same id
     * when this test was first written.
     */
    private val subsonic = server("srv-sub")
    private val plex = server("srv-plex", kind = "PLEX", baseUrl = "https://plex.example.com/", username = "")
    private val jellyfin = server("srv-jellyfin", kind = "JELLYFIN", baseUrl = "https://jellyfin.example.com/")

    private val subsonicId = "srv-sub"
    private val plexId = "srv-plex"
    private val jellyfinId = "srv-jellyfin"

    /** A fake store: only the ids in [known] resolve, and every secret is returned in the clear. */
    private fun lookup(known: List<Server> = listOf(subsonic, plex, jellyfin)): ServerCredentials {
        val byId = known.associateBy { it.id }
        return ServerCredentials { id ->
            lookedUpIds.add(id)
            byId[id]?.let { server ->
                LocatedServer(server) {
                    decryptedIds.incrementAndGet()
                    when (server.kind) {
                        "PLEX" -> plexToken
                        "JELLYFIN" -> jellyfinToken
                        else -> password
                    }
                }
            }
        }
    }

    /** Raised by the terminal interceptor so a test can tell "sent" from "refused" by the outcome. */
    private class Sent(request: Request) : Error("sent ${request.url}")

    /**
     * Runs the real interceptor in a real chain. The terminal interceptor raises [Sent] instead of
     * returning a response, so the caller learns whether the request was let through without needing a
     * server anywhere.
     */
    private fun run(url: String, credentials: ServerCredentials = lookup()) {
        outgoing.set(null)
        decryptedIds.set(0)
        refusal = null
        val terminal = okhttp3.Interceptor { chain ->
            outgoing.set(chain.request())
            throw Sent(chain.request())
        }
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(SavedServerInterceptor(credentials))
            .addInterceptor(terminal)
            .build()
        try {
            client.newCall(Request.Builder().url(url).build()).execute().close()
        } catch (sent: Sent) {
            return
        } catch (caught: IOException) {
            // Plex refusals are plain IOExceptions; the other two protocols use SubsonicException.
            refusal = caught
            return
        }
        throw AssertionError("the interceptor neither sent nor refused $url")
    }

    /** Returns the recorded outgoing request, failing the test if nothing was sent. */
    private fun sent(): Request = requireNotNull(outgoing.get()) { "the interceptor never sent a request" }

    private fun assertRefused(requestUrl: String, message: String, credentials: ServerCredentials = lookup()) {
        run(requestUrl, credentials)
        val error = refusal
        assertNull("a refused request must not reach the network", outgoing.get())
        assertNotNull("the refusal must explain itself", error)
        assertTrue("was <${error?.message}>", error?.message.orEmpty().contains(message))
    }

    // ---- 1. no marker: untouched pass-through ---------------------------------

    @Test
    fun aRequestWithoutTheMarkerPassesThroughUntouched() {
        run("https://music.example.com/rest/ping.view?id=1")

        val request = sent()
        assertEquals("https://music.example.com/rest/ping.view?id=1", request.url.toString())
        assertTrue("no credential may be invented for an unmarked request", request.headers.names().isEmpty())
        assertTrue("no server may be looked up without a marker", lookedUpIds.isEmpty())
        assertEquals(0, decryptedIds.get())
    }

    @Test
    fun aRequestWithoutTheMarkerIsUntouchedEvenOnAForeignHost() {
        run("https://evil.example.com/anything?lusound_other=1")

        assertEquals("https://evil.example.com/anything?lusound_other=1", sent().url.toString())
        assertEquals(0, decryptedIds.get())
    }

    // ---- 2. unknown marker: refuse instead of sending unauthenticated ---------

    @Test
    fun anUnknownOrRemovedServerIdIsRefusedInsteadOfBeingSentUnauthenticated() {
        run("https://music.example.com/rest/stream.view?id=s1&lusound_server=gone")

        assertNull("a request named after a removed server must not go out", outgoing.get())
        assertEquals(listOf("gone"), lookedUpIds)
        assertEquals("a missing server has no secret to decrypt", 0, decryptedIds.get())
        assertTrue(
            "was <${refusal?.message}>",
            refusal?.message.orEmpty().contains("服务器已移除，无法读取该音频或封面"),
        )
    }

    // ---- 3. the right credential placement per protocol -----------------------

    @Test
    fun subsonicGetsTokenSaltQueryParametersAndNeverThePlaintextPassword() {
        run("https://music.example.com/rest/stream.view?id=s1&lusound_server=$subsonicId")

        val url = sent().url
        val salt = requireNotNull(url.queryParameter("s"))
        assertEquals(32, salt.length)
        assertEquals("admin", url.queryParameter("u"))
        assertEquals(md5(password + salt), url.queryParameter("t"))
        assertEquals("1.16.1", url.queryParameter("v"))
        assertEquals("LuSound", url.queryParameter("c"))
        assertEquals("json", url.queryParameter("f"))
        assertNull("the plaintext password must never travel as `p`", url.queryParameter("p"))
        assertFalse(url.toString(), url.toString().contains(password))
        assertTrue("the interceptor must send no headers of its own", sent().headers.names().isEmpty())
        assertEquals(1, decryptedIds.get())
    }

    @Test
    fun plexGetsTheTokenHeader() {
        run("https://plex.example.com/library/parts/1/file.flac?lusound_server=$plexId")

        val request = sent()
        assertEquals(plexToken, request.header("X-Plex-Token"))
        assertEquals(plexId, request.header("X-Plex-Client-Identifier"))
        assertEquals(app.lusound.BuildConfig.VERSION_NAME, request.header("X-Plex-Version"))
        assertFalse(request.url.toString(), request.url.toString().contains(plexToken))
        assertEquals(1, decryptedIds.get())
    }

    @Test
    fun jellyfinGetsTheEmbyTokenHeader() {
        run("https://jellyfin.example.com/Audio/abc/stream?static=true&lusound_server=$jellyfinId")

        val request = sent()
        assertEquals(jellyfinToken, request.header("X-Emby-Token"))
        assertFalse(request.url.toString(), request.url.toString().contains(jellyfinToken))
        assertEquals(1, decryptedIds.get())
    }

    // ---- 4. the marker never survives into the outgoing URL -------------------

    @Test
    fun theServerMarkerIsRemovedForEveryProtocol() {
        val cases = listOf(
            "SUBSONIC" to "https://music.example.com/rest/stream.view?id=s1&lusound_server=$subsonicId&c=LuSound",
            "PLEX" to "https://plex.example.com/library/parts/1/file.flac?lusound_server=$plexId",
            "JELLYFIN" to "https://jellyfin.example.com/Audio/abc/stream?static=true&lusound_server=$jellyfinId",
        )
        for ((kind, requestUrl) in cases) {
            run(requestUrl)
            val outgoingUrl = sent().url
            assertNull("$kind leaked the marker", outgoingUrl.queryParameter("lusound_server"))
            assertFalse("$kind leaked the marker", outgoingUrl.toString().contains("lusound_server"))
        }
    }

    @Test
    fun everyProtocolKeepsTheMarkersNeighboursIntact() {
        run("https://music.example.com/rest/stream.view?id=s1&lusound_server=$subsonicId&c=LuSound")

        assertEquals("s1", sent().url.queryParameter("id"))
        assertEquals("LuSound", sent().url.queryParameter("c"))
    }

    // ---- 5. out-of-scope URLs are refused per protocol ------------------------

    /**
     * Subsonic and Plex build the whole authenticated request before they hand it on, so their own scope
     * check runs inside [authenticate] / [authenticatePlex] with the secret already in hand. The
     * ordering assertion is that one reveal happens for those protocols and none for Jellyfin (see its
     * same-host case below), which is exactly what the original inline `vault.decrypt` also did. The
     * credential is never transmitted, which is the assertion that protects the user.
     */
    @Test
    fun subsonicRefusesEveryOutOfScopeRequest() {
        val cases = listOf(
            "another host" to "https://evil.example.com/rest/stream.view?lusound_server=$subsonicId",
            "another scheme" to "http://music.example.com/rest/stream.view?lusound_server=$subsonicId",
            "another port" to "https://music.example.com:8443/rest/stream.view?lusound_server=$subsonicId",
            "outside rest/" to "https://music.example.com/api/stream?lusound_server=$subsonicId",
            "a path that climbs back out of rest/" to "https://music.example.com/rest/../admin?lusound_server=$subsonicId",
        )
        for ((label, requestUrl) in cases) {
            assertRefused(requestUrl, "拒绝向配置范围以外的地址发送服务器凭据")
            assertEquals("$label: one reveal, matching the original inline decrypt", 1, decryptedIds.get())
        }
    }

    @Test
    fun plexRefusesEveryOutOfScopeRequest() {
        val cases = listOf(
            "another host" to "https://evil.example.com/library/parts/1/file.flac?lusound_server=$plexId",
            "another scheme" to "http://plex.example.com/library/parts/1/file.flac?lusound_server=$plexId",
            "another port" to "https://plex.example.com:8443/library/parts/1/file.flac?lusound_server=$plexId",
        )
        for ((label, requestUrl) in cases) {
            assertRefused(requestUrl, "拒绝向配置范围以外的地址发送 Plex Token")
            assertEquals("$label: one reveal, matching the original inline decrypt", 1, decryptedIds.get())
        }
    }

    @Test
    fun jellyfinRefusesEveryOutOfScopeRequest() {
        val cases = listOf(
            "another host" to "https://evil.example.com/Audio/abc/stream?lusound_server=$jellyfinId",
            "another scheme" to "http://jellyfin.example.com/Audio/abc/stream?lusound_server=$jellyfinId",
            "another port" to "https://jellyfin.example.com:8443/Audio/abc/stream?lusound_server=$jellyfinId",
        )
        for ((label, requestUrl) in cases) {
            assertRefused(requestUrl, "拒绝向配置范围以外的地址发送 Jellyfin 凭据")
            assertEquals("$label: the Jellyfin branch checks scope before revealing the secret", 0, decryptedIds.get())
        }
    }

    /**
     * Plex and Jellyfin check the *base* path rather than a protocol-specific segment, so a deployment
     * under a sub-path is the case that can actually refuse a same-host URL. Both protocols are built
     * that way in production: [plexResourceUrl] keeps every resource inside `/library/`, and Jellyfin
     * media URLs are relative to the configured base ([streamUrl], [coverUrl]).
     */
    @Test
    fun plexRefusesASameHostUrlOutsideTheConfiguredSubPath() {
        val subPath = server(plexId, kind = "PLEX", baseUrl = "https://example.com/plex/", username = "")
        val credentials = lookup(listOf(subPath))

        assertRefused(
            "https://example.com/other/library/parts/1/file.flac?lusound_server=$plexId",
            "拒绝向配置范围以外的地址发送 Plex Token",
            credentials,
        )
        assertRefused(
            "https://example.com/plex/../admin?lusound_server=$plexId",
            "拒绝向配置范围以外的地址发送 Plex Token",
            credentials,
        )

        run("https://example.com/plex/library/parts/1/file.flac?lusound_server=$plexId", credentials)
        assertTrue(sent().url.toString(), sent().url.encodedPath.startsWith("/plex/library/"))
        assertEquals(plexToken, sent().header("X-Plex-Token"))
    }

    @Test
    fun jellyfinRefusesASameHostUrlOutsideTheConfiguredSubPath() {
        val subPath = server(jellyfinId, kind = "JELLYFIN", baseUrl = "https://example.com/jellyfin/")
        val credentials = lookup(listOf(subPath))

        assertRefused(
            "https://example.com/other/Audio/abc/stream?lusound_server=$jellyfinId",
            "拒绝向配置范围以外的地址发送 Jellyfin 凭据",
            credentials,
        )
        assertRefused(
            "https://example.com/jellyfin/../admin?lusound_server=$jellyfinId",
            "拒绝向配置范围以外的地址发送 Jellyfin 凭据",
            credentials,
        )

        run("https://example.com/jellyfin/Audio/abc/stream?lusound_server=$jellyfinId", credentials)
        assertEquals(jellyfinToken, sent().header("X-Emby-Token"))
    }

    @Test
    fun subsonicRefusesASameHostUrlOutsideTheConfiguredSubPath() {
        val subPath = server(subsonicId, baseUrl = "https://example.com/navidrome/")
        val credentials = lookup(listOf(subPath))

        assertRefused(
            "https://example.com/other/rest/ping.view?lusound_server=$subsonicId",
            "拒绝向配置范围以外的地址发送服务器凭据",
            credentials,
        )

        run("https://example.com/navidrome/rest/ping.view?lusound_server=$subsonicId", credentials)
        assertEquals("admin", sent().url.queryParameter("u"))
    }

    /**
     * Finding, not a target: with the documented single-host deployment (base = `https://host/`) the
     * Plex and Jellyfin checks accept *any* path on that host, because they only test
     * `encodedPath.startsWith(base.encodedPath)` (CloudHttp.kt:104 and :114) while the Subsonic check
     * additionally demands `rest/` (CloudHttp.kt:60). The credential still cannot leave the configured
     * scheme, host and port, and every URL the app builds for these protocols is well inside the scope,
     * so this pins the current behaviour rather than asking for a change.
     */
    @Test
    fun plexAndJellyfinCurrentlyAcceptAnyPathOnTheConfiguredRootHost() {
        for (markerUrl in listOf(
            "https://plex.example.com/admin?lusound_server=$plexId",
            "https://jellyfin.example.com/admin?lusound_server=$jellyfinId",
        )) {
            run(markerUrl)
            assertEquals(
                "$markerUrl: today's behaviour is that a root base scope accepts any path",
                "/admin",
                sent().url.encodedPath,
            )
        }
    }

    // ---- 6. an unsupported protocol is refused -------------------------------

    @Test
    fun anUnsupportedKindIsRefusedRatherThanSentUnauthenticated() {
        val koel = server(subsonicId, kind = "KOEL")
        run("https://music.example.com/rest/ping.view?lusound_server=$subsonicId", lookup(listOf(koel)))

        assertNull("an unknown protocol must not produce a request", outgoing.get())
        assertTrue("was <${refusal?.message}>", refusal?.message.orEmpty().contains("不支持的服务器协议"))
    }

    /**
     * Finding, not a target: the interceptor decides the protocol before it asks for the secret
     * (CloudHttp.kt:98-:101), so an unsupported `kind` is refused without touching the Keystore. The
     * assertion pins that ordering, which is what keeps a tampered row from surfacing a vault error.
     */
    @Test
    fun anUnsupportedKindIsRefusedWithoutRevealingTheSecret() {
        val koel = server(subsonicId, kind = "koel")
        run("https://music.example.com/rest/ping.view?lusound_server=$subsonicId", lookup(listOf(koel)))

        assertNull("an unknown protocol must not produce a request", outgoing.get())
        assertTrue("was <${refusal?.message}>", refusal?.message.orEmpty().contains("不支持的服务器协议"))
        assertEquals(0, decryptedIds.get())
    }

    /** A secret that cannot be decrypted must fail the request, not send it without a credential. */
    @Test
    fun aFailedDecryptionRefusesTheRequest() {
        val broken = ServerCredentials { id ->
            lookedUpIds.add(id)
            if (subsonic.id == id) LocatedServer(subsonic) { throw IOException("服务器凭据损坏，请重新输入密码") } else null
        }
        run("https://music.example.com/rest/ping.view?lusound_server=$subsonicId", broken)

        assertNull("a request whose secret cannot be read must not go out", outgoing.get())
        assertTrue("was <${refusal?.message}>", refusal?.message.orEmpty().contains("凭据损坏"))
    }

    // ---- the seam's own contract --------------------------------------------

    @Test
    fun theSecretIsDecryptedLazilyAndOnlyOncePerRequest() {
        run("https://music.example.com/rest/ping.view?lusound_server=$subsonicId")

        assertEquals(1, decryptedIds.get())
        assertEquals(listOf(subsonicId), lookedUpIds)
    }

    private fun md5(value: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
