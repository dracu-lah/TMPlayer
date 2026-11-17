package androidx.media3.exoplayer.source

// ExoPlayer's media sources on iOS: the engine opens the media item's location itself, so these
// are names Android's player configures, with nothing behind them.

/** Media3's media source. */
interface MediaSource {
    /** Media3's factory of media sources. */
    interface Factory
}

/** A renderer's track groups, Media3's. */
class TrackGroupArray(vararg groups: androidx.media3.common.TrackGroup) {
    private val all = groups.toList()

    val length: Int get() = all.size

    fun get(index: Int): androidx.media3.common.TrackGroup = all[index]

    fun isEmpty(): Boolean = all.isEmpty()

    companion object {
        val EMPTY = TrackGroupArray()
    }
}

/**
 * Media3's media source factory: the data sources and extractors Android's player reads a video
 * with. On iOS the engine opens the video itself (tdfile:// through TDLib's stream), so these are
 * kept, not run.
 */
class DefaultMediaSourceFactory(
    val dataSourceFactory: androidx.media3.datasource.DataSource.Factory,
    val extractorsFactory: androidx.media3.extractor.ExtractorsFactory,
) : MediaSource.Factory {
    constructor(context: android.content.Context) : this(
        androidx.media3.datasource.DataSource.Factory { throw UnsupportedOperationException("The engine reads on iOS") },
        androidx.media3.extractor.DefaultExtractorsFactory(),
    )

    fun setSubtitleParserFactory(subtitleParserFactory: androidx.media3.extractor.text.SubtitleParser.Factory): DefaultMediaSourceFactory = this

    fun experimentalParseSubtitlesDuringExtraction(parse: Boolean): DefaultMediaSourceFactory = this
}
