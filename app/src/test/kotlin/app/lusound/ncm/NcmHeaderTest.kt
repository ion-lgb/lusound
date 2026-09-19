package app.lusound.ncm

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The NCM container header, built here from the published format description rather than from a
 * sample file. The instrumented `NcmContainerTest` can only run where a real encrypted sample has
 * been pushed to the device, so every validation branch below — bad magic, oversized blocks, an
 * unsupported audio format, a truncated file — would otherwise never be exercised at all.
 *
 * The synthesised key stream is deliberately not asserted against a reimplementation of the same
 * algorithm: that would only prove the copy matches itself. What is checked here is the container
 * arithmetic, the decrypt-and-decode chain and the rejection surface; byte-exact payload decryption
 * against a real sample stays with the instrumented test.
 */
class NcmHeaderTest {
    @Test
    fun validContainerDecodesMetadataAndOffsets() {
        val built = NcmBuilder(title = "半岛铁盒", artists = listOf("周杰伦"), album = "八度空间", durationMs = 260_000, format = "flac")
            .withCover(coverLength = 128, frameLength = 256)
            .build()

        val header = readNcmHeader(ByteArrayInputStream(built.bytes), built.bytes.size.toLong())

        assertEquals("半岛铁盒", header.metadata.title)
        assertEquals("周杰伦", header.metadata.artist)
        assertEquals("八度空间", header.metadata.album)
        assertEquals(260_000L, header.metadata.durationMs)
        assertEquals("flac", header.metadata.format)
        assertEquals(256, header.keyStream.size)
        assertEquals(built.coverOffset, header.coverOffset)
        assertEquals(128, header.coverLength)
        assertEquals(built.audioOffset, header.audioOffset)
        assertTrue("audio must follow the cover frame", header.audioOffset > header.coverOffset)
    }

    @Test
    fun multipleArtistsAreJoinedForDisplay() {
        val built = NcmBuilder(artists = listOf("蔡依林", "周杰伦")).build()

        val header = readNcmHeader(ByteArrayInputStream(built.bytes), built.bytes.size.toLong())

        assertEquals("蔡依林 / 周杰伦", header.metadata.artist)
    }

    @Test
    fun aContainerWithoutCoverStillPlacesAudioCorrectly() {
        val built = NcmBuilder().withCover(coverLength = 0, frameLength = 0).build()

        val header = readNcmHeader(ByteArrayInputStream(built.bytes), built.bytes.size.toLong())

        assertEquals(0, header.coverLength)
        assertEquals(built.coverOffset, header.audioOffset)
    }

    @Test
    fun badMagicIsRejected() {
        val built = NcmBuilder(magic = "NOTANCM!".toByteArray(Charsets.US_ASCII)).build()

        assertThrows(NcmException::class.java) { readNcmHeader(ByteArrayInputStream(built.bytes), built.bytes.size.toLong()) }
    }

    @Test
    fun keyBlockWithoutTheExpectedPrefixIsRejected() {
        val built = NcmBuilder(keyPrefix = "someothermusic").build()

        assertThrows(NcmException::class.java) { readNcmHeader(ByteArrayInputStream(built.bytes), built.bytes.size.toLong()) }
    }

