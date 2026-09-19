package app.lusound.library

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.FlacMetadataReader
import java.io.IOException

/** What a FLAC stream header states about itself. */
data class FlacStreamInfo(val sampleRateHz: Int, val bitDepth: Int)

/** The metadata region of a file is small; anything larger is treated as not-a-FLAC rather than read. */
private const val MAX_METADATA_BLOCK_BYTES = 1024 * 1024

/**
 * Reads the stream information block of a FLAC stream, or null when the input is not FLAC or carries
 * no usable STREAMINFO.
 *
 * This is the only quality figure available on every supported API level: MediaStore only exposes
 * `samplerate` and `bits_per_sample` from the T SDK extension onwards, and `MediaMetadataRetriever`
 * never reports a bit depth. FLAC states both in its own header, so the answer comes from the file
 * rather than from a guess.
 *
 * Media3 parses the block; only the STREAMINFO type check is done here, because a file that does not
 * start with STREAMINFO is malformed whatever else it contains.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun readFlacStreamInfo(input: ExtractorInput): FlacStreamInfo? = try {
    if (!FlacMetadataReader.checkAndPeekStreamMarker(input)) return null
    input.resetPeekPosition()
    FlacMetadataReader.readStreamMarker(input)
    val header = ByteArray(4)
    input.peekFully(header, 0, 4)
    input.resetPeekPosition()
    val blockType = header[0].toInt() and 0x7f
    val blockSize = ((header[1].toInt() and 255) shl 16) or ((header[2].toInt() and 255) shl 8) or (header[3].toInt() and 255)
    if (blockType != 0 || blockSize !in 34..MAX_METADATA_BLOCK_BYTES) return null
    val holder = FlacMetadataReader.FlacStreamMetadataHolder(null)
    FlacMetadataReader.readMetadataBlock(input, holder)
    val metadata = holder.flacStreamMetadata ?: return null
    if (metadata.sampleRate <= 0 || metadata.bitsPerSample <= 0) return null
    FlacStreamInfo(metadata.sampleRate, metadata.bitsPerSample)
} catch (_: IOException) {
    // A truncated or unreadable header is simply "no stream information", not a failed import.
    null
}

/**
 * The same reading for an opened document.
 *
 * A failure to read is not a failure to import: quality is a detail, so anything unreadable simply
 * stays unknown rather than aborting the scan.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun flacStreamInfo(context: Context, uri: Uri): FlacStreamInfo? = try {
    DataSourceInputStream(DefaultDataSource.Factory(context).createDataSource(), DataSpec(uri)).use { stream ->
        val input = DefaultExtractorInput({ buffer, offset, length -> stream.read(buffer, offset, length) }, 0, C.LENGTH_UNSET.toLong())
        readFlacStreamInfo(input)
    }
} catch (_: IOException) {
    null
} catch (_: RuntimeException) {
    // A provider that rejects the read is reported by the caller's own metadata read; quality is not
    // worth failing the import for, so it degrades to unknown.
    null
}
