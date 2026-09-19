package app.lusound.ncm

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

class NcmException(message: String, cause: Throwable?) : IOException(message, cause)
data class NcmMetadata(val title: String, val artist: String, val album: String, val durationMs: Long, val format: String)
data class NcmHeader(val audioOffset: Long, val coverOffset: Long, val coverLength: Int, val keyStream: ByteArray, val metadata: NcmMetadata)
@Serializable
private data class NcmPayload(val musicName: String, val artist: JsonArray, val album: String, val duration: Long, val format: String)

/** NCM format constants, not account credentials. Protocol reference: https://github.com/taurusxin/ncmdump . */
private const val CORE_KEY = "687a4852416d736f356b496e62617857"
private const val META_KEY = "2331346c6a6b5f215c5d2630553c2728"
private val metadataJson = Json { ignoreUnknownKeys = true }

/** Bounded header parsing. The caller retains ownership of the stream; audio is never materialized here. */
fun readNcmHeader(stream: InputStream, fileLength: Long): NcmHeader {
    val input = DataInputStream(stream)
    val magic = ByteArray(8).also(input::readFully)
    if (!magic.contentEquals("CTENFDAM".toByteArray(Charsets.US_ASCII))) throw NcmException("不是有效的 NCM 文件", null)
    input.readUnsignedShort()
    val keyBlock = readBlock(input, 4096, "密钥")
    val decodedKey = decryptAes(ByteArray(keyBlock.size) { (keyBlock[it].toInt() xor 0x64).toByte() }, CORE_KEY, "密钥")
    val prefix = "neteasecloudmusic".toByteArray(Charsets.US_ASCII)
    if (decodedKey.size <= prefix.size || !decodedKey.copyOfRange(0, prefix.size).contentEquals(prefix)) throw NcmException("NCM 密钥前缀无效或格式不受支持", null)
    val streamKey = decodedKey.copyOfRange(prefix.size, decodedKey.size)
    val metadataBlock = readBlock(input, 1024 * 1024, "元数据")
    val metadata = readMetadata(metadataBlock)
    input.readInt() // Container CRC is not an authenticated integrity check.
    input.readByte() // Cover version.
    val frameLength = readLength(input, 16 * 1024 * 1024, "封面区域")
    val coverLength = readLength(input, frameLength, "封面")
    val coverOffset = 10L + 4 + keyBlock.size + 4 + metadataBlock.size + 5 + 4 + 4
    val audioOffset = coverOffset + frameLength
    if (audioOffset >= fileLength) throw NcmException("NCM 文件截断或没有音频数据", null)
    skipNcmBytes(input, frameLength.toLong())
    val box = IntArray(256) { it }
    var last = 0
    for (index in box.indices) {
        last = (last + box[index] + (streamKey[index % streamKey.size].toInt() and 255)) and 255
        val value = box[index]; box[index] = box[last]; box[last] = value
    }
    val keyStream = ByteArray(256) { index ->
        val j = (index + 1) and 255
        box[(box[j] + box[(box[j] + j) and 255]) and 255].toByte()
    }
    return NcmHeader(audioOffset, coverOffset, coverLength, keyStream, metadata)
}

private fun readLength(input: DataInputStream, maximum: Int, field: String): Int {
    val length = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
    if (length > maximum) throw NcmException("NCM $field 长度 $length 超过支持范围 $maximum", null)
    return length.toInt()
}
private fun readBlock(input: DataInputStream, maximum: Int, field: String): ByteArray = ByteArray(readLength(input, maximum, field)).also(input::readFully)

/**
 * The NCM container stores its key and metadata blocks as AES-ECB with a fixed key. ECB is part of
 * the file format, so it cannot be replaced here; the plaintext is a container key, not user data.
 */
@Suppress("GetInstance")
private fun decryptAes(bytes: ByteArray, key: String, field: String): ByteArray {
    if (bytes.isEmpty() || bytes.size % 16 != 0) throw NcmException("NCM $field 不是完整 AES 数据块", null)
    try {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), "AES"))
        return cipher.doFinal(bytes)
    } catch (error: GeneralSecurityException) { throw NcmException("NCM $field 解密失败：文件损坏或格式不受支持", error) }
}

