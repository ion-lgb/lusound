package app.lusound.metadata

import androidx.media3.common.DataReader
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * Embedded lyrics on the JVM.
 *
 * The ID3 and MP4 containers are parsed by Media3's own extractor classes, and those classes are
 * plain Java: the tags and boxes below are built byte by byte here, then handed to the production
 * readers. Only the payload formats the app decodes itself are asserted directly.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class EmbeddedLyricsTest {
    private val plain: (String) -> String = { it }

    @Test
    fun containerIsSniffedFromTheFirstBytes() {
        assertEquals(LyricsContainer.FLAC, sniffLyricsContainer(ascii("fLaC\u0000\u0000\u0000\u0022")))
        assertEquals(LyricsContainer.ID3, sniffLyricsContainer(ascii("ID3\u0003\u0000\u0000")))
        assertEquals(LyricsContainer.MP4, sniffLyricsContainer(ascii("\u0000\u0000\u0000\u0020ftypM4A ")))
        assertEquals(LyricsContainer.MP4, sniffLyricsContainer(ascii("\u0000\u0000\u0000\u0008moov")))
        assertEquals(LyricsContainer.MP4, sniffLyricsContainer(ascii("\u0000\u0000\u0000\u0008mdat")))
        assertNull(sniffLyricsContainer(ascii("RIFF\u0000\u0000\u0000\u0000WAVE")))
        assertNull(sniffLyricsContainer(ascii("\u0000\u0000\u0000\u0008junk")))
        assertNull(sniffLyricsContainer(ascii("fLa")))
        assertNull(sniffLyricsContainer(ByteArray(0)))
    }

    @Test
    fun usltPayloadIsDecodedForEveryId3TextEncoding() {
        assertEquals("plain lyrics", decodeUsltPayload(uslt(0, "", "plain lyrics", StandardCharsets.ISO_8859_1)))
        assertEquals("中文歌词", decodeUsltPayload(uslt(3, "", "中文歌词", StandardCharsets.UTF_8)))
        assertEquals("中文歌词", decodeUsltPayload(uslt(1, "", "中文歌词", StandardCharsets.UTF_16LE, bom = true)))
        assertEquals("歌词", decodeUsltPayload(uslt(2, "", "歌词", StandardCharsets.UTF_16BE)))
        assertEquals("中文歌词", decodeUsltPayload(uslt(1, "注释", "中文歌词", StandardCharsets.UTF_16LE, bom = true)))
        assertEquals("描述被跳过", decodeUsltPayload(uslt(3, "内容描述", "描述被跳过", StandardCharsets.UTF_8)))
    }

    @Test
    fun malformedUsltPayloadsAreNotLyrics() {
        assertNull(decodeUsltPayload(ByteArray(0)))
        assertNull(decodeUsltPayload(byteArrayOf(3, 'e'.code.toByte(), 'n'.code.toByte())))
        assertNull(decodeUsltPayload(byteArrayOf(3, 'e'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte())))
        // A payload that is nothing but encoding, language and an empty descriptor has no text.
        assertNull(decodeUsltPayload(byteArrayOf(3, 'e'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte(), 0)))
    }

    @Test
    fun trailingNullsAreNotPartOfTheLyrics() {
        val payload = uslt(3, "", "歌词\u0000\u0000", StandardCharsets.UTF_8)
        assertEquals("歌词", decodeUsltPayload(payload))
    }

    @Test
    fun id3v23UsltIsReadThroughMedia3sTagParser() {
        val lyrics = "[00:01.00]第一句\n[00:02.50]第二句"
        val tag = id3Tag(version = 3, frames = listOf(frame("USLT", uslt(3, "", lyrics, StandardCharsets.UTF_8), 3)))
        assertEquals(lyrics, readId3Lyrics(extractorInput(tag), "test", plain))
    }

    @Test
    fun id3v24UsltUsesSynchsafeFrameSizes() {
        // Longer than 127 bytes, so the synchsafe frame size differs from a plain integer.
        val lyrics = "[00:01.00]" + "长歌词".repeat(60)
        val tag = id3Tag(version = 4, frames = listOf(frame("USLT", uslt(3, "", lyrics, StandardCharsets.UTF_8), 4)))
        val read = readId3Lyrics(extractorInput(tag), "test", plain)
        assertEquals(lyrics, read)
    }

    @Test
    fun id3v22UltIsReadAsWell() {
        val lyrics = "[00:01.00]v2.2 lyrics"
        val tag = id3Tag(version = 2, frames = listOf(frame("ULT", uslt(0, "", lyrics, StandardCharsets.ISO_8859_1), 2)))
        assertEquals(lyrics, readId3Lyrics(extractorInput(tag), "test", plain))
    }

    @Test
    fun usltWinsOverAFreeformLyricsTag() {
        val tag = id3Tag(version = 3, frames = listOf(
            frame("TXXX", txxx("LYRICS", "freeform text"), 3),
            frame("USLT", uslt(3, "", "real lyrics", StandardCharsets.UTF_8), 3),
        ))
        assertEquals("real lyrics", readId3Lyrics(extractorInput(tag), "test", plain))
    }

    @Test
    fun freeformLyricsTagIsUsedWhenNoUsltExists() {
        val tag = id3Tag(version = 3, frames = listOf(frame("TXXX", txxx("LYRICS", "freeform text"), 3)))
        assertEquals("freeform text", readId3Lyrics(extractorInput(tag), "test", plain))
        val unrelated = id3Tag(version = 3, frames = listOf(frame("TXXX", txxx("MOOD", "happy"), 3)))
        assertNull(readId3Lyrics(extractorInput(unrelated), "test", plain))
    }

    @Test
    fun placeholderLyricsAreRejected() {
        val tag = id3Tag(version = 3, frames = listOf(frame("USLT", uslt(3, "", "暂无歌词", StandardCharsets.UTF_8), 3)))
        assertNull(readId3Lyrics(extractorInput(tag), "test", plain))
    }

    @Test
    fun aStreamWithoutAnId3TagHasNoLyrics() {
        assertNull(readId3Lyrics(extractorInput(ascii("RIFF\u0000\u0000\u0000\u0000WAVEfmt ")), "test", plain))
        assertNull(readId3Lyrics(extractorInput(ascii("ID3")), "test", plain))
    }

    @Test
    fun anOversizedId3TagIsReportedInsteadOfAllocated() {
        val header = ByteArrayOutputStream().apply {
            write(ascii("ID3"))
            write(3)
            write(0)
            write(0)
            write(synchsafe(17 * 1024 * 1024))
        }.toByteArray()
        try {
            readId3Lyrics(extractorInput(header), "test", plain)
            fail("A tag larger than the ceiling must be reported, not allocated")
        } catch (expected: IOException) {
            assertEquals(true, expected.message.orEmpty().contains("16 MB"))
        }
    }

    @Test
    fun mp4LyricsAreReadFromTheMoovUserDataIlstItem() {
        val lyrics = "[00:01.00]M4A 歌词\n[00:03.00]第二句"
        val udta = readMp4Udta(ByteArrayFileBytes(mp4File(lyrics)), "test")
        assertNotNull(udta)
        assertEquals(lyrics, mp4Lyrics(requireNotNull(udta), plain))
    }

    @Test
    fun mp4FreeformLyricsItemIsReadWhenTheCopyrightItemIsMissing() {
        val udta = readMp4Udta(ByteArrayFileBytes(mp4File(null, freeform = "freeform m4a lyrics")), "test")
        assertNotNull(udta)
        assertEquals("freeform m4a lyrics", mp4Lyrics(requireNotNull(udta), plain))
    }

    @Test
    fun mp4WithoutLyricsItemsHasNoLyrics() {
        val udta = readMp4Udta(ByteArrayFileBytes(mp4File("暂无歌词")), "test")
        assertNotNull(udta)
        assertNull(mp4Lyrics(requireNotNull(udta), plain))
        val empty = readMp4Udta(ByteArrayFileBytes(mp4File(null)), "test")
        assertNotNull(empty)
        assertNull(mp4Lyrics(requireNotNull(empty), plain))
    }

    @Test
    fun mp4WithoutUserDataHasNoLyrics() {
        val ftyp = box(ascii("ftyp"), ascii("M4A ") + int32(0) + ascii("M4A ") + int32(0x200))
        val moov = box(ascii("moov"), box(ascii("mvhd"), ByteArray(16)))
        assertNull(readMp4Udta(ByteArrayFileBytes(ftyp + moov), "test"))
    }

    @Test
    fun aMalformedMp4StructureMeansNoLyrics() {
        assertNull(readMp4Udta(ByteArrayFileBytes(ascii("RIFF\u0000\u0000\u0000\u0000WAVEfmt ")), "test"))
        assertNull(readMp4Udta(ByteArrayFileBytes(ByteArray(0)), "test"))
        // A box that claims more bytes than the file holds cannot be walked.
        assertNull(readMp4Udta(ByteArrayFileBytes(int32(4096) + ascii("moov")), "test"))
        // A box smaller than its own header is not a box.
        assertNull(readMp4Udta(ByteArrayFileBytes(int32(4) + ascii("moov")), "test"))
        // A moov whose child claims more bytes than moov holds is not walked either.
        val overlong = box(ascii("moov"), int32(4096) + ascii("udta"))
        assertNull(readMp4Udta(ByteArrayFileBytes(overlong), "test"))
    }

    @Test
    fun anOversizedUserDataBoxIsReported() {
        val udtaSize = 17 * 1024 * 1024
        val moov = int32(8 + udtaSize) + ascii("moov") + int32(udtaSize) + ascii("udta")
        // The walk never reads the declared body: the size is rejected first, so a forged header is
        // enough to prove the ceiling holds.
        val bytes = ByteArrayFileBytes(moov, limit = 8L + udtaSize)
        try {
            readMp4Udta(bytes, "test")
            fail("A user data box larger than the ceiling must be reported")
        } catch (expected: IOException) {
            assertEquals(true, expected.message.orEmpty().contains("16 MB"))
        }
    }

    /** The bytes of a synthetic MP4; [limit] pretends the file is longer than the array. */
    private class ByteArrayFileBytes(private val bytes: ByteArray, private val limit: Long = bytes.size.toLong()) : FileBytes {
        override val length: Long get() = limit

        override fun at(position: Long, count: Int): ByteArray? {
            if (position < 0 || count < 0 || position + count > bytes.size) return null
            return bytes.copyOfRange(position.toInt(), position.toInt() + count)
        }
    }

    private fun extractorInput(bytes: ByteArray): DefaultExtractorInput {
        val stream = ByteArrayInputStream(bytes)
        return DefaultExtractorInput(DataReader { buffer, offset, length -> stream.read(buffer, offset, length) }, 0, bytes.size.toLong())
    }

    /** `USLT`/`ULT` body: encoding byte, language, terminated descriptor, then the lyrics. */
    private fun uslt(encoding: Int, description: String, text: String, charset: Charset, bom: Boolean = false): ByteArray {
        val terminator = if (encoding == 0 || encoding == 3) byteArrayOf(0) else byteArrayOf(0, 0)
        return ByteArrayOutputStream().apply {
            write(encoding)
            write(ascii("eng"))
            write(description.toByteArray(charset))
            write(terminator)
            if (bom) write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
            write(text.toByteArray(charset))
        }.toByteArray()
    }

    /** `TXXX` body: encoding byte, terminated description, then the value. */
    private fun txxx(description: String, value: String): ByteArray = ByteArrayOutputStream().apply {
        write(3)
        write(description.toByteArray(StandardCharsets.UTF_8))
        write(0)
        write(value.toByteArray(StandardCharsets.UTF_8))
    }.toByteArray()

    private fun id3Tag(version: Int, frames: List<ByteArray>): ByteArray {
        val body = ByteArrayOutputStream().apply { frames.forEach(::write) }.toByteArray()
        return ByteArrayOutputStream().apply {
            write(ascii("ID3"))
            write(version)
            write(0)
            write(0)
            write(synchsafe(body.size))
            write(body)
        }.toByteArray()
    }

    private fun frame(id: String, body: ByteArray, version: Int): ByteArray {
        val header = ByteArrayOutputStream()
        header.write(ascii(id))
        if (version == 2) {
            // ID3v2.2 frames have a three character id, a three byte size and no flags.
            header.write(int24(body.size))
        } else {
            header.write(if (version == 4) synchsafe(body.size) else int32(body.size))
            header.write(byteArrayOf(0, 0))
        }
        return header.toByteArray() + body
    }

    /**
     * An M4A-shaped file: `ftyp` and a `moov` whose `udta/meta/ilst` either carries the `©lyr` item,
     * the Apple freeform `----:com.apple.iTunes:LYRICS` item, or nothing at all.
     */
    private fun mp4File(lyrics: String?, freeform: String? = null): ByteArray {
        val items = when {
            lyrics != null -> copyrightLyricsItem(lyrics)
            freeform != null -> freeformLyricsItem(freeform)
            else -> ByteArray(0)
        }
        val ilst = box(ascii("ilst"), items)
        val meta = box(ascii("meta"), byteArrayOf(0, 0, 0, 0) + ilst)
        val udta = box(ascii("udta"), meta)
        val moov = box(ascii("moov"), udta)
        val ftyp = box(ascii("ftyp"), ascii("M4A ") + int32(0) + ascii("M4A ") + int32(0x200))
        return ftyp + moov
    }

    private fun copyrightLyricsItem(lyrics: String): ByteArray =
        box(byteArrayOf(0xA9.toByte(), 'l'.code.toByte(), 'y'.code.toByte(), 'r'.code.toByte()), dataAtom(lyrics))

    private fun freeformLyricsItem(lyrics: String): ByteArray = box(ascii("----"),
        box(ascii("mean"), byteArrayOf(0, 0, 0, 0) + ascii("com.apple.iTunes")) +
            box(ascii("name"), byteArrayOf(0, 0, 0, 0) + ascii("LYRICS")) +
            dataAtom(lyrics))

    /** A `data` atom: size, type, version and flags, locale, then the UTF-8 text. */
    private fun dataAtom(text: String): ByteArray = box(ascii("data"), byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0) + text.toByteArray(StandardCharsets.UTF_8))

    private fun box(type: ByteArray, content: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(int32(8 + content.size))
        write(type)
        write(content)
    }.toByteArray()

    private fun ascii(text: String): ByteArray = text.toByteArray(StandardCharsets.ISO_8859_1)

    private fun int32(value: Int): ByteArray =
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    private fun int24(value: Int): ByteArray =
        byteArrayOf((value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    private fun synchsafe(value: Int): ByteArray = byteArrayOf(
        ((value ushr 21) and 0x7F).toByte(), ((value ushr 14) and 0x7F).toByte(),
        ((value ushr 7) and 0x7F).toByte(), (value and 0x7F).toByte())
}
