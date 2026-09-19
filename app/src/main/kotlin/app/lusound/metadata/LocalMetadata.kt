package app.lusound.metadata

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.AtomicFile
import androidx.core.net.toUri
import app.lusound.library.Track
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * What can be read from the audio file and the directory around it.
 *
 * [lyricsSource] and [lyricsUrl] describe where [lyrics] came from, because the priority between the
 * local sources and a cached remote result is decided in [MetadataRepository]; a bare string could
 * not tell an embedded tag from a sidecar file.
 */
data class LocalMetadata(val lyrics: String?, val lyricsSource: String?, val lyricsUrl: String?, val cover: String?)

/**
 * Reads the cover and the lyrics a local track carries, without any network access.
 *
 * Lyrics priority is enforced here and nowhere else: the audio file's own tags win over a `.lrc`
 * file next to it, and the sidecar is only looked for when the file itself carries no lyrics. A
 * missing sidecar is a normal outcome; a sidecar that was found but cannot be read throws, so it is
 * reported instead of being presented as "no lyrics".
 */
fun readLocalMetadata(context: Context, track: Track): LocalMetadata {
    val uri = track.uri.toUri()
    if (uri.scheme !in setOf("content", "file") || track.container == app.lusound.library.NCM_CONTAINER) {
        return LocalMetadata(null, null, null, track.artworkUri)
    }
    val retriever = MediaMetadataRetriever()
    val picture = try {
        retriever.setDataSource(context, uri)
        retriever.embeddedPicture
    } catch (error: RuntimeException) {
        throw IOException("无法读取本地封面：${track.title}；${error.message}", error)
    } finally { retriever.release() }
    val cover = picture?.let { saveCover(context, track.uri, it) }
    val embedded = readEmbeddedLyrics(context, track, uri)
    if (embedded != null) return LocalMetadata(embedded, MetadataSource.EMBEDDED, null, cover)
    val sidecar = readSidecarLyrics(context, track)
    return LocalMetadata(sidecar?.text, sidecar?.let { MetadataSource.SIDECAR }, sidecar?.uri?.toString(), cover)
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

/**
 * Providers without a revision field are re-read locally; remote matches still retain their cache
 * lifetime.
 *
 * The revision covers the audio file only. A `.lrc` file that appears later therefore cannot change
 * it, which is why [MetadataRepository] re-reads local content whenever the cached row holds no
 * lyrics at all.
 */
fun sourceRevision(context: Context, track: Track): String {
    val uri = track.uri.toUri()
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