    @Test
    fun oversizedKeyBlockIsRejectedBeforeItIsRead() {
        val built = NcmBuilder(keyBlockLengthOverride = 4097).build()

        val error = assertThrows(NcmException::class.java) { readNcmHeader(ByteArrayInputStream(built.bytes), built.bytes.size.toLong()) }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("密钥"))
    }

    @Test
    fun oversizedMetadataBlockIsRejected() {
        val built = NcmBuilder(metadataBlockLengthOverride = 1024 * 1024 + 1).build()

        val error = assertThrows(NcmException::class.java) { readNcmHeader(ByteArrayInputStream(built.bytes), built.bytes.size.toLong()) }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("元数据"))
    }

    @Test
    fun aKeyBlockThatIsNotAWholeAesBlockIsRejected() {
        val built = NcmBuilder(keyBlockLengthOverride = 15).build()

        assertThrows(NcmException::class.java) { readNcmHeader(ByteArrayInputStream(built.bytes), built.bytes.size.toLong()) }
    }

    @Test
    fun anUnsupportedPayloadFormatIsRejected() {
        val built = NcmBuilder(format = "ogg").build()

        val error = assertThrows(NcmException::class.java) { readNcmHeader(ByteArrayInputStream(built.bytes), built.bytes.size.toLong()) }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("MP3/FLAC"))
    }

    @Test
    fun aBlankTitleIsRejected() {
        val built = NcmBuilder(title = "   ").build()

        assertThrows(NcmException::class.java) { readNcmHeader(ByteArrayInputStream(built.bytes), built.bytes.size.toLong()) }
    }

    @Test
    fun aNonPositiveDurationIsRejected() {
        assertThrows(NcmException::class.java) {
            val built = NcmBuilder(durationMs = 0).build()
            readNcmHeader(ByteArrayInputStream(built.bytes), built.bytes.size.toLong())
        }
    }

    @Test
    fun metadataWithoutItsPrefixIsRejected() {
        val built = NcmBuilder(metadataTextOverride = "not the expected prefix").build()

        val error = assertThrows(NcmException::class.java) { readNcmHeader(ByteArrayInputStream(built.bytes), built.bytes.size.toLong()) }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("前缀"))
    }

    @Test
    fun metadataThatIsNotBase64IsRejected() {
        val built = NcmBuilder(metadataTextOverride = "163 key(Don't modify):not*valid*base64").build()

        assertThrows(NcmException::class.java) { readNcmHeader(ByteArrayInputStream(built.bytes), built.bytes.size.toLong()) }
    }

    @Test
    fun decodedMetadataThatIsNotMusicIsRejected() {
        // Correctly encoded and encrypted, but the payload is not a music record.
        val built = NcmBuilder(metadataPayloadOverride = "video:{\"a\":1}").build()

        val error = assertThrows(NcmException::class.java) { readNcmHeader(ByteArrayInputStream(built.bytes), built.bytes.size.toLong()) }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("音乐元数据"))
    }

    @Test
    fun aFileWithNoAudioAfterTheCoverIsRejected() {
        // Declares one audio byte fewer than it actually needs to hold a payload.
        val built = NcmBuilder().withCover(coverLength = 16, frameLength = 16).build()
        val truncated = built.bytes.copyOfRange(0, built.audioOffset.toInt())

        val error = assertThrows(NcmException::class.java) { readNcmHeader(ByteArrayInputStream(truncated), truncated.size.toLong()) }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("截断"))
    }

    @Test
    fun aCoverLengthBeyondItsFrameIsRejected() {
        val built = NcmBuilder().withCover(coverLength = 64, frameLength = 32).build()

        val error = assertThrows(NcmException::class.java) { readNcmHeader(ByteArrayInputStream(built.bytes), built.bytes.size.toLong()) }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("封面"))
    }

    // ---- skipping the cover frame, which must never be materialised in memory ----

    @Test
    fun skipConsumesExactlyTheRequestedByteCount() {
        val stream = ByteArrayInputStream(ByteArray(100) { it.toByte() })

        skipNcmBytes(stream, 40)

        assertEquals(0x28, stream.read())
    }

    @Test
    fun skippingZeroBytesReadsNothing() {
        val stream = ByteArrayInputStream(byteArrayOf(1, 2, 3))

        skipNcmBytes(stream, 0)

        assertEquals(1, stream.read())
    }

    @Test
    fun skipFallsBackToReadingWhenAStreamSkipsLessThanAsked() {
        // A provider-backed stream is allowed to skip fewer bytes than requested, or none at all.
        val stream = ZeroSkipStream(ByteArray(10) { it.toByte() })

        skipNcmBytes(stream, 4)

        assertEquals("exactly four bytes must be consumed", 6, stream.available())
        assertEquals(4, stream.read())
    }

    @Test
    fun skippingPastTheEndIsRejected() {
        val stream = ByteArrayInputStream(ByteArray(5))

        val error = assertThrows(NcmException::class.java) { skipNcmBytes(stream, 6) }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("截断"))
    }

    /** A stream whose `skip` never advances, forcing the read-one-byte fallback. */
    private class ZeroSkipStream(bytes: ByteArray) : java.io.InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        override fun read(): Int = delegate.read()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate.read(buffer, offset, length)
        override fun skip(count: Long): Long = 0
        override fun available(): Int = delegate.available()
    }

    @Test
    fun theAudioOffsetIsExactlyTheDocumentedFieldLayout() {
        val built = NcmBuilder().withCover(coverLength = 8, frameLength = 8).build()
        val expectedCoverOffset = 10L + 4 + built.keyBlockSize + 4 + built.metadataBlockSize + 5 + 4 + 4

        val header = readNcmHeader(ByteArrayInputStream(built.bytes), built.bytes.size.toLong())

        assertEquals(expectedCoverOffset, header.coverOffset)
        assertEquals(expectedCoverOffset + 8, header.audioOffset)
    }

    @Test
    fun anEmptyStreamIsRejectedRatherThanReturningGarbage() {
        assertThrows(IOException::class.java) { readNcmHeader(ByteArrayInputStream(ByteArray(0)), 0) }
    }

    @Test
    fun theDerivedKeyStreamIsDeterministicForTheSameKeyBlock() {
        val first = NcmBuilder().build()
        val second = NcmBuilder().build()

        val a = readNcmHeader(ByteArrayInputStream(first.bytes), first.bytes.size.toLong())
        val b = readNcmHeader(ByteArrayInputStream(second.bytes), second.bytes.size.toLong())

        assertArrayEquals(a.keyStream, b.keyStream)
    }

    @Test
    fun aDifferentStreamKeyProducesADifferentKeyStream() {
        val first = NcmBuilder(streamKey = ByteArray(32) { it.toByte() }).build()
        val second = NcmBuilder(streamKey = ByteArray(32) { (it + 1).toByte() }).build()

        val a = readNcmHeader(ByteArrayInputStream(first.bytes), first.bytes.size.toLong())
        val b = readNcmHeader(ByteArrayInputStream(second.bytes), second.bytes.size.toLong())

        assertTrue("changing the container key must change the derived stream", !a.keyStream.contentEquals(b.keyStream))
    }

    /** Builds a container byte-for-byte from the public description of the format. */
    private class NcmBuilder(
        private val magic: ByteArray = "CTENFDAM".toByteArray(Charsets.US_ASCII),
        private val streamKey: ByteArray = ByteArray(32) { (it * 3).toByte() },
        private val keyPrefix: String = "neteasecloudmusic",
        private val title: String = "标题",
        private val artists: List<String> = listOf("歌手"),
        private val album: String = "专辑",
        private val durationMs: Long = 1_000,
        private val format: String = "mp3",
        private val keyBlockLengthOverride: Int? = null,
        private val metadataBlockLengthOverride: Int? = null,
        private val metadataTextOverride: String? = null,
        private val metadataPayloadOverride: String? = null,
    ) {
        private var coverLength = 0
        private var frameLength = 0
        var keyBlockSize = 0
            private set
        var metadataBlockSize = 0
            private set
        var coverOffset = 0L
            private set
        var audioOffset = 0L
            private set

        fun withCover(coverLength: Int, frameLength: Int): NcmBuilder = apply {
            this.coverLength = coverLength
            this.frameLength = frameLength
        }

        fun build(): Built {
            val keyBlock = xor(
                aesEncrypt((keyPrefix + String(streamKey, Charsets.ISO_8859_1)).toByteArray(Charsets.ISO_8859_1), CORE_KEY),
                0x64,
            )
            val metadataText = metadataTextOverride ?: run {
                val payload = metadataPayloadOverride ?: ("music:" + metadataJson())
                "163 key(Don't modify):" + Base64.getEncoder().encodeToString(aesEncrypt(payload.toByteArray(Charsets.UTF_8), META_KEY))
            }
            val metadataBlock = xor(metadataText.toByteArray(Charsets.UTF_8), 0x63)
            keyBlockSize = keyBlockLengthOverride ?: keyBlock.size
            metadataBlockSize = metadataBlockLengthOverride ?: metadataBlock.size
            coverOffset = 10L + 4 + keyBlockSize + 4 + metadataBlockSize + 5 + 4 + 4
            audioOffset = coverOffset + frameLength

            val output = ByteArrayOutputStream()
            DataOutputStream(output).use { stream ->
                stream.write(magic)
                stream.writeShort(0)
                stream.writeLength(keyBlockSize)
                stream.write(keyBlock)
                stream.writeLength(metadataBlockSize)
                stream.write(metadataBlock)
                stream.writeInt(0) // container CRC, not authenticated
                stream.writeByte(0) // cover version
                stream.writeLength(frameLength)
                stream.writeLength(coverLength)
                stream.write(ByteArray(frameLength))
                stream.write(ByteArray(AUDIO_BYTES))
            }
            return Built(output.toByteArray(), keyBlockSize, metadataBlockSize, coverOffset, audioOffset)
        }

        /**
         * Container lengths are little-endian: the reader applies `Integer.reverseBytes` to a
         * big-endian `readInt`.
         */
        private fun DataOutputStream.writeLength(value: Int) = writeInt(Integer.reverseBytes(value))

        private fun metadataJson() =
            """{"musicName":"$title","artist":[${artists.joinToString(",") { """["$it",1]""" }}],"album":"$album","duration":$durationMs,"format":"$format"}"""
    }

    private data class Built(val bytes: ByteArray, val keyBlockSize: Int, val metadataBlockSize: Int, val coverOffset: Long, val audioOffset: Long)

    private companion object {
        const val CORE_KEY = "687a4852416d736f356b496e62617857"
        const val META_KEY = "2331346c6a6b5f215c5d2630553c2728"
        const val AUDIO_BYTES = 64

        fun xor(bytes: ByteArray, mask: Int) = ByteArray(bytes.size) { (bytes[it].toInt() xor mask).toByte() }

        fun aesEncrypt(plain: ByteArray, keyHex: String): ByteArray =
            Cipher.getInstance("AES/ECB/PKCS5Padding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), "AES"))
            }.doFinal(plain)
    }
}
