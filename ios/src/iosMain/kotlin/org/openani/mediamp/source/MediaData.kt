package org.openani.mediamp.source

// mediamp's media source, the type upstream's PlaybackEngine opens. The desktop builds it from a
// TDLib file; on iOS an engine opens a location itself, so the stand in is that location.

/** What an engine opens: a path, a URL, or TMPlayer's `tdfile://<file id>` stream. */
interface MediaData {
    val uri: String
}

/** A [MediaData] by its location. */
class UriMediaData(override val uri: String) : MediaData
