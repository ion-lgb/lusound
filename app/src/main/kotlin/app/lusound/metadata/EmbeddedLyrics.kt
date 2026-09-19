/* Embedded lyrics readers. The containers (FLAC blocks, ID3 frames, MP4 boxes) are parsed by
 * AndroidX Media3's own extractor code; only the two payload formats Media3 does not decode
 * (an ID3 USLT body) and the file-level box walk are implemented here. */
// Media3 marks its extractor and container APIs as unstable, and this file exists to use them.
// The opt-in is file-wide because every reader here works through them, including the private
// helpers that only touch box and frame types. Media3's marker is a plain Java annotation, so the
// opt-in lint recognises is androidx.annotation.OptIn, not Kotlin's OptIn.
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.lusound.metadata

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Metadata
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.container.Mp4Box
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.FlacMetadataReader
import androidx.media3.extractor.Id3Peeker
import androidx.media3.extractor.metadata.flac.VorbisComment
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.Id3Decoder
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.mp4.BoxParser
import app.lusound.library.Track
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * The tag families this app reads lyrics from.
 *
 * The kind is decided by sniffing the first bytes of the file rather than by trusting
 * `tracks.container`: the stored container comes from a MIME type, an extension or a server name,
 * and a wrong one must not decide whether a file's own lyrics are read. Each reader then validates
 * its own marker again, so a misdetection degrades to "no embedded lyrics".
 */
internal enum class LyricsContainer { FLAC, ID3, MP4 }

/** Bytes read once per local file to choose a container; the longest marker checked is 8 bytes. */
internal const val LYRICS_PROBE_BYTES = 16

/**
 * Ceiling for every embedded metadata read, matching the FLAC reader's existing limit: a corrupt
 * length field must not be able to allocate an unbounded buffer. Exceeding it is an error, because
 * the file claimed to hold lyrics that could not be read.
 */
private const val MAX_TAG_BYTES = 16 * 1024 * 1024

private val FLAC_LYRICS_KEYS = setOf("LYRICS", "UNSYNCEDLYRICS", "SYNCEDLYRICS")

/** `TXXX` descriptions that taggers use for unsynchronised lyrics when no USLT frame is written. */
private val FREEFORM_LYRICS_KEYS = setOf("LYRICS", "UNSYNCEDLYRICS", "SYNCEDLYRICS", "SYNCHRONIZEDLYRICS")

/** Box types that legitimately open an MP4/M4A file; anything else is never walked as MP4. */
private val MP4_OPENING_BOXES = listOf("ftyp", "moov", "mdat", "free", "skip", "wide", "styp").map(::boxType).toSet()

private fun boxType(name: String): Int = name.fold(0) { value, character -> (value shl 8) or character.code }

/** Returns the tag family of one file, or null when it carries no tags this app reads. */
internal fun sniffLyricsContainer(probe: ByteArray): LyricsContainer? = when {
    probe.matches(0, "fLaC") -> LyricsContainer.FLAC
    probe.matches(0, "ID3") -> LyricsContainer.ID3
    probe.size >= 8 && readInt(probe, 4) in MP4_OPENING_BOXES -> LyricsContainer.MP4
    else -> null
}

/** Reads the lyrics a local file carries in its own tags; null when it carries none. */
internal fun readEmbeddedLyrics(context: Context, track: Track, uri: Uri): String? =
    when (sniffLyricsContainer(readLyricsProbe(context, uri))) {
        LyricsContainer.FLAC -> withExtractorInput(context, uri) { readFlacLyrics(it, track.title) }
        LyricsContainer.ID3 -> withExtractorInput(context, uri) { readId3Lyrics(it, track.title) }
        LyricsContainer.MP4 -> readMp4Lyrics(context, uri, track.title)
        null -> null
    }

