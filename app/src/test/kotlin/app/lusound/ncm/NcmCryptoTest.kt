package app.lusound.ncm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The NCM payload cipher is a positional XOR against a fixed 256-byte key stream: the index wraps,
 * and seek correctness depends entirely on that wrap. These are the properties the data source
 * relies on for random access, checked here with an independent key stream instead of the
 * production reader's own output.
 */
class NcmCryptoTest {
    private val keyStream = ByteArray(256) { it.toByte() }

    @Test
    fun emptyInputProducesEmptyOutput() {
        assertArrayEquals(ByteArray(0), decryptNcmBytes(ByteArray(0), 0, keyStream))
    }

    @Test
    fun keyStreamIndexWrapsAt256() {
        val expected = ByteArray(10) { ((250 + it) and 255).toByte() }
        assertArrayEquals(expected, decryptNcmBytes(ByteArray(10), 250, keyStream))
    }

    @Test
    fun decryptionIsItsOwnInverseAtAnyOffset() {
        val original = ByteArray(300) { (it * 7 + 3).toByte() }
        val once = decryptNcmBytes(original, 111, keyStream)
        assertArrayEquals(original, decryptNcmBytes(once, 111, keyStream))
    }

    @Test
    fun seekIntoTheMiddleEqualsSlicingAFullRead() {
        val whole = ByteArray(512) { (it % 251).toByte() }
        val full = decryptNcmBytes(whole, 0, keyStream)
        val fromMiddleLength = 100
        val fromMiddle = decryptNcmBytes(whole.copyOfRange(256, 256 + fromMiddleLength), 256, keyStream)
        assertArrayEquals(full.copyOfRange(256, 256 + fromMiddleLength), fromMiddle)
    }

    @Test
    fun invalidOffsetOrKeyStreamIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { decryptNcmBytes(ByteArray(1), -1, keyStream) }
        assertThrows(IllegalArgumentException::class.java) { decryptNcmBytes(ByteArray(1), 0, ByteArray(255)) }
    }

    @Test
    fun zeroXorWithAZeroKeyStreamIsIdentity() {
        val bytes = byteArrayOf(1, 2, 3)
        assertArrayEquals(bytes, decryptNcmBytes(bytes, 42, ByteArray(256)))
    }
}
