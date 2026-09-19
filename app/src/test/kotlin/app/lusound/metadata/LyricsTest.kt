package app.lusound.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * LRC grammar on the JVM. The identity decoder stands in for Android's HTML entity decoding
 * (see [decodeLyricHtml]); every other part of the parser is plain text handling.
 */
class LyricsTest {
    private val plain: (String) -> String = { it }

    @Test
    fun repeatedTimestampsOnOneLineProduceOneLyricEach() {
        val lines = parseLyrics("[00:01.00][00:05.50]同一句", plain)
        assertEquals(listOf(1_000L, 5_500L), lines.map { it.timeMs })
        assertEquals(listOf("同一句", "同一句"), lines.map { it.text })
    }

    @Test
    fun fractionalSecondsKeepLeftAlignedMilliseconds() {
        // "5" is 500 ms, not 5 ms: the fraction is left-aligned, so it must be right-padded.
        assertEquals(1_500L, parseLyrics("[00:01.5]a", plain).single().timeMs)
        assertEquals(1_050L, parseLyrics("[00:01.05]a", plain).single().timeMs)
        assertEquals(1_005L, parseLyrics("[00:01.005]a", plain).single().timeMs)
    }

    @Test
    fun aMissingFractionIsZeroMilliseconds() {
        assertEquals(1_000L, parseLyrics("[00:01]a", plain).single().timeMs)
    }

    @Test
    fun bothColonAndDotAreAcceptedAsFractionSeparators() {
        assertEquals(1_500L, parseLyrics("[00:01:500]a", plain).single().timeMs)
        assertEquals(1_500L, parseLyrics("[00:01.500]a", plain).single().timeMs)
    }

    @Test
    fun threeDigitMinutesAreAccepted() {
        assertEquals(6_000_000L, parseLyrics("[100:00.00]a", plain).single().timeMs)
    }

    @Test
    fun offsetTagShiftsEveryLineAndNeverGoesNegative() {
        assertEquals(2_500L, parseLyrics("[offset:1500]\n[00:01.00]a", plain).single().timeMs)
        assertEquals(0L, parseLyrics("[offset:-2000]\n[00:01.00]a", plain).single().timeMs)
        assertEquals(2_000L, parseLyrics("[OFFSET:1000]\n[00:01.00]a", plain).single().timeMs)
    }

    @Test
    fun outputIsSortedByTime() {
        val lines = parseLyrics("[00:05.00]b\n[00:01.00]a", plain)
        assertEquals(listOf("a", "b"), lines.map { it.text })
    }

    @Test
    fun equalTimestampsKeepFileOrder() {
        val lines = parseLyrics("[00:01.00]a\n[00:01.00]b", plain)
        assertEquals(listOf("a", "b"), lines.map { it.text })
    }

    @Test
    fun linesWithoutTimestampsAreNotLyrics() {
        assertEquals(1, parseLyrics("标题\n[00:02.00]b", plain).size)
        assertEquals(0, parseLyrics("[ar:歌手]\n[ti:标题]", plain).size)
    }

    @Test
    fun realisticFileAppliesTagsAndOffset() {
        val text = """
            [ar:歌手]
            [ti:歌名]
            [offset:500]
            [00:12.00]第一句
            [00:15.30]第二句
        """.trimIndent()
        val lines = parseLyrics(text, plain)
        assertEquals(listOf(12_500L, 15_800L), lines.map { it.timeMs })
        assertEquals(listOf("第一句", "第二句"), lines.map { it.text })
    }

    @Test
    fun usableLyricsRejectsTagsOnlyAndPlaceholderText() {
        assertNull(usableLyrics("[ar:歌手]\n[ti:标题]", plain))
        assertNull(usableLyrics("暂无歌词", plain))
        assertNull(usableLyrics("纯音乐，请欣赏", plain))
        assertNull(usableLyrics("No lyrics", plain))
        assertNull(usableLyrics("   ", plain))
        assertNull(usableLyrics(null, plain))
    }

    @Test
    fun usableLyricsKeepsTimedAndUntimedContent() {
        assertEquals("[00:01.00]你好", usableLyrics("  [00:01.00]你好  ", plain))
        assertEquals("第一行\n第二行", usableLyrics("第一行\n第二行", plain))
    }

    @Test
    fun currentLyricIndexFollowsTheLastTimestampAtOrBeforeThePosition() {
        val lines = listOf(LyricLine(1_000, "a"), LyricLine(2_000, "b"), LyricLine(3_000, "c"))
        assertEquals(-1, currentLyricIndex(lines, 0))
        assertEquals(-1, currentLyricIndex(lines, 999))
        assertEquals(0, currentLyricIndex(lines, 1_000))
        assertEquals(0, currentLyricIndex(lines, 1_999))
        assertEquals(1, currentLyricIndex(lines, 2_000))
        assertEquals(2, currentLyricIndex(lines, 3_500))
        assertEquals(-1, currentLyricIndex(emptyList(), 5_000))
    }
}
