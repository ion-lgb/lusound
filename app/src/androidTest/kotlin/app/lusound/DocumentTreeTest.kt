package app.lusound

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lusound.library.*
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses Android's real external-storage document provider, SQLite and audio metadata reader. */
@RunWith(AndroidJUnit4::class)
class DocumentTreeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun recursivelyScanReconcileAndPreserveOnFailure(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Music")
        val root = checkNotNull(DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, "LuSoundFolder-${System.nanoTime()}"))
        val tree = DocumentsContract.buildTreeDocumentUri(root.authority, DocumentsContract.getDocumentId(root))
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val album = checkNotNull(DocumentsContract.createDocument(resolver, root, DocumentsContract.Document.MIME_TYPE_DIR, "Album"))
            val audio = checkNotNull(DocumentsContract.createDocument(resolver, album, "audio/wav", "song.wav"))
            checkNotNull(resolver.openOutputStream(audio)).use { it.write(testWav()) }
            DocumentsContract.createDocument(resolver, root, "text/plain", "notes.txt")
            DocumentsContract.createDocument(resolver, root, "application/octet-stream", "locked.kgm")
            context.grantUriPermission(context.packageName, tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            automation.dropShellPermissionIdentity()
            assertTrue(resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission })
            val snapshot = scanDocumentTree(context, tree)
            assertEquals(1, snapshot.tracks.size)
            assertEquals(1, snapshot.unsupportedFiles.size)
            assertTrue(snapshot.tracks.single().folder.endsWith("/Album"))
            assertEquals(6000L, snapshot.tracks.single().durationMs)
            replaceDocumentTree(database, snapshot)
            val track = database.library().getTracks().single()
            val playlist = database.library().insertPlaylist(Playlist(0, "Folder", null, null))
            database.library().insertEntry(PlaylistEntry(playlist, track.uri, 0))
            replaceDocumentTree(database, scanDocumentTree(context, tree))
            assertEquals(listOf(track.uri), database.library().playlistTrackUris(playlist))
            lateinit var library: LibraryViewModel
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                library = ViewModelProvider(compose.activity)[LibraryViewModel::class.java]
                library.importFolder(tree)
            }
            compose.waitUntil(10000) { library.folderStatus.value != null }
            compose.onNodeWithTag("nav_settings").performClick()
            compose.onNodeWithTag("rescan_$tree").performScrollTo().performClick()
            compose.waitUntil(10000) { !library.folderScanning.value && library.folderStatus.value != null }
            playRealStream(context as LuSoundApplication, track)
            compose.waitForIdle()
            compose.waitUntil(10000) { !library.folderScanning.value && library.folderStatus.value != null }
            compose.onNodeWithTag("rescan_$tree").assertIsEnabled()
            screenshot(context, "folder-settings.png")
            automation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
            val broken = checkNotNull(DocumentsContract.createDocument(resolver, root, "audio/wav", "broken.wav"))
            automation.dropShellPermissionIdentity()
            try {
                replaceDocumentTree(database, scanDocumentTree(context, tree))
                fail("Unreadable audio must not become a partial successful scan")
            } catch (_: IOException) { }
            assertEquals(listOf(track), database.library().getTracks())
            automation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
            DocumentsContract.deleteDocument(resolver, broken)
            DocumentsContract.deleteDocument(resolver, audio)
            automation.dropShellPermissionIdentity()
            replaceDocumentTree(database, scanDocumentTree(context, tree))
            assertTrue(database.library().getTracks().isEmpty())
            assertTrue(database.library().playlistTrackUris(playlist).isEmpty())
            compose.onNodeWithTag("remove_$tree").performScrollTo().performClick()
            compose.onNodeWithTag("confirm_remove_folder").performClick()
            compose.waitUntil(10000) { tree.toString() !in library.folders.value }
            assertFalse(resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission })
            assertTrue(context.database.library().getTracks().none { it.origin == "DOCUMENT_TREE:$tree" })
            compose.onNodeWithTag("settings_import_folder").performScrollTo().performClick()
            compose.waitUntil(10000) { automation.rootInActiveWindow?.packageName?.toString()?.endsWith("documentsui") == true }
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            compose.waitUntil(10000) { automation.rootInActiveWindow?.packageName?.toString() == context.packageName }
            compose.onNodeWithTag("lusound_root").assertIsDisplayed()
        } finally {
            database.close()
            val app = context as LuSoundApplication
            app.database.library().getTracks().filter { it.origin == "DOCUMENT_TREE:$tree" }.map { it.uri }.chunked(500).forEach { app.database.library().deleteTracks(it) }
            automation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
            if (resolver.persistedUriPermissions.any { it.uri == tree }) resolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            DocumentsContract.deleteDocument(resolver, root)
            automation.dropShellPermissionIdentity()
        }
    }
}
