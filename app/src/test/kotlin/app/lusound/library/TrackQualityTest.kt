package app.lusound.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quality shown for a track.
 *
 * The rule under test is the one the handover is emphatic about: an unreported figure must read as
 * unknown. Nothing here may turn a container name into a bitrate, a sample rate or a bit depth.
 */
class TrackQualityTest {
    @Test
    fun bitsPerSecondBecomeKilobitsRounded() {
        assertEquals(320, bitrateKbps(320_000))
        assertEquals(128, bitrateKbps(128_000))
        assertEquals(1411, bitrateKbps(1_411_200))
        // Rounds to nearest rather than truncating.
        assertEquals(1, bitrateKbps(999))
    }

    @Test
    fun anUnreportedBitrateStaysUnknown() {
        assertNull(bitrateKbps(null))
        assertNull("providers use 0 for not measured", bitrateKbps(0))
        assertNull(bitrateKbps(-1))
    }

    @Test
    fun sampleRateIsShortenedWithoutLosingPrecision() {
        assertEquals("44.1 kHz", sampleRateLabel(44_100))
        assertEquals("48 kHz", sampleRateLabel(48_000))
        assertEquals("96 kHz", sampleRateLabel(96_000))
        assertEquals("22.05 kHz", sampleRateLabel(22_050))
        assertEquals("8 kHz", sampleRateLabel(8_000))
    }

    @Test
    fun qualityDetailJoinsOnlyWhatIsKnown() {
        assertEquals("320 kbps · 44.1 kHz · 16 bit", qualityDetail(320, 44_100, 16))
        assertEquals("320 kbps", qualityDetail(320, null, null))
        assertEquals("44.1 kHz", qualityDetail(null, 44_100, null))
        assertEquals("16 bit", qualityDetail(null, null, 16))
        assertEquals("320 kbps · 16 bit", qualityDetail(320, null, 16))
    }

    @Test
    fun qualityDetailIsNullWhenNothingIsKnown() {
        assertNull(qualityDetail(null, null, null))
        assertNull("zero means not measured, not zero kbps", qualityDetail(0, 0, 0))
    }

    @Test
    fun aTrackWithNoReportedQualityReadsAsUnknown() {
        assertEquals(UNKNOWN_QUALITY, qualityLabel(track(container = "flac")))
        assertEquals(UNKNOWN_QUALITY, qualityLabel(track(container = "mp3")))
        assertEquals(UNKNOWN_QUALITY, qualityLabel(track(container = "ncm")))
    }

    @Test
    fun aContainerNameNeverProducesAQualityFigure() {
        // The failure this guards against is showing "FLAC · 16 bit / 44.1 kHz" for a file whose tags
        // were never read, and "MP3 · 320 kbps" for a 96 kbps file.
        listOf("flac", "mp3", "m4a", "wav", "ncm", "").forEach { container ->
            val label = qualityLabel(track(container = container))
            assertEquals(UNKNOWN_QUALITY, label)
            assertFalse(label, label.contains("kbps"))
            assertFalse(label, label.contains("bit"))
            assertFalse(label, label.contains("kHz"))
        }
    }

    @Test
    fun reportedQualityIsShown() {
        val label = qualityLabel(track(container = "flac", bitrateKbps = 1_411, sampleRateHz = 44_100, bitDepth = 16))
        assertEquals("1411 kbps · 44.1 kHz · 16 bit", label)
        assertTrue(label, label.contains("44.1 kHz"))
    }

    @Test
    fun aPartiallyKnownTrackShowsOnlyTheKnownParts() {
        assertEquals("320 kbps", qualityLabel(track(container = "mp3", bitrateKbps = 320)))
        assertEquals("24 bit", qualityLabel(track(container = "flac", bitDepth = 24)))
    }

    private fun track(
        container: String = "flac",
        bitrateKbps: Int? = null,
        sampleRateHz: Int? = null,
        bitDepth: Int? = null,
    ) = Track("content://media/1", "标题", "歌手", "专辑", "/Music", 1_000, null,
        container, TrackSource.MEDIASTORE, null, bitrateKbps, sampleRateHz, bitDepth)
}
