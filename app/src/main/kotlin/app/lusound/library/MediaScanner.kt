package app.lusound.library

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import androidx.core.net.toUri
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads accessible audio, including recordings; a provider failure never becomes an empty successful scan. */
suspend fun scanMediaStore(context: Context): MediaLibrarySnapshot = withContext(Dispatchers.IO) {
    val volumesBefore = mountedMediaVolumes(context)
    val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val folderColumn = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.RELATIVE_PATH else MediaStore.Audio.Media.DATA
    // Quality columns are appended, never inserted: the positional reads below rely on their order.
    // BITRATE exists from API 30. SAMPLERATE and BITS_PER_SAMPLE arrived with the T SDK extension
    // level 15 and then became part of base API 36, so they are requested only where the provider
    // actually has them -- asking an older provider for a column it does not know throws.
    // SAMPLERATE and BITS_PER_SAMPLE arrived with the T SDK extension level 15 and became base API 36
    // in Android 16. They are named as literals, like every other column in this projection, because
    // referencing the API-gated fields makes lint report the constant as inlined even though the guard
    // below is present and correct. Guarding still matters: asking a provider for a column it does not
    // know throws, so they are requested only where they exist.
    val hasAudioDetails = Build.VERSION.SDK_INT >= 36 ||
        (Build.VERSION.SDK_INT >= 33 && android.os.ext.SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= 15)
    val qualityColumns = buildList {
        if (Build.VERSION.SDK_INT >= 30) add(MediaStore.MediaColumns.BITRATE)
        if (hasAudioDetails) {
            add("samplerate")
            add("bits_per_sample")
        }
    }
    // Identity columns: the media index reports the file's own name, size and modification time.
    val identityColumns = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
    val projection = (if (Build.VERSION.SDK_INT >= 29) arrayOf("_id", "title", "artist", "album", "duration", "album_id", "mime_type", folderColumn, MediaStore.MediaColumns.VOLUME_NAME)
        else arrayOf("_id", "title", "artist", "album", "duration", "album_id", "mime_type", folderColumn)) + qualityColumns + identityColumns
    val cursor = context.contentResolver.query(collection, projection, null, null, null)
        ?: throw IOException("MediaStore 查询失败：$collection，媒体提供程序未返回结果")
    val tracks = cursor.use { rows ->
        // Resolved once per query; a missing column reports -1 and stays unknown.
        val bitrateColumn = rows.getColumnIndex(MediaStore.MediaColumns.BITRATE)
        val sampleRateColumn = rows.getColumnIndex("samplerate")
        val bitDepthColumn = rows.getColumnIndex("bits_per_sample")
        val nameColumn = rows.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
        val sizeColumn = rows.getColumnIndex(MediaStore.MediaColumns.SIZE)
        val modifiedColumn = rows.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
        fun reported(column: Int): Int? = if (column >= 0 && !rows.isNull(column)) rows.getInt(column).takeIf { it > 0 } else null
        fun text(column: Int): String? = if (column >= 0 && !rows.isNull(column)) rows.getString(column) else null
        buildList {
            while (rows.moveToNext()) {
                val volumeCollection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(rows.getString(8)) else collection
                val uri = ContentUris.withAppendedId(volumeCollection, rows.getLong(0))
                val location = rows.getString(7).orEmpty()
                val folder = if (Build.VERSION.SDK_INT >= 29) location.trimEnd('/') else location.substringBeforeLast('/')
                add(Track(uri.toString(), rows.getString(1).orEmpty(), rows.getString(2).orEmpty(), rows.getString(3).orEmpty(),
                    folder, rows.getLong(4), ContentUris.withAppendedId("content://media/external/audio/albumart".toUri(), rows.getLong(5)).toString(),
                    audioContainer(rows.getString(6).orEmpty().removePrefix("audio/")), TrackSource.MEDIASTORE, null,
                    // MediaStore reports bits per second; the database holds kilobits.
                    bitrateKbps(if (bitrateColumn >= 0 && !rows.isNull(bitrateColumn)) rows.getLong(bitrateColumn) else null),
                    reported(sampleRateColumn), reported(bitDepthColumn),
                    // DATE_MODIFIED is in seconds; other providers report milliseconds, and the
                    // identity compares whole seconds so the same file matches from either provider.
                    identityKey = fileIdentityKey(
                        displayName = text(nameColumn).orEmpty(),
                        folder = folder,
                        sizeBytes = if (sizeColumn >= 0 && !rows.isNull(sizeColumn)) rows.getLong(sizeColumn) else 0L,
                        lastModifiedMs = (if (modifiedColumn >= 0 && !rows.isNull(modifiedColumn)) rows.getLong(modifiedColumn) else 0L) * 1000,
                    )))
            }
        }
    }
    MediaLibrarySnapshot(tracks, volumesBefore intersect mountedMediaVolumes(context))
}

