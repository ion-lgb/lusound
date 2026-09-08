package app.lusound.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class DocumentTreeSnapshot(val treeUri: String, val tracks: List<Track>, val unsupportedFiles: List<String>)
private data class TreeDirectory(val id: String, val path: String)
private data class TreeDocument(val id: String, val name: String, val mime: String)

/** Reads a complete authorized tree before reconciliation. Incomplete provider results never delete library data. */
suspend fun scanDocumentTree(context: Context, tree: Uri): DocumentTreeSnapshot = withContext(Dispatchers.IO) {
    require(DocumentsContract.isTreeUri(tree)) { "请选择系统文件选择器中的目录" }
    val rootId = DocumentsContract.getTreeDocumentId(tree)
    val root = queryTreeDocuments(context, DocumentsContract.buildDocumentUriUsingTree(tree, rootId)).singleOrNull()
        ?: throw IOException("授权目录不存在或无法读取：$tree")
    if (root.mime != DocumentsContract.Document.MIME_TYPE_DIR) throw IOException("授权位置不是目录：$tree")
    val pending = ArrayDeque<TreeDirectory>()
    pending.add(TreeDirectory(rootId, root.name))
    val visited = mutableSetOf<String>()
    val tracks = mutableListOf<Track>()
    val unsupported = mutableListOf<String>()
    while (pending.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val directory = pending.removeFirst()
        if (!visited.add(directory.id)) throw IOException("目录提供程序返回循环或重复目录：${directory.path}")
        val children = queryTreeDocuments(context, DocumentsContract.buildChildDocumentsUriUsingTree(tree, directory.id))
        if (children.map { it.id }.distinct().size != children.size) throw IOException("目录提供程序返回重复文件：${directory.path}")
        for (child in children) {
            currentCoroutineContext().ensureActive()
            val extension = child.name.substringAfterLast('.', "").lowercase()
            when {
                child.mime == DocumentsContract.Document.MIME_TYPE_DIR -> pending.add(TreeDirectory(child.id, "${directory.path}/${child.name}"))
                isEncryptedAudio(extension) && extension != "ncm" -> unsupported.add("${directory.path}/${child.name}")
                child.mime.startsWith("audio/") || extension in setOf("ncm", "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "aiff", "aif", "wma", "amr") -> {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, child.id)
                    val track = try { readAudioDocument(context, uri) }
                    catch (error: IllegalArgumentException) { throw IOException("无法读取音频：${directory.path}/${child.name}，URI=$uri", error) }
                    tracks.add(track.copy(folder = directory.path, origin = "DOCUMENT_TREE:$tree"))
                }
            }
        }
    }
    if (tracks.map { it.uri }.distinct().size != tracks.size) throw IOException("目录提供程序在多个位置返回同一音频，请检查目录结构")
    DocumentTreeSnapshot(tree.toString(), tracks, unsupported)
}

private fun queryTreeDocuments(context: Context, uri: Uri): List<TreeDocument> {
    val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
    val cursor = context.contentResolver.query(uri, projection, null, null, null) ?: throw IOException("目录提供程序未返回结果：$uri")
    return cursor.use {
        if (it.extras.getBoolean(DocumentsContract.EXTRA_LOADING)) throw IOException("目录仍在加载，请稍后重新扫描：$uri")
        it.extras.getString(DocumentsContract.EXTRA_ERROR)?.let { message -> throw IOException("目录读取失败：$uri，$message") }
        buildList {
            while (it.moveToNext()) {
                if ((0..2).any { column -> it.isNull(column) || it.getString(column).isBlank() }) throw IOException("目录返回的文件信息不完整：$uri")
                add(TreeDocument(it.getString(0), it.getString(1), it.getString(2)))
            }
        }
    }
}

/** Updates this tree only, preserving playlist positions for unchanged document URIs. */
suspend fun replaceDocumentTree(database: LibraryDatabase, snapshot: DocumentTreeSnapshot) {
    database.withTransaction {
        val uris = snapshot.tracks.map { it.uri }.toSet()
        val removed = database.library().getTracks().filter { it.origin == "DOCUMENT_TREE:${snapshot.treeUri}" && it.uri !in uris }.map { it.uri }
        removed.chunked(500).forEach { database.library().deleteTracks(it) }
        database.library().upsertTracks(snapshot.tracks)
    }
}

fun isEncryptedAudio(extension: String): Boolean = extension.startsWith("qmc") || extension in setOf("ncm", "kgm", "kgma", "vpr")
