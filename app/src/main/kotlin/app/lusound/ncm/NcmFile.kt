package app.lusound.ncm

import android.content.Context
import android.net.Uri
import java.io.Closeable
import java.io.FileInputStream

/** Owns a seekable provider descriptor, including non-zero asset offsets. No temporary audio files. */
class NcmFile(val input: FileInputStream, val startOffset: Long, val length: Long, val header: NcmHeader) : Closeable {
    override fun close() { input.close() }
}

fun openNcmFile(context: Context, uri: Uri): NcmFile {
    require(uri.scheme == "content" || uri.scheme == "file") { "NCM 仅支持本地文件或授权文档" }
    val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r") ?: throw NcmException("无法打开 NCM：$uri", null)
    var input: FileInputStream? = null
    var complete = false
    try {
        // AssetFileDescriptor's bounded stream keeps a separate remaining-byte counter;
        // use the owning raw descriptor stream so channel seeks also work backwards.
        input = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor.parcelFileDescriptor)
        input.channel.position(descriptor.startOffset)
        val length = if (descriptor.length >= 0) descriptor.length else input.channel.size() - descriptor.startOffset
        val header = readNcmHeader(input, length)
        input.channel.position(descriptor.startOffset + header.audioOffset)
        val result = NcmFile(input, descriptor.startOffset, length, header)
        complete = true
        return result
    } finally {
        if (!complete) { if (input != null) input.close() else descriptor.close() }
    }
}