private fun readMetadata(block: ByteArray): NcmMetadata {
    val text = ByteArray(block.size) { (block[it].toInt() xor 0x63).toByte() }.toString(Charsets.UTF_8)
    val prefix = "163 key(Don't modify):"
    if (!text.startsWith(prefix)) throw NcmException("NCM 元数据前缀缺失或不受支持", null)
    val encoded = try { Base64.getDecoder().decode(text.removePrefix(prefix)) }
        catch (error: IllegalArgumentException) { throw NcmException("NCM 元数据 Base64 无效", error) }
    val decoded = decryptAes(encoded, META_KEY, "元数据").toString(Charsets.UTF_8)
    if (!decoded.startsWith("music:")) throw NcmException("NCM 不是受支持的音乐元数据", null)
    val data = try { metadataJson.decodeFromString<NcmPayload>(decoded.removePrefix("music:")) }
        catch (error: SerializationException) { throw NcmException("NCM 歌曲信息缺少必要字段或格式无效", error) }
    if (data.musicName.isBlank() || data.duration <= 0 || data.format !in setOf("mp3", "flac")) throw NcmException("NCM 歌名、时长或音频格式无效，仅支持 MP3/FLAC", null)
    val artists = data.artist.map { entry ->
        val tuple = entry as? JsonArray ?: throw NcmException("NCM 艺术家信息无效", null)
        val name = tuple.firstOrNull() as? JsonPrimitive ?: throw NcmException("NCM 艺术家名称缺失", null)
        if (!name.isString || name.content.isBlank()) throw NcmException("NCM 艺术家名称无效", null)
        name.content
    }
    return NcmMetadata(data.musicName, artists.joinToString(" / "), data.album, data.duration, data.format)
}

fun skipNcmBytes(input: InputStream, count: Long) {
    var remaining = count
    while (remaining > 0) {
        val skipped = input.skip(remaining)
        if (skipped > 0) remaining -= skipped
        else if (input.read() == -1) throw NcmException("NCM 文件被截断", null)
        else remaining--
    }
}

/** Pure random-access transformation; position is relative to the decrypted compressed audio payload. */
fun decryptNcmBytes(bytes: ByteArray, position: Long, keyStream: ByteArray): ByteArray {
    require(position >= 0 && keyStream.size == 256) { "NCM 解密偏移或密钥流无效" }
    return ByteArray(bytes.size) { index -> (bytes[index].toInt() xor keyStream[((position + index) and 255).toInt()].toInt()).toByte() }
}

/**
 * Whether the decrypted payload really is the format the container's metadata declared.
 *
 * The declaration is only a claim: a damaged or hand-made container can say `flac` and hold something
 * else, and handing that to the decoder produces a confusing failure far from its cause. Only the two
 * formats the reader supports are accepted, and each is recognised by its own marker — `fLaC` for FLAC,
 * an ID3 tag or an MPEG frame sync for MP3. A short signature is rejected rather than indexed into.
 */
fun payloadMatchesDeclaredFormat(format: String, signature: ByteArray): Boolean = when (format) {
    "flac" -> signature.size >= 4 && signature.copyOfRange(0, 4).contentEquals(FLAC_MARKER)
    "mp3" -> signature.size >= 3 && (signature.copyOfRange(0, 3).contentEquals(ID3_MARKER) || isMpegFrameSync(signature))
    else -> false
}

/** The first eleven bits of an MPEG audio frame are set; nothing else starts an MP3 stream. */
private fun isMpegFrameSync(signature: ByteArray): Boolean =
    signature.size >= 2 && (signature[0].toInt() and 0xff) == 0xff && (signature[1].toInt() and 0xe0) == 0xe0

private val FLAC_MARKER = "fLaC".toByteArray(Charsets.US_ASCII)
private val ID3_MARKER = "ID3".toByteArray(Charsets.US_ASCII)
