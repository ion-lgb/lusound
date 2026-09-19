/* Sidecar .lrc discovery: a lyrics file whose name matches the audio file next to it. */
package app.lusound.metadata

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.net.toUri
import app.lusound.library.Track
import app.lusound.library.TrackSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** A lyrics file that was found next to the audio file. */
internal data class SidecarLyrics(val text: String, val uri: Uri)

/** A lyrics text file larger than this is treated as unreadable rather than as lyrics. */
private const val MAX_SIDECAR_BYTES = 2 * 1024 * 1024

/**
 * The name an LRC file must carry to belong to [audioName]: everything before the audio file's last
 * dot, so `Song.mp3` is matched by `Song.lrc` and `01. Song.flac` by `01. Song.lrc`.
 */
internal fun lyricsBaseName(audioName: String): String {
    val name = audioName.trim()
    return name.substringBeforeLast('.', missingDelimiterValue = name)
}

/**
 * How well [siblingName] matches [audioName], or null when it is not that audio's lyrics file.
 *
 * Lower is better: `Song.lrc` (0) is the documented sidecar name, while `Song.mp3.lrc` (1) is
 * accepted because some taggers append to the whole file name. The extension is compared
 * case-insensitively because filesystems hand out `.LRC` as readily as `.lrc`.
 */
internal fun sidecarRank(audioName: String, siblingName: String): Int? {
    if (siblingName.substringAfterLast('.', "").lowercase() != "lrc") return null
    val base = lyricsBaseName(audioName)
    if (base.isBlank()) return null
    return when {
        lyricsBaseName(siblingName).equals(base, ignoreCase = true) -> 0
        lyricsBaseName(siblingName).equals(audioName.trim(), ignoreCase = true) -> 1
        else -> null
    }
}

/** Picks the lyrics file for [audioName] out of one directory listing, or null when there is none. */
internal fun pickSidecar(audioName: String, siblingNames: Collection<String>): String? =
    siblingNames.mapNotNull { name -> sidecarRank(audioName, name)?.let { rank -> rank to name } }
        .minWithOrNull(compareBy({ it.first }, { it.second }))?.second

/**
 * Decodes a lyrics file.
 *
 * LRC files in the wild are UTF-8 (often with a byte order mark), UTF-16 with a byte order mark, or
 * a legacy Chinese code page such as GBK. A file is only decoded as UTF-8 when every byte really is
 * UTF-8, so GBK lyrics are not turned into replacement characters.
 */
internal fun decodeLyricsBytes(bytes: ByteArray): String = when {
    bytes.startsWithBom(0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
    bytes.startsWithBom(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
    bytes.startsWithBom(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
    else -> decodeUtf8OrGbk(bytes)
}

private fun ByteArray.startsWithBom(vararg bom: Int): Boolean =
    size >= bom.size && bom.indices.all { this[it] == bom[it].toByte() }

private fun decodeUtf8OrGbk(bytes: ByteArray): String {
    val strict = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        strict.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        String(bytes, Charset.forName("GB18030"))
    }
}

/**
 * Lyrics from the `.lrc` file that sits next to the audio file, or null when there is none.
 *
 * Finding a sidecar never fails the metadata load: a provider that cannot list siblings, an expired
 * grant or a document without a parent directory simply means "no sidecar". Reading a sidecar that
 * was found is a different matter and throws, because silently reporting "no lyrics" would hide a
 * real problem.
 */
internal fun readSidecarLyrics(context: Context, track: Track): SidecarLyrics? {
    val uri = findSidecar(context, track) ?: return null
    val text = usableLyrics(readSidecarText(context, uri)) ?: return null
    return SidecarLyrics(text, uri)
}

private fun findSidecar(context: Context, track: Track): Uri? {
    val audio = track.uri.toUri()
    return when {
        audio.scheme == "file" -> fileSidecar(audio)
        audio.scheme != "content" -> null
        track.sourceKind == TrackSource.DOCUMENT_TREE -> documentTreeSidecar(context, track, audio)
        track.sourceKind == TrackSource.MEDIASTORE -> mediaStoreSidecar(context, track, audio)
        else -> singleDocumentSidecar(context, audio)
    }
}

/** An imported `file://` track simply has a real directory to look in. */
private fun fileSidecar(audio: Uri): Uri? {
    val file = audio.path?.let(::File) ?: return null
    val directory = file.parentFile ?: return null
    val name = pickSidecar(file.name, directory.list()?.toList().orEmpty()) ?: return null
    return Uri.fromFile(File(directory, name))
}

/**
 * An authorised tree: the audio document's own directory is resolved through `DocumentsContract`,
 * then that directory's children are listed and matched by name. Asking the audio document for its
 * display name costs one small query but is the only provider-independent way to learn the file's
 * own name, since the tree scan stores a tag title rather than a file name.
 */
private fun documentTreeSidecar(context: Context, track: Track, audio: Uri): Uri? {
    val tree = track.sourceRef?.toUri() ?: return null
    val parent = documentParent(context, audio) ?: return null
    val audioName = queryDisplayName(context, audio) ?: return null
    val siblings = queryChildren(context, DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)) ?: return null
    val match = pickSidecar(audioName, siblings.map { it.second }) ?: return null
    return DocumentsContract.buildDocumentUriUsingTree(tree, siblings.first { it.second == match }.first)
}

/** A single imported document: only some providers expose the parent directory at all. */
private fun singleDocumentSidecar(context: Context, audio: Uri): Uri? {
    val authority = audio.authority ?: return null
    val parent = derivedParentId(audio) ?: return null
    val audioName = queryDisplayName(context, audio) ?: return null
    val children = DocumentsContract.buildChildDocumentsUri(authority, parent)
    val siblings = queryChildren(context, children) ?: return null
    val match = pickSidecar(audioName, siblings.map { it.second }) ?: return null
    return DocumentsContract.buildDocumentUri(authority, siblings.first { it.second == match }.first)
}

/**
 * MediaStore lyrics live in the same relative directory as the audio, so the media provider can list
 * them without an absolute path. From API 29 the relative path is the portable column; before that
 * the absolute `DATA` path is the only one, and rows are narrowed back to direct children because
 * `LIKE` also matches subdirectories.
 */
private fun mediaStoreSidecar(context: Context, track: Track, audio: Uri): Uri? {
    val volume = audio.pathSegments.firstOrNull() ?: return null
    val collection = MediaStore.Files.getContentUri(volume)
    val folder = track.folder.trimEnd('/')
    val projection: Array<String>
    val selection: String
    val arguments: Array<String>
    if (Build.VERSION.SDK_INT >= 29) {
        // A few providers store the path without its trailing slash and a file in the volume root
        // has an empty relative path, so both spellings of this one exact directory are accepted.
        projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        selection = "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?)"
        arguments = arrayOf("$folder/", folder)
    } else {
        projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA)
        selection = "${MediaStore.MediaColumns.DATA} LIKE ? ESCAPE '\\'"
        arguments = arrayOf("${escapeLike(folder)}/%")
    }
    val rows = mediaStoreRows(context, collection, projection, selection, arguments, if (projection.size > 2) folder else null)
    val audioName = queryDisplayName(context, audio) ?: return null
    val match = pickSidecar(audioName, rows.map { it.second }) ?: return null
    return ContentUris.withAppendedId(collection, rows.first { it.second == match }.first)
}

