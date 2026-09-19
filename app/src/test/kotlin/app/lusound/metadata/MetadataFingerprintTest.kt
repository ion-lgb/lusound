package app.lusound.metadata

import app.lusound.library.Track
import app.lusound.library.TrackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The fingerprint decides whether a cached match still belongs to the track it was stored for. */
class MetadataFingerprintTest {
    private fun track(
        uri: String = "content://media/audio/1",
        title: String = "标题",
        artist: String = "歌手",
        album: String = "专辑",
        durationMs: Long = 200_000,
    ) = Track(uri, title, artist, album, "/Music", durationMs, null, "flac", TrackSource.MEDIASTORE, null)

    @Test
    fun fingerprintIsStableAndHexEncoded() {
        val value = metadataFingerprint(track())
        assertEquals(value, metadataFingerprint(track()))
        assertEquals(64, value.length)
        assertEquals(true, value.all { it in "0123456789abcdef" })
    }

    @Test
    fun everyIdentityFieldChangesTheFingerprint() {
        val base = metadataFingerprint(track())
        assertNotEquals(base, metadataFingerprint(track(uri = "content://media/audio/2")))
        assertNotEquals(base, metadataFingerprint(track(title = "另一首")))
        assertNotEquals(base, metadataFingerprint(track(artist = "另一位")))
        assertNotEquals(base, metadataFingerprint(track(album = "另一张")))
        assertNotEquals(base, metadataFingerprint(track(durationMs = 200_001)))
    }

    @Test
    fun fieldsCannotCollideByBleedingIntoEachOther() {
        // Without a separator, ("ab","c") and ("a","bc") would hash the same joined string.
        assertNotEquals(
            metadataFingerprint(track(title = "ab", artist = "c")),
            metadataFingerprint(track(title = "a", artist = "bc")),
        )
    }
}
