package app.lusound.library

import androidx.media3.common.DataReader
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.ExtractorInput
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading quality out of a FLAC stream header.
 *
 * The streams here are synthesised byte by byte from the format description, including the bit-packed
 * STREAMINFO fields, so the test also checks that the packing is right: if the sample rate or bit
 * depth were packed wrongly, Media3 would read back different numbers. This matters because a FLAC
 * header is the only place a bit depth can be read on every supported API level.
 */
class FlacStreamInfoTest {
    @Test
    fun readsSampleRateAndBitDepthFromStreamInformation() {
        val info = readFlacStreamInfo(flacInput(flacStream(sampleRate = 44_100, channels = 2, bitsPerSample = 16)))

        assertEquals(44_100, info?.sampleRateHz)
        assertEquals(16, info?.bitDepth)
    }

    @Test
    fun readsAHighResolutionStream() {
        val info = readFlacStreamInfo(flacInput(flacStream(sampleRate = 96_000, channels = 2, bitsPerSample = 24)))

        assertEquals(96_000, info?.sampleRateHz)
        assertEquals(24, info?.bitDepth)
    }

    @Test
    fun readsAMonoLowRateStreamAndANonZeroSampleCount() {
        val info = readFlacStreamInfo(flacInput(flacStream(sampleRate = 8_000, channels = 1, bitsPerSample = 8, totalSamples = 123_456)))

        assertEquals(8_000, info?.sampleRateHz)
        assertEquals(8, info?.bitDepth)
    }

    @Test
    fun aStreamThatIsNotFlacIsRejected() {
        val mp3 = "ID3\u0004\u0000\u0000\u0000\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1)

        assertNull(readFlacStreamInfo(flacInput(mp3)))
        assertNull(readFlacStreamInfo(flacInput(ByteArray(0))))
    }

    @Test
    fun aTruncatedHeaderIsRejectedRatherThanThrowing() {
        assertNull("only the marker", readFlacStreamInfo(flacInput("fLaC".toByteArray(Charsets.US_ASCII))))
        val full = flacStream(sampleRate = 44_100, channels = 2, bitsPerSample = 16)
        assertNull("the block header is cut off", readFlacStreamInfo(flacInput(full.copyOfRange(0, 6))))
        assertNull("the stream information is cut short", readFlacStreamInfo(flacInput(full.copyOfRange(0, 20))))
    }

    @Test
    fun aStreamWhoseFirstBlockIsNotStreamInformationIsRejected() {
        // A file that starts with something other than STREAMINFO is malformed whatever else it holds.
        val bytes = flacStream(sampleRate = 44_100, channels = 2, bitsPerSample = 16).copyOf()
        bytes[4] = (0x80 or 4).toByte() // last-block flag with block type 4 (VORBIS_COMMENT)

        assertNull(readFlacStreamInfo(flacInput(bytes)))
    }

    @Test
    fun anImplausibleBlockSizeIsRejectedBeforeItIsRead() {
        val bytes = flacStream(sampleRate = 44_100, channels = 2, bitsPerSample = 16).copyOf()
        bytes[5] = 0xFF.toByte() // claim a block far larger than any real metadata region
        bytes[6] = 0xFF.toByte()
        bytes[7] = 0xFF.toByte()

        assertNull(readFlacStreamInfo(flacInput(bytes)))
    }

    /** Wraps the array in an [ExtractorInput] without pulling in an Android data source. */
    private fun flacInput(bytes: ByteArray): ExtractorInput {
        var position = 0
        val reader = DataReader { buffer, offset, length ->
            val count = minOf(length, bytes.size - position)
            if (count <= 0) {
                -1
            } else {
                System.arraycopy(bytes, position, buffer, offset, count)
                position += count
                count
            }
        }
        return DefaultExtractorInput(reader, 0, bytes.size.toLong())
    }

    /** A FLAC stream holding nothing but its STREAMINFO block. */
    private fun flacStream(sampleRate: Int, channels: Int, bitsPerSample: Int, totalSamples: Long = 0): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.US_ASCII))
        out.write(0x80) // last metadata block, block type 0 = STREAMINFO
        out.write(byteArrayOf(0, 0, 34)) // its length
        out.write(byteArrayOf(0x10, 0x00)) // minimum block size
        out.write(byteArrayOf(0x10, 0x00)) // maximum block size
        out.write(byteArrayOf(0, 0, 0)) // minimum frame size
        out.write(byteArrayOf(0, 0, 0)) // maximum frame size
        out.write(
            byteArrayOf(
                ((sampleRate shr 12) and 0xFF).toByte(),
                ((sampleRate shr 4) and 0xFF).toByte(),
                (((sampleRate and 0x0F) shl 4) or (((channels - 1) and 0x07) shl 1) or (((bitsPerSample - 1) shr 4) and 0x01)).toByte(),
                ((((bitsPerSample - 1) and 0x0F) shl 4) or ((totalSamples shr 32).toInt() and 0x0F)).toByte(),
                ((totalSamples shr 24) and 0xFF).toByte(),
                ((totalSamples shr 16) and 0xFF).toByte(),
                ((totalSamples shr 8) and 0xFF).toByte(),
                (totalSamples and 0xFF).toByte(),
            ),
        )
        repeat(16) { out.write(0) } // stream MD5, not read here
        return out.toByteArray()
    }
}
