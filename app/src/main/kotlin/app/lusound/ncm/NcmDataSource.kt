package app.lusound.ncm

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

fun ncmPlaybackUri(uri: String): Uri = Uri.Builder().scheme("ncm").authority("audio").appendQueryParameter("uri", uri).build()

/** Media3 receives decrypted compressed bytes, then performs the actual MP3/FLAC-to-PCM decoding. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NcmDataSource(private val context: Context) : BaseDataSource(false) {
    private var file: NcmFile? = null
    private var openedUri: Uri? = null
    private var position: Long = 0
    private var remaining: Long = 0

    override fun open(dataSpec: DataSpec): Long {
        check(file == null) { "NCM 数据源尚未关闭" }
        transferInitializing(dataSpec)
        val original = dataSpec.uri.getQueryParameter("uri") ?: throw NcmException("NCM 播放 URI 缺少原文件位置", null)
        val opened = openNcmFile(context, Uri.parse(original))
        var complete = false
        try {
            val audioLength = opened.length - opened.header.audioOffset
            if (dataSpec.position > audioLength) throw NcmException("NCM 请求偏移 ${dataSpec.position} 超过音频长度 $audioLength", null)
            opened.input.channel.position(opened.startOffset + opened.header.audioOffset + dataSpec.position)
            remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) audioLength - dataSpec.position else minOf(dataSpec.length, audioLength - dataSpec.position)
            position = dataSpec.position
            file = opened
            openedUri = dataSpec.uri
            complete = true
            transferStarted(dataSpec)
            return remaining
        } finally { if (!complete) opened.close() }
    }

    /** The DataReader contract requires writing into the supplied output buffer. */
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val source = checkNotNull(file) { "NCM 数据源未打开" }
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = source.input.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (count < 0) throw NcmException("NCM 音频数据提前结束，文件可能已变更或被截断", null)
        for (index in 0 until count) {
            buffer[offset + index] = (buffer[offset + index].toInt() xor source.header.keyStream[((position + index) and 255).toInt()].toInt()).toByte()
        }
        position += count
        remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = openedUri
    override fun close() {
        val source = file
        file = null
        openedUri = null
        remaining = 0
        if (source != null) try { source.close() } finally { transferEnded() }
    }
}

/** Routes only explicit local NCM playback URIs; ordinary local/cloud sources retain their existing stack. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NcmRoutingDataSource(private val context: Context, private val upstream: DataSource.Factory) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var active: DataSource? = null
    override fun addTransferListener(transferListener: TransferListener) { listeners.add(transferListener); active?.addTransferListener(transferListener) }
    override fun open(dataSpec: DataSpec): Long {
        check(active == null) { "播放数据源尚未关闭" }
        val source = if (dataSpec.uri.scheme == "ncm") NcmDataSource(context) else upstream.createDataSource()
        active = source
        listeners.forEach(source::addTransferListener)
        return source.open(dataSpec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = checkNotNull(active).read(buffer, offset, length)
    override fun getUri(): Uri? = active?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()
    override fun close() { val source = active; active = null; source?.close() }
}
