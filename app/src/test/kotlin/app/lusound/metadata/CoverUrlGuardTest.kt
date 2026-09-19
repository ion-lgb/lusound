package app.lusound.metadata

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The allowlist for cover-art download URLs.
 *
 * The URL comes from a third-party JSON response, so it is untrusted input: a compromised or
 * misconfigured service could otherwise point the app at any host and have it fetch from there with the
 * app's own network identity. These cases are the shapes that would matter if the check were loosened.
 */
class CoverUrlGuardTest {
    private fun allowed(url: String) = isSupportedCoverUrl(url.toHttpUrl())

    @Test
    fun theCoverArtArchiveAndItsStorageHostsAreAllowed() {
        assertTrue(allowed("https://coverartarchive.org/release/abc/front-500"))
        assertTrue(allowed("https://archive.org/download/abc/front.jpg"))
        assertTrue(allowed("https://ia801504.us.archive.org/1/items/abc/front.jpg"))
    }

    @Test
    fun anythingThatIsNotHttpsIsRejected() {
        assertFalse("cleartext would leak the request and allow tampering", allowed("http://coverartarchive.org/release/abc"))
    }

    @Test
    fun anUnrelatedHostIsRejected() {
        assertFalse(allowed("https://evil.example.com/front.jpg"))
        assertFalse(allowed("https://localhost/front.jpg"))
        assertFalse(allowed("https://127.0.0.1/front.jpg"))
    }

    @Test
    fun aHostThatOnlyLooksLikeTheAllowedOneIsRejected() {
        // The subdomain test keeps its leading dot, so these are not archive.org hosts.
        assertFalse(allowed("https://notarchive.org/front.jpg"))
        assertFalse(allowed("https://archive.org.evil.example.com/front.jpg"))
        assertFalse("a prefix is not a subdomain", allowed("https://coverartarchive.org.evil.example.com/front.jpg"))
    }

    @Test
    fun theHostComparisonIsCaseInsensitiveBecauseUrlsAreNormalised() {
        assertTrue(allowed("https://CoverArtArchive.ORG/release/abc"))
        assertTrue(allowed("https://IA801504.US.ARCHIVE.ORG/1/items/abc/front.jpg"))
    }

    @Test
    fun credentialsInTheUrlDoNotMakeAForeignHostAcceptable() {
        assertFalse(allowed("https://user:pass@evil.example.com/front.jpg"))
    }

    @Test
    fun theHostIsTheTrustBoundaryNotThePort() {
        assertTrue("a standard port is still the same host", allowed("https://coverartarchive.org:443/release/abc"))
        // An attacker cannot control DNS for an allowed host, so another port still reaches the service
        // being trusted; the check exists to keep foreign hosts out, not to police ports.
        assertTrue(allowed("https://coverartarchive.org:8443/release/abc"))
    }
}
