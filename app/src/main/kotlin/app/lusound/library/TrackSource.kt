package app.lusound.library

/**
 * How a track entered the library.
 *
 * These strings are persisted in `tracks.sourceKind`; renaming a constant requires a migration.
 * They replace the previous single `origin` string, which encoded the source kind, an authorised
 * tree URI and a server id in one value that callers had to parse by prefix.
 */
object TrackSource {
    /** Discovered by the system media provider on a mounted volume. */
    const val MEDIASTORE = "MEDIASTORE"

    /** Imported as a single document through the system file picker. */
    const val DOCUMENT = "DOCUMENT"

    /** Imported by scanning an authorised directory tree; see [Track.sourceRef]. */
    const val DOCUMENT_TREE = "DOCUMENT_TREE"

    /** Provided by a configured server; see [Track.sourceRef]. */
    const val CLOUD = "CLOUD"
}

/** The encrypted NetEase container, which is the one container that needs its own playback route. */
const val NCM_CONTAINER = "ncm"

/** The one container whose own header states a bit depth; see [flacStreamInfo]. */
const val FLAC_CONTAINER = "flac"

/**
 * The three import paths report the audio container in three different vocabularies: MediaStore
 * reports MIME subtypes (`mpeg`, `mp4`, `x-wav`), document imports report the file extension, and
 * servers report their own container name (`mp3`, `flac`). Storing the raw value meant the same song
 * displayed as `MPEG`, `MP3` or `mp3` depending on how it arrived, so every path is normalised here
 * before it reaches the database.
 *
 * Unrecognised values are returned lowercased and trimmed rather than discarded: an unknown
 * container is still more useful for display than an empty one, and guessing a codec from the file
 * name is exactly what this function must not do.
 */
fun audioContainer(value: String): String = when (val raw = value.trim().lowercase()) {
    "mpeg", "mp3" -> "mp3"
    "mp4", "m4a", "x-m4a" -> "m4a"
    "aac", "x-aac" -> "aac"
    "flac", "x-flac" -> "flac"
    "wav", "x-wav", "wave", "vnd.wave" -> "wav"
    "ogg", "x-ogg" -> "ogg"
    "opus" -> "opus"
    "aiff", "x-aiff", "aif" -> "aiff"
    "wma", "x-ms-wma" -> "wma"
    "amr" -> "amr"
    else -> raw
}

/** Container shown in a track row; an unreported container must read as unknown, not as a guess. */
fun containerLabel(container: String): String = container.uppercase().ifBlank { "未知格式" }

/**
 * Where a track plays from, shown in a track row.
 *
 * Derived from the stored source fields only. The previous implementation sniffed the URI
 * (`startsWith("lusound://")`), which was a proxy for "came from a server" rather than a statement
 * of it, and returned `本地` for anything else even when the file was encrypted or offline-unavailable.
 */
fun sourceLabel(track: Track): String = when {
    track.sourceKind == TrackSource.CLOUD -> "在线"
    track.container == NCM_CONTAINER -> "来源：网易云"
    else -> "本地"
}
