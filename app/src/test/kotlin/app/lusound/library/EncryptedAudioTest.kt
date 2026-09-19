package app.lusound.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scanner's "encrypted, not yet supported" gate. Getting this wrong in either direction is
 * user-visible: a false negative imports a file that cannot play, a false positive hides a normal
 * one. Both call sites pass an already-lowercased extension.
 */
class EncryptedAudioTest {
    @Test
    fun qmcFamilyIsDetectedByPrefix() {
        assertTrue(isEncryptedAudio("qmc0"))
        assertTrue(isEncryptedAudio("qmc3"))
        assertTrue(isEncryptedAudio("qmcflac"))
        assertTrue(isEncryptedAudio("qmcogg"))
        assertTrue(isEncryptedAudio("qmc"))
    }

    @Test
    fun otherSupportedContainersAreDetectedByName() {
        assertTrue(isEncryptedAudio("ncm"))
        assertTrue(isEncryptedAudio("kgm"))
        assertTrue(isEncryptedAudio("kgma"))
        assertTrue(isEncryptedAudio("vpr"))
    }

    @Test
    fun standardAudioIsNotTreatedAsEncrypted() {
        listOf("mp3", "flac", "m4a", "wav", "ogg", "opus", "aac", "aiff", "wma", "amr", "").forEach {
            assertFalse(it, isEncryptedAudio(it))
        }
    }

    @Test
    fun detectionIsCaseSensitiveSoCallersMustLowercaseFirst() {
        // Documents the contract rather than the intent: both call sites lowercase, and a future
        // third call site that forgets would silently import an unplayable file.
        assertFalse(isEncryptedAudio("NCM"))
        assertFalse(isEncryptedAudio("QMC0"))
        assertTrue("NCM".lowercase().let(::isEncryptedAudio))
    }
}
