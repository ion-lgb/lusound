package app.lusound.ncm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a container's declared audio format matches the payload it actually carries.
 *
 * The declaration is only a claim, and a mismatch that slips through surfaces as a decoder failure far
 * from its cause. The markers themselves are what the reader recognises, so they are pinned here.
 */
class NcmPayloadSignatureTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun ascii(value: String) = value.toByteArray(Charsets.US_ASCII)

    @Test
    fun flacIsRecognisedByItsMarker() {
        assertTrue(payloadMatchesDeclaredFormat("flac", ascii("fLaC")))
        assertTrue(payloadMatchesDeclaredFormat("flac", ascii("fLaC") + bytes(0, 0, 0, 34)))
    }

    @Test
    fun mp3IsRecognisedByATagOrAFrameSync() {
        assertTrue(payloadMatchesDeclaredFormat("mp3", ascii("ID3") + bytes(4, 0, 0)))
        assertTrue("MPEG frame sync, 11 bits set", payloadMatchesDeclaredFormat("mp3", bytes(0xff, 0xfb, 0x90)))
        assertTrue(payloadMatchesDeclaredFormat("mp3", bytes(0xff, 0xe0, 0x00)))
    }

    @Test
    fun aDeclarationThatDoesNotMatchThePayloadIsRejected() {
        assertFalse(payloadMatchesDeclaredFormat("flac", ascii("ID3") + bytes(4, 0, 0)))
        assertFalse(payloadMatchesDeclaredFormat("mp3", ascii("fLaC") + bytes(0)))
        assertFalse(payloadMatchesDeclaredFormat("flac", ascii("fLaD")))
    }

    @Test
    fun aNearMissOnTheMpegSyncIsRejected() {
        // 0x1f leaves the three sync-protection bits clear, so this is not a frame header.
        assertFalse(payloadMatchesDeclaredFormat("mp3", bytes(0xff, 0x1f, 0x00)))
        assertFalse(payloadMatchesDeclaredFormat("mp3", bytes(0xfe, 0xfb, 0x00)))
    }

    @Test
    fun aFormatTheReaderDoesNotSupportIsRejectedEvenWithAPlausiblePayload() {
        assertFalse(payloadMatchesDeclaredFormat("ogg", ascii("OggS")))
        assertFalse(payloadMatchesDeclaredFormat("", ascii("fLaC")))
        assertFalse(payloadMatchesDeclaredFormat("wav", ascii("RIFF")))
    }

    @Test
    fun aShortOrEmptySignatureIsRejectedRatherThanIndexedInto() {
        assertFalse(payloadMatchesDeclaredFormat("flac", bytes(0x66, 0x4c, 0x61)))
        assertFalse(payloadMatchesDeclaredFormat("mp3", bytes(0x49, 0x44)))
        assertFalse(payloadMatchesDeclaredFormat("mp3", bytes(0xff)))
        assertFalse(payloadMatchesDeclaredFormat("flac", ByteArray(0)))
        assertFalse(payloadMatchesDeclaredFormat("mp3", ByteArray(0)))
    }
}
