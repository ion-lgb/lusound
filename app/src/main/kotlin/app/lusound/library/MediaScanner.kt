package app.lusound.library

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
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
    val projection = (if (Build.VERSION.SDK_INT >= 29) arrayOf("_id", "title", "artist", "album", "duration", "album_id", "mime_type", folderColumn, MediaStore.MediaColumns.VOLUME_NAME)
        else arrayOf("_id", "title", "artist", "album", "duration", "album_id", "mime_type", folderColumn)) + qualityColumns
    val cursor = context.contentResolver.query(collection, projection, null, null, null)
        ?: throw IOException("MediaStore 查询失败：$collection，媒体提供程序未返回结果")
    val tracks = cursor.use { rows ->
        // Resolved once per query; a missing column reports -1 and stays unknown.
        val bitrateColumn = rows.getColumnIndex(MediaStore.MediaColumns.BITRATE)
        val sampleRateColumn = rows.getColumnIndex("samplerate")
        val bitDepthColumn = rows.getColumnIndex("bits_per_sample")
        fun reported(column: Int): Int? = if (column >= 0 && !rows.isNull(column)) rows.getInt(column).takeIf { it > 0 } else null
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
                    reported(sampleRateColumn), reported(bitDepthColumn)))
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
        Track(uri.toString(), reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: name,
            reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
            reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty(), "授权导入", duration, null,
            container, TrackSource.DOCUMENT, null, bitrateKbps(bitrate), flac?.sampleRateHz ?: sampleRate, flac?.bitDepth)
    } finally { reader.release() }
}