/**
 * FLAC VorbisComment lyrics, the reader this app already relied on.
 *
 * Media3 parses the metadata blocks; no audio decoder, temporary file or tag rewriting is involved.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun readFlacLyrics(input: ExtractorInput, title: String, decode: (String) -> String = ::decodeLyricHtml): String? {
    if (!FlacMetadataReader.checkAndPeekStreamMarker(input)) return null
    input.resetPeekPosition()
    FlacMetadataReader.readStreamMarker(input)
    val holder = FlacMetadataReader.FlacStreamMetadataHolder(null)
    var last = false
    while (!last) {
        val header = ByteArray(4)
        input.peekFully(header, 0, 4)
        input.resetPeekPosition()
        val size = ((header[1].toInt() and 255) shl 16) or ((header[2].toInt() and 255) shl 8) or (header[3].toInt() and 255)
        if (input.position + size > MAX_TAG_BYTES) throw IOException("FLAC 元数据超过 16 MB：$title")
        last = FlacMetadataReader.readMetadataBlock(input, holder)
    }
    val metadata = holder.flacStreamMetadata?.getMetadataCopyWithAppendedEntriesFrom(null)
    return (0 until (metadata?.length() ?: 0)).mapNotNull { index ->
        (metadata?.get(index) as? VorbisComment)?.takeIf { it.key.uppercase() in FLAC_LYRICS_KEYS }?.value
    }.firstNotNullOfOrNull { usableLyrics(it, decode) }
}

/**
 * ID3v2 lyrics: `USLT` (ID3v2.3/2.4) and `ULT` (ID3v2.2) unsynchronised lyrics, with a `TXXX`
 * frame whose description names lyrics as a documented last resort, because foobar2000, MusicBee and
 * other taggers write the same text there.
 *
 * Media3 splits the tag into frames, including v2.4 synchsafe frame sizes and per-frame
 * unsynchronisation; it deliberately exposes `USLT` as an opaque frame, so only that body is decoded
 * (see [decodeUsltPayload]). `SYLT` (synchronised lyrics) is intentionally not converted: its
 * timestamps are in an unsynchronised binary form whose correctness cannot be verified here.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun readId3Lyrics(input: ExtractorInput, title: String, decode: (String) -> String = ::decodeLyricHtml): String? {
    // Id3Peeker allocates the whole declared tag, so the length is checked before handing it over.
    val header = ByteArray(Id3Decoder.ID3_HEADER_LENGTH)
    try { input.peekFully(header, 0, header.size) } catch (_: EOFException) { return null }
    input.resetPeekPosition()
    if (!header.matches(0, "ID3")) return null
    if (ParsableByteArray(header).apply { skipBytes(6) }.readSynchSafeInt() > MAX_TAG_BYTES) {
        throw IOException("ID3 标签超过 16 MB：$title")
    }
    val metadata = Id3Peeker().peekId3Data(input, lyricsFramePredicate()) ?: return null
    return (unitedLyricsFrames(metadata) + freeformLyricsFrames(metadata)).firstNotNullOfOrNull { usableLyrics(it, decode) }
}

/**
 * M4A/MP4 lyrics: the `©lyr` item of `moov/udta/meta/ilst`, plus the `----:com.apple.iTunes:LYRICS`
 * freeform item some taggers write instead.
 *
 * [udta] must be the complete `udta` box including its header, which is what [readMp4Udta] returns
 * and what Media3's `BoxParser.parseUdta` expects.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun mp4Lyrics(udta: ByteArray, decode: (String) -> String = ::decodeLyricHtml): String? {
    val metadata = BoxParser.parseUdta(Mp4Box.LeafBox(Mp4Box.TYPE_udta, ParsableByteArray(udta)))
    val candidates = (0 until metadata.length()).mapNotNull { index ->
        when (val entry = metadata[index]) {
            is TextInformationFrame -> entry.takeIf { it.id == "USLT" }?.values?.firstOrNull()
            is InternalFrame -> entry.takeIf { it.description.equals("LYRICS", ignoreCase = true) }?.text
            else -> null
        }
    }
    return candidates.firstNotNullOfOrNull { usableLyrics(it, decode) }
}

/**
 * Decodes the body of an ID3 `USLT`/`ULT` frame: an encoding byte, a three byte language, a
 * terminated content descriptor and then the lyrics themselves.
 *
 * The terminator rule mirrors Media3's own ID3 text handling: single byte encodings terminate on one
 * zero byte, UTF-16 on a zero byte at an even offset from the descriptor start followed by a second
 * one.
 */
