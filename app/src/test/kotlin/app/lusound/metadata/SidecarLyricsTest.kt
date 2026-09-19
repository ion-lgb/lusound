package app.lusound.metadata

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Sidecar `.lrc` name matching and text decoding.
 *
 * Both are pure text/byte handling, so they are verified here without a device; the provider
 * queries that use them are covered by the instrumented test.
 */
class SidecarLyricsTest {
    @Test
    fun baseNameDropsTheLastExtensionOnly() {
        assertEquals("Song", lyricsBaseName("Song.mp3"))
        assertEquals("Song", lyricsBaseName("Song.MP3"))
        assertEquals("Song", lyricsBaseName("Song"))
        assertEquals("01. Song", lyricsBaseName("01. Song.flac"))
        assertEquals("Song", lyricsBaseName("  Song.mp3  "))
    }

    @Test
    fun lyricsFileNameMustShareTheAudioBaseName() {
        assertEquals("Song.lrc", pickSidecar("Song.mp3", listOf("Song.lrc")))
        assertEquals("Song.lrc", pickSidecar("Song.flac", listOf("Song.lrc")))
        assertEquals("Song.lrc", pickSidecar("Song", listOf("Song.lrc")))
        assertEquals("01. Song.lrc", pickSidecar("01. Song.mp3", listOf("01. Song.lrc")))
    }

    @Test
    fun theExtensionIsComparedWithoutCase() {
        assertEquals("Song.LRC", pickSidecar("Song.mp3", listOf("Song.LRC")))
        assertEquals("song.lrc", pickSidecar("SONG.MP3", listOf("song.lrc")))
    }

    @Test
    fun aLyricsFileOfAnotherSongIsNeverUsed() {
        assertNull(pickSidecar("Song.mp3", listOf("Other.lrc")))
        assertNull(pickSidecar("Song.mp3", listOf("Song.txt")))
        assertNull(pickSidecar("Song.mp3", listOf("Song.mp3.lrc.txt")))
        assertNull(pickSidecar("Song.mp3", emptyList()))
    }

    @Test
    fun namesWithoutABaseAreRejected() {
        assertNull(pickSidecar("", listOf(".lrc")))
        assertNull(pickSidecar(".mp3", listOf(".lrc")))
    }

    @Test
    fun thePlainSidecarNameWinsOverTheAppendedOne() {
        assertEquals("Song.lrc", pickSidecar("Song.mp3", listOf("Song.mp3.lrc", "Song.lrc")))
        assertEquals("Song.lrc", pickSidecar("Song.mp3", listOf("Song.lrc", "Song.mp3.lrc")))
        // Only the appended spelling exists: it is still better than no lyrics at all.
        assertEquals("Song.mp3.lrc", pickSidecar("Song.mp3", listOf("Song.mp3.lrc")))
    }

    @Test
    fun equallyRankedSiblingsResolveDeterministically() {
        assertEquals("Song.lrc", pickSidecar("Song.mp3", listOf("song.LRC", "Song.lrc")))
        assertEquals("Song.lrc", pickSidecar("Song.mp3", listOf("Song.lrc", "song.LRC")))
    }

    @Test
    fun utf8IsDecodedWithAndWithoutAByteOrderMark() {
        val lyrics = "[00:01.00]第一句\n[00:02.00]第二句"
        assertEquals(lyrics, decodeLyricsBytes(lyrics.toByteArray(StandardCharsets.UTF_8)))
        val marked = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + lyrics.toByteArray(StandardCharsets.UTF_8)
        assertEquals(lyrics, decodeLyricsBytes(marked))
    }

    @Test
    fun utf16IsDecodedFromItsByteOrderMark() {
        val lyrics = "[00:01.00]歌词"
        val little = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + lyrics.toByteArray(StandardCharsets.UTF_16LE)
        assertEquals(lyrics, decodeLyricsBytes(little))
        val big = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + lyrics.toByteArray(StandardCharsets.UTF_16BE)
        assertEquals(lyrics, decodeLyricsBytes(big))
    }

    @Test
    fun legacyChineseLyricsAreNotTurnedIntoReplacementCharacters() {
        val lyrics = "[00:01.00]歌曲"
        val gbk = lyrics.toByteArray(Charset.forName("GB18030"))
        // "歌" is 0xB8 0xE8 in GB18030, which is not valid UTF-8; the decoder must fall back.
        assertEquals(lyrics, decodeLyricsBytes(gbk))
    }

    @Test
    fun anEmptyFileDecodesToAnEmptyText() {
        assertEquals("", decodeLyricsBytes(ByteArray(0)))
    }
}
