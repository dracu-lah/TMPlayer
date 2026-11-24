package androidx.media3.exoplayer.trackselection

// ExoPlayer's track selector on iOS: the choices it would make are the player's
// TrackSelectionParameters, which the engine bridge carries out (EngineExoPlayer).

/** Media3's track selector. */
abstract class TrackSelector

/**
 * Media3's default track selector. On iOS it holds the player's track preferences, which
 * ExoPlayer.Builder hands the engine as its TrackSelectionParameters (EngineExoPlayer turns them
 * into the engine's track choices); with no renderers on iOS there is nothing to map.
 */
open class DefaultTrackSelector(@Suppress("UNUSED_PARAMETER") context: android.content.Context?) : TrackSelector() {
    /** Media3's selector parameters: the track preferences, and renderers turned off by index. */
    class Parameters internal constructor(builder: Builder) : androidx.media3.common.TrackSelectionParameters(builder) {
        private val disabled: Set<Int> = builder.disabledRenderers.toSet()

        fun getRendererDisabled(rendererIndex: Int): Boolean = rendererIndex in disabled

        override fun buildUpon(): Builder = Builder(this)

        /** Media3's builder, each setter answering with this Builder, as Media3's do. */
        class Builder : androidx.media3.common.TrackSelectionParameters.Builder {
            internal val disabledRenderers = LinkedHashSet<Int>()

            constructor() : super()

            constructor(context: android.content.Context) : super()

            internal constructor(from: Parameters) : super(from) {
                disabledRenderers.addAll(from.disabled)
            }

            fun setRendererDisabled(rendererIndex: Int, disabled: Boolean): Builder = apply {
                if (disabled) disabledRenderers.add(rendererIndex) else disabledRenderers.remove(rendererIndex)
            }

            /** Tunnelled playback hands the video and sound to the device together; the engine decides that on iOS, so this is kept and not used. */
            @Suppress("UNUSED_PARAMETER")
            fun setTunnelingEnabled(enabled: Boolean): Builder = this

            override fun setTrackTypeDisabled(trackType: Int, disabled: Boolean): Builder = apply { super.setTrackTypeDisabled(trackType, disabled) }

            override fun setIgnoredTextSelectionFlags(flags: Int): Builder = apply { super.setIgnoredTextSelectionFlags(flags) }

            override fun setPreferredTextLanguage(language: String?): Builder = apply { super.setPreferredTextLanguage(language) }

            override fun setSelectUndeterminedTextLanguage(select: Boolean): Builder = apply { super.setSelectUndeterminedTextLanguage(select) }

            override fun setPreferredAudioLanguage(language: String?): Builder = apply { super.setPreferredAudioLanguage(language) }

            // The ordered lists of Settings (WP-80), first match winning, kept as this Builder so the chain goes on.
            override fun setPreferredTextLanguages(vararg languages: String): Builder = apply { super.setPreferredTextLanguages(*languages) }

            override fun setPreferredAudioLanguages(vararg languages: String): Builder = apply { super.setPreferredAudioLanguages(*languages) }

            override fun build(): Parameters = Parameters(this)
        }
    }

    /** Which renderer plays which track groups, Media3's. */
    abstract class MappedTrackInfo {
        abstract fun getRendererCount(): Int

        abstract fun getRendererType(rendererIndex: Int): Int

        abstract fun getTrackGroups(rendererIndex: Int): androidx.media3.exoplayer.source.TrackGroupArray

        abstract fun getTrackSupport(rendererIndex: Int, groupIndex: Int, trackIndex: Int): Int

        val rendererCount: Int get() = getRendererCount()
    }

    var parameters: Parameters = Parameters.Builder().build()

    /** None on iOS: no renderers to map tracks to. */
    val currentMappedTrackInfo: MappedTrackInfo? get() = null

    fun buildUponParameters(): Parameters.Builder = parameters.buildUpon()

    fun setParameters(parameters: Parameters) {
        this.parameters = parameters
    }

    fun setParameters(builder: Parameters.Builder) = setParameters(builder.build())
}

/** A track selection of one renderer, Media3's; none is made on iOS (the engine selects), the type is for the load control's callback. */
interface ExoTrackSelection
