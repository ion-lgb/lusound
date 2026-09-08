package app.lusound.ncm

import android.content.Context
import android.net.Uri
import app.lusound.library.Track
import java.io.File
import java.security.MessageDigest

/** Only already-visible cover artwork may be cached. Neither compressed audio nor PCM is written. */
fun readNcmTrack(context: Context, uri: Uri): Track = openNcmFile(context, uri).use { file ->
    val header = file.header
    val signature = ByteArray(4)
    java.io.DataInputStream(file.input).readFully(signature)
    val audio = decryptNcmBytes(signature, 0, header.keyStream)
    val valid = when (header.metadata.format) {
        "flac" -> audio.contentEquals("fLaC".toByteArray(Charsets.US_ASCII))
        "mp3" -> audio.copyOfRange(0, 3).contentEquals("ID3".toByteArray(Charsets.US_ASCII)) || ((audio[0].toInt() and 255) == 255 && (audio[1].toInt() and 224) == 224)
        else -> false
    }
    if (!valid) throw NcmException("NCM 解密后的音频标识与声明格式不一致", null)
    val artwork = if (header.coverLength > 0) {
        file.input.channel.position(file.startOffset + header.coverOffset)
        val bytes = ByteArray(header.coverLength)
        java.io.DataInputStream(file.input).readFully(bytes)
        val name = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        val folder = File(context.cacheDir, "ncm-covers")
        if (!folder.isDirectory && !folder.mkdirs()) throw NcmException("无法创建 NCM 封面缓存目录", null)
        val cover = File(folder, "$name.img")
        cover.writeBytes(bytes)
        Uri.fromFile(cover).toString()
    } else null
    Track(uri.toString(), header.metadata.title, header.metadata.artist, header.metadata.album, "授权导入", header.metadata.durationMs, artwork, "ncm", "DOCUMENT")
}
