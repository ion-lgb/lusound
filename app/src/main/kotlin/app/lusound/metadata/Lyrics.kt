/* Standard LRC parsing adapted from Convx LyricsUtils (GPL-3.0), see NOTICE. */
package app.lusound.metadata

import android.text.Html

data class LyricLine(val timeMs: Long, val text: String)

/**
 * Android's HTML entity decoding for one lyric line.
 *
 * This is a seam, not an abstraction: the LRC grammar below is plain text handling that should be
 * verifiable without a device, while this single call needs the framework. Unit tests pass an
 * identity decoder and keep [parseLyrics] on the JVM; production always uses this implementation.
 */
internal fun decodeLyricHtml(raw: String): String = Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()

/** Supports repeated timestamps, fractional seconds and LRC offsets; metadata tags are not lyrics. */
fun parseLyrics(text: String): List<LyricLine> = parseLyrics(text, ::decodeLyricHtml)

internal fun parseLyrics(text: String, decode: (String) -> String): List<LyricLine> {
    val timestamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    val offset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    return text.lineSequence().flatMap { line ->
        val times = timestamp.findAll(line).toList()
        val content = decode(line.substringAfterLast(']')).trim()
        times.asSequence().map { match ->
            val fraction = match.groupValues[3].padEnd(3, '0').toLong()
            LyricLine((match.groupValues[1].toLong() * 60000 + match.groupValues[2].toLong() * 1000 + fraction + offset).coerceAtLeast(0), content)
        }
    }.sortedBy { it.timeMs }.toList()
}

fun usableLyrics(text: String?): String? = usableLyrics(text, ::decodeLyricHtml)

internal fun usableLyrics(text: String?, decode: (String) -> String): String? {
    val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val lines = parseLyrics(value, decode)
    val content = if (lines.isNotEmpty()) lines.map { it.text } else value.lines().filterNot { it.startsWith('[') }
    return value.takeIf { content.any { it.isNotBlank() && it !in setOf("暂无歌词", "纯音乐，请欣赏", "No lyrics") } }
}

fun currentLyricIndex(lines: List<LyricLine>, positionMs: Long): Int {
    var left = 0
    var right = lines.size
    while (left < right) {
        val middle = (left + right) / 2
        if (lines[middle].timeMs <= positionMs) left = middle + 1 else right = middle
    }
    return left - 1
}
