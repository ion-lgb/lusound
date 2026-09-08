package app.lusound.metadata

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.AtomicFile
import androidx.media3.common.C
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.FlacMetadataReader
import androidx.media3.extractor.metadata.flac.VorbisComment
import app.lusound.library.Track
import java.io.File
import java.io.IOException
import java.security.MessageDigest

data class LocalMetadata(val lyrics: String?, val cover: String?)

/** Media3 parses FLAC metadata blocks; no audio decoder, temporary audio file or tag rewriting. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun readLocalMetadata(context: Context, track: Track): LocalMetadata {
    val uri = Uri.parse(track.uri)
    if (uri.scheme !in setOf("content", "file") || track.format == "ncm") return LocalMetadata(null, track.artworkUri)
    val retriever = MediaMetadataRetriever()
    val picture = try {
        retriever.setDataSource(context, uri)
        retriever.embeddedPicture
    } catch (error: RuntimeException) {
        throw IOException("无法读取本地封面：${track.title}；${error.message}", error)
    } finally { retriever.release() }
    val cover = picture?.let { saveCover(context, track.uri, it) }
    val lyrics = DataSourceInputStream(DefaultDataSource.Factory(context).createDataSource(), DataSpec(uri)).use { stream ->
        val input = DefaultExtractorInput({ buffer, offset, length -> stream.read(buffer, offset, length) }, 0, C.LENGTH_UNSET.toLong())
        if (!FlacMetadataReader.checkAndPeekStreamMarker(input)) return@use null
        input.resetPeekPosition()
        FlacMetadataReader.readStreamMarker(input)
        val holder = FlacMetadataReader.FlacStreamMetadataHolder(null)
        var last = false
        while (!last) {
            val header = ByteArray(4)
            input.peekFully(header, 0, 4)
            input.resetPeekPosition()
            val size = ((header[1].toInt() and 255) shl 16) or ((header[2].toInt() and 255) shl 8) or (header[3].toInt() and 255)
            if (input.position + size > 16 * 1024 * 1024) throw IOException("FLAC 元数据超过 16 MB：${track.title}")
            last = FlacMetadataReader.readMetadataBlock(input, holder)
        }
        val metadata = holder.flacStreamMetadata?.getMetadataCopyWithAppendedEntriesFrom(null)
        (0 until (metadata?.length() ?: 0)).mapNotNull { index ->
            (requireNotNull(metadata)[index] as? VorbisComment)?.takeIf { it.key.uppercase() in setOf("LYRICS", "UNSYNCEDLYRICS", "SYNCEDLYRICS") }?.value
        }.firstNotNullOfOrNull(::usableLyrics)
    }
    return LocalMetadata(lyrics, cover)
}

/** Only validated image bytes are cached; the original audio stays untouched. */
fun saveCover(context: Context, key: String, bytes: ByteArray): String {
    if (bytes.size > 5 * 1024 * 1024) throw IOException("封面超过 5 MB 限制")
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("封面响应不是可解码的图片")
    val directory = File(context.filesDir, "metadata-covers")
    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建封面缓存目录")
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(key.toByteArray())
    val name = digest.digest(bytes).joinToString("") { "%02x".format(it) }
    val file = File(directory, "$name.img")
    val atomic = AtomicFile(file)
    val output = atomic.startWrite()
    try { output.write(bytes); atomic.finishWrite(output) }
    catch (error: IOException) { atomic.failWrite(output); throw error }
    return Uri.fromFile(file).toString()
}

/** Providers without a revision field are re-read locally; remote matches still retain their cache lifetime. */
fun sourceRevision(context: Context, track: Track): String {
    val uri = Uri.parse(track.uri)
    if (uri.scheme == "file") {
        val file = File(requireNotNull(uri.path))
        if (!file.isFile) throw IOException("本地音频不存在：${track.title}")
        return "${file.length()}:${file.lastModified()}"
    }
    if (uri.scheme != "content") return "remote"
    return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) throw IOException("无法读取音频文件版本：${track.title}")
        val modified = listOf("last_modified", "date_modified").map(cursor::getColumnIndex).firstOrNull { it >= 0 }
        val size = cursor.getColumnIndex("_size")
        if (modified == null || cursor.isNull(modified)) "unversioned"
        else "${cursor.getLong(modified)}:${if (size >= 0 && !cursor.isNull(size)) cursor.getLong(size) else -1}"
    } ?: throw IOException("文件提供程序未返回音频版本：${track.title}")
}
