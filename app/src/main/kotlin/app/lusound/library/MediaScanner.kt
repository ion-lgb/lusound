package app.lusound.library

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads accessible audio, including recordings; a provider failure never becomes an empty successful scan. */
suspend fun scanMediaStore(context: Context): MediaLibrarySnapshot = withContext(Dispatchers.IO) {
    val volumesBefore = mountedMediaVolumes(context)
    val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val folderColumn = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.RELATIVE_PATH else MediaStore.Audio.Media.DATA
    val projection = if (Build.VERSION.SDK_INT >= 29) arrayOf("_id", "title", "artist", "album", "duration", "album_id", "mime_type", folderColumn, MediaStore.MediaColumns.VOLUME_NAME)
        else arrayOf("_id", "title", "artist", "album", "duration", "album_id", "mime_type", folderColumn)
    val cursor = context.contentResolver.query(collection, projection, null, null, null)
        ?: throw IOException("MediaStore 查询失败：$collection，媒体提供程序未返回结果")
    val tracks = cursor.use {
        buildList {
            while (it.moveToNext()) {
                val volumeCollection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(it.getString(8)) else collection
                val uri = ContentUris.withAppendedId(volumeCollection, it.getLong(0))
                val location = it.getString(7).orEmpty()
                val folder = if (Build.VERSION.SDK_INT >= 29) location.trimEnd('/') else location.substringBeforeLast('/')
                add(Track(uri.toString(), it.getString(1).orEmpty(), it.getString(2).orEmpty(), it.getString(3).orEmpty(),
                    folder, it.getLong(4), ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), it.getLong(5)).toString(),
                    it.getString(6).orEmpty().removePrefix("audio/"), "MEDIASTORE"))
            }
        }
    }
    MediaLibrarySnapshot(tracks, volumesBefore intersect mountedMediaVolumes(context))
}

private fun mountedMediaVolumes(context: Context): Set<String> = if (Build.VERSION.SDK_INT >= 29) {
    MediaStore.getExternalVolumeNames(context)
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
        Track(uri.toString(), reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: name,
            reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
            reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty(), "授权导入", duration, null, extension, "DOCUMENT")
    } finally { reader.release() }
}