private fun mountedMediaVolumes(context: Context): Set<String> = if (Build.VERSION.SDK_INT >= 29) {    MediaStore.getExternalVolumeNames(context)
} else {
    when (Environment.getExternalStorageState()) {
        Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY -> setOf("external")
        else -> emptySet()
    }
}

/** Imports a persistently granted document by URI; audio bytes are never copied. */
suspend fun readAudioDocument(context: Context, uri: Uri): Track = withContext(Dispatchers.IO) {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (!it.moveToFirst()) throw IOException("无法读取导入文件名称：$uri")
        it.getString(0)
    } ?: throw IOException("文件提供程序无法读取：$uri")
    val extension = name.substringAfterLast('.', "").lowercase()
    if (extension == "ncm") return@withContext app.lusound.ncm.readNcmTrack(context, uri)
    if (isEncryptedAudio(extension)) {
        throw UnsupportedOperationException("首期尚不支持 $extension 加密文件：$name；文件未解密、未复制")
    }
    val reader = MediaMetadataRetriever()
    try {
        try { reader.setDataSource(context, uri) }
        catch (error: SecurityException) { throw error }
        catch (error: RuntimeException) { throw IOException("无法读取音频元数据：$name，URI=$uri，原因=${error.message}", error) }
        val duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            ?: throw IOException("文件不是可读取的音频：$name，URI=$uri")
        // The retriever is already open for the tags, so quality costs no extra file access here.
        // METADATA_KEY_BITRATE has existed since API 14; METADATA_KEY_SAMPLERATE only since API 31,
        // so on older releases the sample rate simply stays unknown. Neither exposes a bit depth.
        val bitrate = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
        val sampleRate = if (Build.VERSION.SDK_INT >= 31) reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() else null
        val container = audioContainer(extension)
        // FLAC states its own sample rate and bit depth in its header, which is the only bit-depth
        // source that exists on every supported API level, so it wins over the retriever's figures.
        // This one extra read is per imported file, never per scanned library.
        val flac = if (container == FLAC_CONTAINER) flacStreamInfo(context, uri) else null
        // Identity, so this import can be recognised as the same file the media index already lists.
        // The stored folder stays the display placeholder; the identity uses the directory the
        // document id implies, which is the form the media index reports for the same file.
        val version = documentVersion(context, uri)
        Track(uri.toString(), reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: name,
            reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
            reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty(), "授权导入", duration, null,
            container, TrackSource.DOCUMENT, null, bitrateKbps(bitrate), flac?.sampleRateHz ?: sampleRate, flac?.bitDepth,
            identityKey = version?.let { (size, modified) -> fileIdentityKey(name, documentFolder(uri).orEmpty(), size, modified) })
    } finally { reader.release() }
}

/**
 * The size and modification time a document provider reports, or null when it reports neither.
 *
 * A provider that does not know these columns must not fail the import, so a rejected query simply
 * means the row gets no identity and therefore takes part in no grouping.
 */
private fun documentVersion(context: Context, uri: Uri): Pair<Long, Long>? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) {
            null
        } else {
            val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
            val modifiedColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val size = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) cursor.getLong(sizeColumn) else 0L
            val modified = if (modifiedColumn >= 0 && !cursor.isNull(modifiedColumn)) cursor.getLong(modifiedColumn) else 0L
            size to modified
        }
    }
} catch (_: RuntimeException) {
    null
}

/**
 * The directory a document id implies, for providers whose ids are path-shaped (the document and
 * external-storage providers are). Null when the id says nothing about a directory, in which case the
 * row is simply never grouped.
 */
private fun documentFolder(uri: Uri): String? = try {
    DocumentsContract.getDocumentId(uri)
        .substringBeforeLast('/', "")
        .substringAfter(':', "")
        .trim('/')
        .takeIf { it.isNotEmpty() }
} catch (_: RuntimeException) {
    null
}
