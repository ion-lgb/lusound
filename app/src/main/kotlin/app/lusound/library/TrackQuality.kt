package app.lusound.library

import java.util.Locale

/** Shown when nothing has reported a quality figure. Never replaced by a guess. */
const val UNKNOWN_QUALITY: String = "音质未知"

/**
 * Bits per second to whole kilobits per second, or null when the source did not report a figure.
 *
 * The three sources disagree on units: MediaStore's `BITRATE` column and the Jellyfin API report bits
 * per second, while Subsonic's `bitRate` and Plex's `bitrate` are already kilobits. Converting once
 * here means the database and the UI only ever hold kilobits. The +500 rounds to the nearest kbps
 * instead of truncating, so 1411200 bps reads as 1411 rather than 1411 vs 1412 depending on the file.
 */
fun bitrateKbps(bitsPerSecond: Long?): Int? = bitsPerSecond?.takeIf { it > 0 }?.let { ((it + 500) / 1000).toInt() }

/** Sample rate as a short label: 44100 → "44.1 kHz", 48000 → "48 kHz", 22050 → "22.05 kHz". */
fun sampleRateLabel(hertz: Int): String {
    // Locale.ROOT so the decimal separator cannot become a comma on a localised device.
    val text = String.format(Locale.ROOT, "%.2f", hertz / 1000.0).trimEnd('0').trimEnd('.')
    return "$text kHz"
}

/**
 * The known parts of a quality reading, joined for display, or null when none of them is known.
 *
 * Non-positive values are treated as unknown because providers use 0 for "not measured".
 */
fun qualityDetail(bitrateKbps: Int?, sampleRateHz: Int?, bitDepth: Int?): String? {
    val parts = buildList {
        bitrateKbps?.takeIf { it > 0 }?.let { add("$it kbps") }
        sampleRateHz?.takeIf { it > 0 }?.let { add(sampleRateLabel(it)) }
        bitDepth?.takeIf { it > 0 }?.let { add("$it bit") }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * Quality to show for a track. Falls back to [UNKNOWN_QUALITY], which is the whole point of the
 * nullable columns: a FLAC is not automatically "16 bit / 44.1 kHz" and a `.mp3` is not automatically
 * 320 kbps, so an unreported figure must read as unknown rather than as a plausible default.
 */
fun qualityLabel(track: Track): String =
    qualityDetail(track.bitrateKbps, track.sampleRateHz, track.bitDepth) ?: UNKNOWN_QUALITY