internal fun decodeUsltPayload(frame: ByteArray): String? {
    if (frame.size < 4) return null
    val encoding = frame[0].toInt() and 0xFF
    val textStart = textTerminator(frame, 4, encoding) + id3DelimiterLength(encoding)
    if (textStart >= frame.size) return null
    return String(frame, textStart, frame.size - textStart, id3Charset(encoding)).trim('\u0000').removePrefix("\uFEFF")
}

/**
 * Random access to the bytes of one local file.
 *
 * Media3's `DataSource` can be reopened at any offset, so a metadata box is read where it sits
 * instead of reading through megabytes of audio to reach it. [length] is the file's size and [at]
 * returns null when the file does not hold that many bytes, which is how a malformed box size is
 * rejected without ever asking the provider for data beyond the end.
 */
internal interface FileBytes {
    val length: Long

    /** The [count] bytes at [position], or null when the file ends first. */
    fun at(position: Long, count: Int): ByteArray?
}

/**
 * Walks the top level boxes until `moov` is found and then its children until `udta` is found,
 * returning that box with its header, because `BoxParser.parseUdta` starts reading after the header.
 *
 * A structure that cannot be walked means "no lyrics"; only a `udta` box beyond the metadata
 * ceiling is reported as an error.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun readMp4Udta(bytes: FileBytes, title: String): ByteArray? {
    var position = 0L
    while (position + Mp4Box.HEADER_SIZE <= bytes.length) {
        val box = readBox(bytes, position, bytes.length) ?: return null
        if (box.type == Mp4Box.TYPE_moov) return findUserData(bytes, box, title)
        position = box.contentStart + box.contentSize
    }
    return null
}

private fun findUserData(bytes: FileBytes, moov: Box, title: String): ByteArray? {
    val end = moov.contentStart + moov.contentSize
    var position = moov.contentStart
    while (position + Mp4Box.HEADER_SIZE <= end) {
        val box = readBox(bytes, position, end) ?: return null
        if (box.type == Mp4Box.TYPE_udta) {
            if (box.size > MAX_TAG_BYTES) throw IOException("MP4 元数据超过 16 MB：$title")
            return bytes.at(box.start, box.size.toInt())
        }
        position = box.contentStart + box.contentSize
    }
    return null
}

private class Box(val type: Int, val size: Long, val headerSize: Int, val start: Long) {
    val contentStart: Long get() = start + headerSize
    val contentSize: Long get() = size - headerSize
}

/** Reads one box header at [position]; null when it is not a well formed box inside [limit]. */
private fun readBox(bytes: FileBytes, position: Long, limit: Long): Box? {
    val header = bytes.at(position, Mp4Box.HEADER_SIZE) ?: return null
    var size = readUnsignedInt(header, 0)
    var headerSize = Mp4Box.HEADER_SIZE
    if (size == 1L) {
        val extended = bytes.at(position + Mp4Box.HEADER_SIZE, 8) ?: return null
        size = readLong(extended, 0)
        headerSize = Mp4Box.LONG_HEADER_SIZE
    }
    // A zero size means the box runs to the end of the file, which the limit already knows.
    val resolved = if (size == 0L) limit - position else size
    if (resolved < headerSize || position + resolved > limit) return null
    return Box(readInt(header, 4), resolved, headerSize, position)
}

/** One small peek decides the container, so a file is opened once per tag family at most. */
private fun readLyricsProbe(context: Context, uri: Uri): ByteArray =
    DataSourceInputStream(DefaultDataSource.Factory(context).createDataSource(), DataSpec(uri)).use { stream ->
        val probe = ByteArray(LYRICS_PROBE_BYTES)
        var read = 0
        while (read < probe.size) {
            val count = stream.read(probe, read, probe.size - read)
            if (count <= 0) break
            read += count
        }
        probe.copyOf(read)
    }

private fun readMp4Lyrics(context: Context, uri: Uri, title: String): String? =
    DataSourceFileBytes(context, uri).use { bytes -> mp4Lyrics(readMp4Udta(bytes, title) ?: return@use null) }

