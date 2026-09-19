package app.lusound.library

import org.junit.Assert.assertEquals
import org.junit.Test

/** The single container vocabulary and the two labels derived from it. */
class TrackSourceTest {
    @Test
    fun mediaStoreMimeSubtypesNormaliseToContainers() {
        // MediaStore reports a MIME subtype; the extension is what users recognise.
        assertEquals("mp3", audioContainer("mpeg"))
        assertEquals("m4a", audioContainer("mp4"))
        assertEquals("wav", audioContainer("x-wav"))
        assertEquals("aiff", audioContainer("x-aiff"))
        assertEquals("wma", audioContainer("x-ms-wma"))
    }

    @Test
    fun valuesThatAreAlreadyContainersPassThroughCaseInsensitively() {
        listOf("mp3", "flac", "ogg", "opus", "aac", "amr").forEach {
            assertEquals(it, audioContainer(it))
            assertEquals(it, audioContainer(it.uppercase()))
        }
    }

    @Test
    fun surroundingWhitespaceAndTheNcmContainerAreHandled() {
        assertEquals("flac", audioContainer("  FLAC  "))
        assertEquals(NCM_CONTAINER, audioContainer("NCM"))
    }

    @Test
    fun anUnknownContainerIsLowercasedRatherThanGuessed() {
        // Guessing a codec from an unrecognised value is exactly what must not happen here.
        assertEquals("weird-codec", audioContainer("Weird-Codec"))
        assertEquals("", audioContainer(""))
    }

    @Test
    fun containerLabelShowsUnknownInsteadOfNothing() {
        assertEquals("FLAC", containerLabel("flac"))
        assertEquals("未知格式", containerLabel(""))
    }

    @Test
    fun sourceLabelComesFromStoredFieldsNotFromTheUriShape() {
        assertEquals("在线", sourceLabel(track("lusound://srv1/1", "mp3", TrackSource.CLOUD, "srv1")))
        assertEquals("来源：网易云", sourceLabel(track("content://tree/document/1", NCM_CONTAINER, TrackSource.DOCUMENT_TREE, "content://tree/1")))
        assertEquals("本地", sourceLabel(track("content://media/1", "flac", TrackSource.MEDIASTORE, null)))
        // A cloud track whose URI is not the usual shape must still read as online.
        assertEquals("在线", sourceLabel(track("https://example.com/a.mp3", "mp3", TrackSource.CLOUD, "srv1")))
        // And a local track must not be mistaken for a server one by its URI.
        assertEquals("本地", sourceLabel(track("content://media/2", "mp3", TrackSource.DOCUMENT, null)))
    }

    private fun track(uri: String, container: String, sourceKind: String, sourceRef: String?) =
        Track(uri, "标题", "歌手", "专辑", "Music", 1_000, null, container, sourceKind, sourceRef)
}
