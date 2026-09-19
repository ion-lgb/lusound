package app.lusound.metadata

import app.lusound.library.Track
import app.lusound.library.TrackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Lyrics candidate selection. A wrong match is worse than no match, so every rejection rule here
 * matters: title, artist, and a duration window of three seconds.
 */
class MetadataMatchTest {
    private fun track(title: String = "歌名", artist: String = "歌手", album: String = "专辑", durationMs: Long = 200_000) =
        Track("content://media/audio/1", title, artist, album, "/Music", durationMs, null, "flac", TrackSource.MEDIASTORE, null)

    private fun candidate(
        id: Long = 1,
        title: String = "歌名",
        artist: String = "歌手",
        album: String = "专辑",
        durationSeconds: Double = 200.0,
        synced: String? = "[00:01.00]x",
        plain: String? = "x",
    ) = LrcRecord(id, title, artist, album, durationSeconds, false, plain, synced)

    @Test
    fun matchingCandidateInsideTheDurationWindowIsAccepted() {
        val match = matchLyrics(track(), listOf(candidate(durationSeconds = 202.0)))
        assertNotNull(match)
        assertEquals(1L, match?.id)
    }

    @Test
    fun durationOutsideThreeSecondsIsRejected() {
        assertNull(matchLyrics(track(), listOf(candidate(durationSeconds = 205.0))))
        // The boundary itself is inclusive.
        assertNotNull(matchLyrics(track(), listOf(candidate(durationSeconds = 203.0))))
    }

    @Test
    fun titleAndArtistMustMatch() {
        assertNull(matchLyrics(track(), listOf(candidate(title = "别的歌"))))
        assertNull(matchLyrics(track(), listOf(candidate(artist = "别的歌手"))))
        assertNull(matchLyrics(track(), listOf(candidate(durationSeconds = 0.0))))
    }

    @Test
    fun albumMatchOutranksACloserDuration() {
        val match = matchLyrics(track(), listOf(
            candidate(id = 1, album = "精选集", durationSeconds = 200.1, synced = "[00:01.00]a"),
            candidate(id = 2, album = "专辑", durationSeconds = 203.0, synced = "[00:01.00]b"),
        ))
        assertEquals(2L, match?.id)
    }

    @Test
    fun syncedLyricsOutrankPlainLyrics() {
        val match = matchLyrics(track(), listOf(
            candidate(id = 1, durationSeconds = 200.0, synced = null, plain = "纯文本"),
            candidate(id = 2, durationSeconds = 201.0, synced = "[00:01.00]b", plain = null),
        ))
        assertEquals(2L, match?.id)
    }

    @Test
    fun closestDurationBreaksRemainingTies() {
        val match = matchLyrics(track(), listOf(
            candidate(id = 1, durationSeconds = 201.5),
            candidate(id = 2, durationSeconds = 200.2),
        ))
        assertEquals(2L, match?.id)
    }

    @Test
    fun featuredArtistsAndSeparatorsStillMatch() {
        assertNotNull(matchLyrics(track(artist = "歌手"), listOf(candidate(artist = "歌手 feat. 另一位"))))
        assertNotNull(matchLyrics(track(artist = "A/B"), listOf(candidate(artist = "A"))))
        assertNotNull(matchLyrics(track(artist = "A & B"), listOf(candidate(artist = "A"))))
    }

    @Test
    fun canonicalTextNormalisesPunctuationWidthsAndCase() {
        assertEquals("helloworld", canonicalText("Hello, World!"))
        assertEquals("abc123", canonicalText("ＡＢＣ１２３"))
        assertEquals(canonicalText("周杰伦"), canonicalText("周杰倫"))
    }
}