/**
 * [FileBytes] over Media3's data source. Only the offsets the box walk asks for are read, and a jump
 * to a new offset reopens the source there, which is a seek for file and document providers.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class DataSourceFileBytes(context: Context, private val uri: Uri) : FileBytes, Closeable {
    private val dataSource: DataSource = DefaultDataSource.Factory(context).createDataSource()
    private var next = 0L
    override val length: Long = dataSource.open(DataSpec(uri))

    override fun at(position: Long, count: Int): ByteArray? {
        if (count <= 0) return ByteArray(0)
        if (position < 0 || position + count > length) return null
        if (next != position) {
            dataSource.close()
            dataSource.open(DataSpec(uri, position, C.LENGTH_UNSET.toLong()))
            next = position
        }
        val target = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = dataSource.read(target, offset, count - offset)
            if (read < 0) return null
            next += read
            offset += read
        }
        return target
    }

    override fun close() = dataSource.close()
}

/** Media3's readers work on an [ExtractorInput]; this makes one sequential pass over the file. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun <T> withExtractorInput(context: Context, uri: Uri, block: (ExtractorInput) -> T): T =
    DataSourceInputStream(DefaultDataSource.Factory(context).createDataSource(), DataSpec(uri)).use { stream ->
        block(DefaultExtractorInput({ buffer, offset, length -> stream.read(buffer, offset, length) }, 0, C.LENGTH_UNSET.toLong()))
    }

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun lyricsFramePredicate(): Id3Decoder.FramePredicate = Id3Decoder.FramePredicate { majorVersion, id0, id1, id2, id3 ->
    val uslt = id0 == 'U'.code && id1 == 'S'.code && id2 == 'L'.code && id3 == 'T'.code
    val ult = majorVersion == 2 && id0 == 'U'.code && id1 == 'L'.code && id2 == 'T'.code
    val txxx = id0 == 'T'.code && id1 == 'X'.code && id2 == 'X'.code && id3 == 'X'.code
    uslt || ult || txxx
}

private fun unitedLyricsFrames(metadata: Metadata): List<String> = (0 until metadata.length()).mapNotNull { index ->
    val frame = metadata[index] as? BinaryFrame ?: return@mapNotNull null
    if (frame.id == "USLT" || frame.id == "ULT") decodeUsltPayload(frame.data) else null
}

private fun freeformLyricsFrames(metadata: Metadata): List<String> = (0 until metadata.length()).mapNotNull { index ->
    val frame = metadata[index] as? TextInformationFrame ?: return@mapNotNull null
    val description = frame.description?.trim()?.uppercase()
    if (frame.id != "TXXX" || description == null || description !in FREEFORM_LYRICS_KEYS) null else frame.values.firstOrNull()
}

private fun id3Charset(encoding: Int): Charset = when (encoding) {
    1 -> StandardCharsets.UTF_16
    2 -> StandardCharsets.UTF_16BE
    3 -> StandardCharsets.UTF_8
    else -> StandardCharsets.ISO_8859_1
}

private fun id3DelimiterLength(encoding: Int): Int = if (encoding == 0 || encoding == 3) 1 else 2

private fun textTerminator(data: ByteArray, from: Int, encoding: Int): Int {
    var position = data.indexOfZero(from)
    if (encoding == 0 || encoding == 3) return position
    while (position < data.size - 1) {
        if ((position - from) % 2 == 0 && data[position + 1] == 0.toByte()) return position
        position = data.indexOfZero(position + 1)
    }
    return data.size
}

private fun ByteArray.indexOfZero(from: Int): Int {
    for (index in from until size) if (this[index] == 0.toByte()) return index
    return size
}

private fun ByteArray.matches(offset: Int, text: String): Boolean =
    size >= offset + text.length && text.indices.all { this[offset + it] == text[it].code.toByte() }

private fun readUnsignedInt(bytes: ByteArray, offset: Int): Long =
    ((bytes[offset].toLong() and 0xFF) shl 24) or ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 8) or (bytes[offset + 3].toLong() and 0xFF)

private fun readInt(bytes: ByteArray, offset: Int): Int = readUnsignedInt(bytes, offset).toInt()

private fun readLong(bytes: ByteArray, offset: Int): Long =
    (readUnsignedInt(bytes, offset) shl 32) or readUnsignedInt(bytes, offset + 4)
