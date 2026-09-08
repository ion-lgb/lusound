package app.lusound

import android.content.Intent
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lusound.library.readAudioDocument
import app.lusound.library.scanDocumentTree
import app.lusound.library.replaceDocumentTree
import app.lusound.ncm.*
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Supply the user's encrypted sample externally; copyrighted audio is never bundled in the test APK. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NcmPlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun importAndPlayEncryptedSample(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as LuSoundApplication
        val sample = File(app.getExternalFilesDir(null), "ncm-sample.ncm")
        check(sample.isFile) { "请先把测试 NCM 原文件推送至应用外部 files/ncm-sample.ncm；不要提供解密后的音频" }
        val digest = MessageDigest.getInstance("SHA-256").digest(sample.readBytes())
        val automation = instrumentation.uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
        val resolver = app.contentResolver
        val parent = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Music")
        val root = checkNotNull(DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, "LuSoundNcm-${System.nanoTime()}"))
        val tree = DocumentsContract.buildTreeDocumentUri(root.authority, DocumentsContract.getDocumentId(root))
        try {
            val document = checkNotNull(DocumentsContract.createDocument(resolver, root, "application/octet-stream", "sample.ncm"))
            checkNotNull(resolver.openOutputStream(document)).use { output -> sample.inputStream().use { it.copyTo(output) } }
            app.grantUriPermission(app.packageName, tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            automation.dropShellPermissionIdentity()
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getDocumentId(document))
            val track = readAudioDocument(app, uri)
            assertEquals("ncm", track.format)
            assertTrue(track.title.isNotBlank())
            assertTrue(track.durationMs > 3000)
            assertNotNull(track.artworkUri)
            openNcmFile(app, uri).use { file ->
                val encrypted = ByteArray(minOf(300000L, file.length - file.header.audioOffset).toInt())
                java.io.DataInputStream(file.input).readFully(encrypted)
                val expected = decryptNcmBytes(encrypted, 0, file.header.keyStream)
                for (offset in listOf(0L, 1L, 255L, 256L, 65537L)) {
                    val source = NcmDataSource(app)
                    try {
                        assertEquals(1024L, source.open(DataSpec.Builder().setUri(ncmPlaybackUri(uri.toString())).setPosition(offset).setLength(1024).build()))
                        val actual = ByteArray(1024)
                        var read = 0
                        while (read < actual.size) {
                            val count = source.read(actual, read, actual.size - read)
                            check(count > 0)
                            read += count
                        }
                        assertEquals(-1, source.read(ByteArray(1), 0, 1))
                        assertArrayEquals(expected.copyOfRange(offset.toInt(), offset.toInt() + 1024), actual)
                    } finally { source.close() }
                }
                val source = NcmDataSource(app)
                try {
                    source.open(DataSpec.Builder().setUri(ncmPlaybackUri(uri.toString())).setPosition(file.length - file.header.audioOffset + 1).build())
                    fail("Out-of-range reads must fail")
                } catch (_: IOException) { } finally { source.close() }
            }
            val snapshot = scanDocumentTree(app, tree)
            assertEquals(1, snapshot.tracks.size)
            assertTrue(snapshot.unsupportedFiles.isEmpty())
            replaceDocumentTree(app.database, snapshot)
            compose.waitUntil(10000) { compose.onAllNodesWithTag("track_$uri").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("track_$uri").performScrollTo().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("mini_player").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("mini_player").performClick()
            screenshot(app, "ncm-player.png")
            compose.onNodeWithTag("close_player").performScrollTo().performClick()
            playRealStream(app, track)
            assertArrayEquals(digest, MessageDigest.getInstance("SHA-256").digest(sample.readBytes()))
        } finally {
            app.database.library().getTracks().filter { it.origin == "DOCUMENT_TREE:$tree" }.map { it.uri }.chunked(500).forEach { app.database.library().deleteTracks(it) }
            automation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
            if (resolver.persistedUriPermissions.any { it.uri == tree }) resolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            DocumentsContract.deleteDocument(resolver, root)
            automation.dropShellPermissionIdentity()
        }
    }
}
