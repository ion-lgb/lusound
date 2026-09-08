/* Standard LRC parsing adapted from Convx LyricsUtils (GPL-3.0), see NOTICE. */
package app.lusound.metadata

import android.text.Html

data class LyricLine(val timeMs: Long, val text: String)

/** Supports repeated timestamps, fractional seconds and LRC offsets; metadata tags are not lyrics. */
fun parseLyrics(text: String): List<LyricLine> {
    val timestamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    val offset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    return text.lineSequence().flatMap { line ->
        val times = timestamp.findAll(line).toList()
        val content = Html.fromHtml(line.substringAfterLast(']'), Html.FROM_HTML_MODE_LEGACY).toString().trim()
        times.asSequence().map { match ->
            val fraction = match.groupValues[3].padEnd(3, '0').toLong()
            LyricLine((match.groupValues[1].toLong() * 60000 + match.groupValues[2].toLong() * 1000 + fraction + offset).coerceAtLeast(0), content)
        }
    }.sortedBy { it.timeMs }.toList()
}

fun usableLyrics(text: String?): String? {
    val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val lines = parseLyrics(value)
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