/**
 * The `_id` and display name of every direct child of one media directory. A provider that refuses
 * the query has no sidecar to offer, which is not a failure of the metadata load.
 */
private fun mediaStoreRows(context: Context, collection: Uri, projection: Array<String>, selection: String,
    arguments: Array<String>, directChildrenOf: String?): List<Pair<Long, String>> {
    val cursor = try { context.contentResolver.query(collection, projection, selection, arguments, null) }
    catch (_: RuntimeException) { null } ?: return emptyList()
    val rows = mutableListOf<Pair<Long, String>>()
    try {
        while (cursor.moveToNext()) {
            val name = cursor.getString(1) ?: continue
            if (directChildrenOf != null && cursor.getString(2)?.substringBeforeLast('/', "") != directChildrenOf) continue
            rows += cursor.getLong(0) to name
        }
    } finally { cursor.close() }
    return rows
}

/**
 * The directory that contains [document], asked of the provider itself so that no document id layout
 * is assumed: the returned path runs from the provider's root down to the document, so its second to
 * last entry is the directory. Providers that cannot answer fall back to trimming the id at its last
 * slash, which is the layout every filesystem-backed provider uses.
 */
private fun documentParent(context: Context, document: Uri): String? {
    val path = try { DocumentsContract.findDocumentPath(context.contentResolver, document)?.path }
    catch (_: Exception) { null }
    return path?.takeIf { it.size >= 2 }?.let { it[it.size - 2] }?.takeIf { it.isNotBlank() } ?: derivedParentId(document)
}

private fun derivedParentId(document: Uri): String? {
    val id = try { DocumentsContract.getDocumentId(document) } catch (_: Exception) { null } ?: return null
    return id.substringBeforeLast('/', "").takeIf { it.isNotBlank() }
}

/** A provider that refuses this query means "no sidecar", never a failed metadata load. */
private fun queryDisplayName(context: Context, document: Uri): String? = try {
    context.contentResolver.query(document, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
    }
} catch (_: RuntimeException) { null }

/** Child document ids and names, or null when this provider cannot list the directory. */
private fun queryChildren(context: Context, children: Uri): List<Pair<String, String>>? = try {
    context.contentResolver.query(children,
        arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val name = cursor.getString(1)
                if (id != null && name != null) add(id to name)
            }
        }
    }
} catch (_: RuntimeException) { null }

private fun readSidecarText(context: Context, uri: Uri): String = try {
    val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("旁置歌词文件无法打开：$uri")
    stream.use { decodeLyricsBytes(it.readBounded(uri)) }
} catch (error: IOException) {
    throw error
} catch (error: SecurityException) {
    throw IOException("旁置歌词文件读取被拒绝：$uri", error)
} catch (error: RuntimeException) {
    throw IOException("旁置歌词文件读取失败：$uri，${error.message}", error)
}

private fun InputStream.readBounded(uri: Uri): ByteArray {
    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(16 * 1024)
    while (true) {
        val read = read(chunk)
        if (read < 0) break
        buffer.write(chunk, 0, read)
        if (buffer.size() > MAX_SIDECAR_BYTES) throw IOException("旁置歌词文件超过 2 MB：$uri")
    }
    return buffer.toByteArray()
}

private fun escapeLike(value: String): String = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
