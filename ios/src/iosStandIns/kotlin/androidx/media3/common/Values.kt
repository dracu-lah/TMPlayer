package androidx.media3.common

import android.net.Uri

// Media3's value types on iOS, as Android's player reads and builds them. Java's public final
// fields are Kotlin vals here, which reads the same.

/** A set of int flags, Media3's. */
class FlagSet private constructor(private val flags: List<Int>) {
    fun contains(flag: Int): Boolean = flag in flags

    fun containsAny(vararg flags: Int): Boolean = flags.any { it in this.flags }

    fun size(): Int = flags.size

    fun get(index: Int): Int = flags[index]

    override fun equals(other: Any?): Boolean = other is FlagSet && other.flags.toSet() == flags.toSet()

    override fun hashCode(): Int = flags.toSet().hashCode()

    class Builder {
        private val flags = LinkedHashSet<Int>()

        fun add(flag: Int): Builder = apply { flags.add(flag) }

        fun addIf(flag: Int, condition: Boolean): Builder = apply { if (condition) flags.add(flag) }

        fun addAll(vararg flags: Int): Builder = apply { flags.forEach { this.flags.add(it) } }

        fun addAll(flags: FlagSet): Builder = apply { this.flags.addAll(flags.flags) }

        fun remove(flag: Int): Builder = apply { flags.remove(flag) }

        fun removeAll(vararg flags: Int): Builder = apply { flags.forEach { this.flags.remove(it) } }

        fun build(): FlagSet = FlagSet(flags.toList())
    }
}

/** A track's format, as Media3 describes one; built by the engine bridge from what an engine reports. */
class Format private constructor(builder: Builder) {
    val id: String? = builder.id
    val label: String? = builder.label
    val language: String? = builder.language
    val selectionFlags: Int = builder.selectionFlags
    val roleFlags: Int = builder.roleFlags
    val averageBitrate: Int = builder.averageBitrate
    val peakBitrate: Int = builder.peakBitrate
    val bitrate: Int = if (builder.peakBitrate != NO_VALUE) builder.peakBitrate else builder.averageBitrate
    val codecs: String? = builder.codecs
    val containerMimeType: String? = builder.containerMimeType
    val sampleMimeType: String? = builder.sampleMimeType
    val maxInputSize: Int = builder.maxInputSize
    val initializationData: List<ByteArray> = builder.initializationData
    val width: Int = builder.width
    val height: Int = builder.height
    val frameRate: Float = builder.frameRate
    val rotationDegrees: Int = builder.rotationDegrees
    val pixelWidthHeightRatio: Float = builder.pixelWidthHeightRatio
    val colorInfo: ColorInfo? = builder.colorInfo
    val channelCount: Int = builder.channelCount
    val sampleRate: Int = builder.sampleRate
    val pcmEncoding: Int = builder.pcmEncoding
    val cueReplacementBehavior: Int = builder.cueReplacementBehavior

    fun buildUpon(): Builder = Builder().also {
        it.id = id
        it.label = label
        it.language = language
        it.selectionFlags = selectionFlags
        it.roleFlags = roleFlags
        it.averageBitrate = averageBitrate
        it.peakBitrate = peakBitrate
        it.codecs = codecs
        it.containerMimeType = containerMimeType
        it.sampleMimeType = sampleMimeType
        it.maxInputSize = maxInputSize
        it.initializationData = initializationData
        it.width = width
        it.height = height
        it.frameRate = frameRate
        it.rotationDegrees = rotationDegrees
        it.pixelWidthHeightRatio = pixelWidthHeightRatio
        it.channelCount = channelCount
        it.sampleRate = sampleRate
        it.pcmEncoding = pcmEncoding
        it.cueReplacementBehavior = cueReplacementBehavior
    }

    override fun toString(): String = "Format($id, $label, $sampleMimeType, $codecs, $language, ${width}x$height, $channelCount ch)"

    class Builder {
        internal var id: String? = null
        internal var label: String? = null
        internal var language: String? = null
        internal var selectionFlags = 0
        internal var roleFlags = 0
        internal var averageBitrate = NO_VALUE
        internal var peakBitrate = NO_VALUE
        internal var codecs: String? = null
        internal var containerMimeType: String? = null
        internal var sampleMimeType: String? = null
        internal var maxInputSize = NO_VALUE
        internal var initializationData: List<ByteArray> = emptyList()
        internal var width = NO_VALUE
        internal var height = NO_VALUE
        internal var frameRate = NO_VALUE.toFloat()
        internal var rotationDegrees = 0
        internal var pixelWidthHeightRatio = 1f
        internal var channelCount = NO_VALUE
        internal var sampleRate = NO_VALUE
        internal var pcmEncoding = NO_VALUE
        internal var cueReplacementBehavior = CUE_REPLACEMENT_BEHAVIOR_MERGE

        fun setId(id: String?): Builder = apply { this.id = id }

        fun setId(id: Int): Builder = apply { this.id = id.toString() }

        fun setLabel(label: String?): Builder = apply { this.label = label }

        fun setLanguage(language: String?): Builder = apply { this.language = language }

        fun setSelectionFlags(selectionFlags: Int): Builder = apply { this.selectionFlags = selectionFlags }

        fun setRoleFlags(roleFlags: Int): Builder = apply { this.roleFlags = roleFlags }

        fun setAverageBitrate(averageBitrate: Int): Builder = apply { this.averageBitrate = averageBitrate }

        fun setPeakBitrate(peakBitrate: Int): Builder = apply { this.peakBitrate = peakBitrate }

        fun setCodecs(codecs: String?): Builder = apply { this.codecs = codecs }

        fun setContainerMimeType(containerMimeType: String?): Builder = apply { this.containerMimeType = containerMimeType }

        fun setSampleMimeType(sampleMimeType: String?): Builder = apply { this.sampleMimeType = sampleMimeType }

        fun setMaxInputSize(maxInputSize: Int): Builder = apply { this.maxInputSize = maxInputSize }

        fun setInitializationData(initializationData: List<ByteArray>?): Builder = apply { this.initializationData = initializationData ?: emptyList() }

        fun setWidth(width: Int): Builder = apply { this.width = width }

        fun setHeight(height: Int): Builder = apply { this.height = height }

        fun setFrameRate(frameRate: Float): Builder = apply { this.frameRate = frameRate }

        fun setRotationDegrees(rotationDegrees: Int): Builder = apply { this.rotationDegrees = rotationDegrees }

        fun setPixelWidthHeightRatio(ratio: Float): Builder = apply { this.pixelWidthHeightRatio = ratio }

        internal var colorInfo: ColorInfo? = null

        fun setColorInfo(colorInfo: ColorInfo?): Builder = apply { this.colorInfo = colorInfo }

        fun setChannelCount(channelCount: Int): Builder = apply { this.channelCount = channelCount }

        fun setSampleRate(sampleRate: Int): Builder = apply { this.sampleRate = sampleRate }

        fun setPcmEncoding(pcmEncoding: Int): Builder = apply { this.pcmEncoding = pcmEncoding }

        fun setCueReplacementBehavior(behavior: Int): Builder = apply { this.cueReplacementBehavior = behavior }

        fun build(): Format = Format(this)
    }

    companion object {
        const val NO_VALUE = -1
        const val OFFSET_SAMPLE_RELATIVE = Long.MAX_VALUE
        const val CUE_REPLACEMENT_BEHAVIOR_MERGE = 1
        const val CUE_REPLACEMENT_BEHAVIOR_REPLACE = 2
    }
}

/** What to play: a location and what is known of it, Media3's. */
class MediaItem private constructor(
    val mediaId: String,
    val localConfiguration: LocalConfiguration?,
    val mediaMetadata: MediaMetadata,
) {
    fun buildUpon(): Builder = Builder().also {
        it.mediaId = mediaId
        it.uri = localConfiguration?.uri
        it.mimeType = localConfiguration?.mimeType
        it.tag = localConfiguration?.tag
        it.customCacheKey = localConfiguration?.customCacheKey
        it.subtitleConfigurations = localConfiguration?.subtitleConfigurations.orEmpty()
        it.mediaMetadata = mediaMetadata
    }

    override fun equals(other: Any?): Boolean =
        other is MediaItem && other.mediaId == mediaId && other.localConfiguration?.uri == localConfiguration?.uri

    override fun hashCode(): Int = 31 * mediaId.hashCode() + (localConfiguration?.uri?.hashCode() ?: 0)

    class LocalConfiguration internal constructor(
        val uri: Uri,
        val mimeType: String?,
        val customCacheKey: String?,
        val tag: Any?,
        val subtitleConfigurations: List<SubtitleConfiguration> = emptyList(),
    )

    /** A side-loaded subtitle file played with the video, Media3's. */
    class SubtitleConfiguration private constructor(
        val uri: Uri,
        val mimeType: String?,
        val language: String?,
        val selectionFlags: Int,
        val roleFlags: Int,
        val label: String?,
        val id: String?,
    ) {
        fun buildUpon(): Builder = Builder(uri).setMimeType(mimeType).setLanguage(language)
            .setSelectionFlags(selectionFlags).setRoleFlags(roleFlags).setLabel(label).setId(id)

        override fun equals(other: Any?): Boolean =
            other is SubtitleConfiguration && other.uri == uri && other.mimeType == mimeType && other.language == language &&
                other.selectionFlags == selectionFlags && other.roleFlags == roleFlags && other.label == label && other.id == id

        override fun hashCode(): Int = listOf(uri, mimeType, language, selectionFlags, roleFlags, label, id).hashCode()

        class Builder(private var uri: Uri) {
            private var mimeType: String? = null
            private var language: String? = null
            private var selectionFlags = 0
            private var roleFlags = 0
            private var label: String? = null
            private var id: String? = null

            fun setUri(uri: Uri): Builder = apply { this.uri = uri }

            fun setMimeType(mimeType: String?): Builder = apply { this.mimeType = mimeType }

            fun setLanguage(language: String?): Builder = apply { this.language = language }

            fun setSelectionFlags(selectionFlags: Int): Builder = apply { this.selectionFlags = selectionFlags }

            fun setRoleFlags(roleFlags: Int): Builder = apply { this.roleFlags = roleFlags }

            fun setLabel(label: String?): Builder = apply { this.label = label }

            fun setId(id: String?): Builder = apply { this.id = id }

            fun build(): SubtitleConfiguration = SubtitleConfiguration(uri, mimeType, language, selectionFlags, roleFlags, label, id)
        }
    }

    class Builder {
        internal var mediaId: String = DEFAULT_MEDIA_ID
        internal var uri: Uri? = null
        internal var mimeType: String? = null
        internal var customCacheKey: String? = null
        internal var tag: Any? = null
        internal var subtitleConfigurations: List<SubtitleConfiguration> = emptyList()
        internal var mediaMetadata: MediaMetadata = MediaMetadata.EMPTY

        fun setSubtitleConfigurations(subtitleConfigurations: List<SubtitleConfiguration>): Builder =
            apply { this.subtitleConfigurations = subtitleConfigurations.toList() }

        fun setMediaId(mediaId: String): Builder = apply { this.mediaId = mediaId }

        fun setUri(uri: String?): Builder = apply { this.uri = uri?.let { Uri.parse(it) } }

        fun setUri(uri: Uri?): Builder = apply { this.uri = uri }

        fun setMimeType(mimeType: String?): Builder = apply { this.mimeType = mimeType }

        fun setCustomCacheKey(customCacheKey: String?): Builder = apply { this.customCacheKey = customCacheKey }

        fun setTag(tag: Any?): Builder = apply { this.tag = tag }

        fun setMediaMetadata(mediaMetadata: MediaMetadata): Builder = apply { this.mediaMetadata = mediaMetadata }

        fun build(): MediaItem =
            MediaItem(mediaId, uri?.let { LocalConfiguration(it, mimeType, customCacheKey, tag, subtitleConfigurations) }, mediaMetadata)
    }

    companion object {
        const val DEFAULT_MEDIA_ID = ""
        val EMPTY: MediaItem = Builder().build()

        fun fromUri(uri: String): MediaItem = Builder().setUri(uri).build()

        fun fromUri(uri: Uri): MediaItem = Builder().setUri(uri).build()
    }
}

/** What a media item is called and shows, Media3's. */
class MediaMetadata private constructor(
    val title: CharSequence?,
    val artist: CharSequence?,
    val albumTitle: CharSequence?,
    val displayTitle: CharSequence?,
    val subtitle: CharSequence?,
    val description: CharSequence?,
    val artworkUri: Uri?,
    val artworkData: ByteArray?,
    val durationMs: Long?,
) {
    fun buildUpon(): Builder = Builder()
        .setTitle(title).setArtist(artist).setAlbumTitle(albumTitle).setDisplayTitle(displayTitle)
        .setSubtitle(subtitle).setDescription(description).setArtworkUri(artworkUri).setDurationMs(durationMs)
        .also { it.artworkData = artworkData }

    class Builder {
        private var title: CharSequence? = null
        private var artist: CharSequence? = null
        private var albumTitle: CharSequence? = null
        private var displayTitle: CharSequence? = null
        private var subtitle: CharSequence? = null
        private var description: CharSequence? = null
        private var artworkUri: Uri? = null
        internal var artworkData: ByteArray? = null
        private var durationMs: Long? = null

        fun setTitle(title: CharSequence?): Builder = apply { this.title = title }

        fun setArtist(artist: CharSequence?): Builder = apply { this.artist = artist }

        fun setAlbumTitle(albumTitle: CharSequence?): Builder = apply { this.albumTitle = albumTitle }

        fun setDisplayTitle(displayTitle: CharSequence?): Builder = apply { this.displayTitle = displayTitle }

        fun setSubtitle(subtitle: CharSequence?): Builder = apply { this.subtitle = subtitle }

        fun setDescription(description: CharSequence?): Builder = apply { this.description = description }

        fun setArtworkUri(artworkUri: Uri?): Builder = apply { this.artworkUri = artworkUri }

        fun setArtworkData(artworkData: ByteArray?, artworkDataType: Int?): Builder = apply { this.artworkData = artworkData }

        fun setDurationMs(durationMs: Long?): Builder = apply { this.durationMs = durationMs }

        private var mediaType: Int? = null

        fun setMediaType(mediaType: Int?): Builder = apply { this.mediaType = mediaType }

        fun build(): MediaMetadata = MediaMetadata(title, artist, albumTitle, displayTitle, subtitle, description, artworkUri, artworkData, durationMs)
    }

    companion object {
        val EMPTY: MediaMetadata = Builder().build()
        const val MEDIA_TYPE_MIXED = 0
        const val MEDIA_TYPE_MUSIC = 1
        const val MEDIA_TYPE_MOVIE = 15
        const val MEDIA_TYPE_VIDEO = 16
        const val MEDIA_TYPE_TV_SHOW = 17
        const val PICTURE_TYPE_OTHER = 0
        const val PICTURE_TYPE_FRONT_COVER = 3
    }
}

/** A group of tracks of one type, Media3's. */
class TrackGroup(val id: String, vararg formats: Format) {
    constructor(vararg formats: Format) : this("", *formats)

    private val formats: List<Format> = formats.toList()
    val length: Int = formats.size
    val type: Int = formats.firstOrNull()?.let { MimeTypes.getTrackType(it.sampleMimeType) } ?: C.TRACK_TYPE_UNKNOWN

    fun getFormat(index: Int): Format = formats[index]

    fun indexOf(format: Format): Int = formats.indexOf(format)

    fun copyWithId(id: String): TrackGroup = TrackGroup(id, *formats.toTypedArray())

    override fun equals(other: Any?): Boolean = other is TrackGroup && other.id == id && other.formats == formats

    override fun hashCode(): Int = 31 * id.hashCode() + formats.hashCode()
}

/** The tracks of the playing item, Media3's. */
class Tracks(val groups: List<Group>) {
    fun isEmpty(): Boolean = groups.isEmpty()

    fun containsType(trackType: Int): Boolean = groups.any { it.type == trackType }

    fun isTypeSupported(trackType: Int): Boolean = isTypeSupported(trackType, false)

    fun isTypeSupported(trackType: Int, allowExceedsCapabilities: Boolean): Boolean =
        groups.any { it.type == trackType && it.isSupported(allowExceedsCapabilities) }

    fun isTypeSelected(trackType: Int): Boolean = groups.any { it.type == trackType && it.isSelected() }

    override fun equals(other: Any?): Boolean = other is Tracks && other.groups == groups

    override fun hashCode(): Int = groups.hashCode()

    class Group(
        val mediaTrackGroup: TrackGroup,
        private val adaptiveSupported: Boolean,
        trackSupport: IntArray,
        trackSelected: BooleanArray,
    ) {
        private val support = trackSupport.copyOf()
        private val selected = trackSelected.copyOf()
        val length: Int = mediaTrackGroup.length
        val type: Int = mediaTrackGroup.type

        fun getMediaTrackGroup(): TrackGroup = mediaTrackGroup

        fun getTrackFormat(trackIndex: Int): Format = mediaTrackGroup.getFormat(trackIndex)

        fun getTrackSupport(trackIndex: Int): Int = support[trackIndex]

        fun isTrackSupported(trackIndex: Int): Boolean = isTrackSupported(trackIndex, false)

        fun isTrackSupported(trackIndex: Int, allowExceedsCapabilities: Boolean): Boolean =
            support[trackIndex] == C.FORMAT_HANDLED || (allowExceedsCapabilities && support[trackIndex] == C.FORMAT_EXCEEDS_CAPABILITIES)

        fun isSupported(): Boolean = isSupported(false)

        fun isSupported(allowExceedsCapabilities: Boolean): Boolean = support.indices.any { isTrackSupported(it, allowExceedsCapabilities) }

        fun isSelected(): Boolean = selected.any { it }

        fun isTrackSelected(trackIndex: Int): Boolean = selected[trackIndex]

        fun isAdaptiveSupported(): Boolean = adaptiveSupported

        override fun equals(other: Any?): Boolean =
            other is Group && other.mediaTrackGroup == mediaTrackGroup && other.support.contentEquals(support) && other.selected.contentEquals(selected)

        override fun hashCode(): Int = 31 * mediaTrackGroup.hashCode() + selected.contentHashCode()
    }

    companion object {
        val EMPTY: Tracks = Tracks(emptyList())
    }
}

/** A choice of tracks within one group, Media3's. */
class TrackSelectionOverride(val mediaTrackGroup: TrackGroup, val trackIndices: List<Int>) {
    constructor(mediaTrackGroup: TrackGroup, trackIndex: Int) : this(mediaTrackGroup, listOf(trackIndex))

    fun getType(): Int = mediaTrackGroup.type

    override fun equals(other: Any?): Boolean =
        other is TrackSelectionOverride && other.mediaTrackGroup == mediaTrackGroup && other.trackIndices == trackIndices

    override fun hashCode(): Int = 31 * mediaTrackGroup.hashCode() + trackIndices.hashCode()
}

/** What tracks the player picks, Media3's; the engine bridge carries out the choices that matter. */
open class TrackSelectionParameters protected constructor(builder: Builder) {
    val preferredAudioLanguages: List<String> = builder.preferredAudioLanguages
    val preferredTextLanguages: List<String> = builder.preferredTextLanguages
    val preferredAudioRoleFlags: Int = builder.preferredAudioRoleFlags
    val preferredTextRoleFlags: Int = builder.preferredTextRoleFlags
    val selectUndeterminedTextLanguage: Boolean = builder.selectUndeterminedTextLanguage
    val ignoredTextSelectionFlags: Int = builder.ignoredTextSelectionFlags
    val disabledTrackTypes: Set<Int> = builder.disabledTrackTypes.toSet()
    val overrides: Map<TrackGroup, TrackSelectionOverride> = builder.overrides.toMap()
    val maxVideoWidth: Int = builder.maxVideoWidth
    val maxVideoHeight: Int = builder.maxVideoHeight
    val maxVideoBitrate: Int = builder.maxVideoBitrate
    val forceLowestBitrate: Boolean = builder.forceLowestBitrate
    val forceHighestSupportedBitrate: Boolean = builder.forceHighestSupportedBitrate

    open fun buildUpon(): Builder = Builder(this)

    override fun equals(other: Any?): Boolean =
        other is TrackSelectionParameters && other.preferredAudioLanguages == preferredAudioLanguages &&
            other.preferredTextLanguages == preferredTextLanguages && other.disabledTrackTypes == disabledTrackTypes &&
            other.overrides == overrides && other.selectUndeterminedTextLanguage == selectUndeterminedTextLanguage &&
            other.ignoredTextSelectionFlags == ignoredTextSelectionFlags

    override fun hashCode(): Int = listOf(preferredAudioLanguages, preferredTextLanguages, disabledTrackTypes, overrides).hashCode()

    open class Builder {
        internal var preferredAudioLanguages: List<String> = emptyList()
        internal var preferredTextLanguages: List<String> = emptyList()
        internal var preferredAudioRoleFlags = 0
        internal var preferredTextRoleFlags = 0
        internal var selectUndeterminedTextLanguage = false
        internal var ignoredTextSelectionFlags = 0
        internal val disabledTrackTypes = LinkedHashSet<Int>()
        internal val overrides = LinkedHashMap<TrackGroup, TrackSelectionOverride>()
        internal var maxVideoWidth = Int.MAX_VALUE
        internal var maxVideoHeight = Int.MAX_VALUE
        internal var maxVideoBitrate = Int.MAX_VALUE
        internal var forceLowestBitrate = false
        internal var forceHighestSupportedBitrate = false

        constructor()

        @Suppress("UNUSED_PARAMETER")
        constructor(context: android.content.Context) : this()

        internal constructor(from: TrackSelectionParameters) : this() {
            preferredAudioLanguages = from.preferredAudioLanguages
            preferredTextLanguages = from.preferredTextLanguages
            preferredAudioRoleFlags = from.preferredAudioRoleFlags
            preferredTextRoleFlags = from.preferredTextRoleFlags
            selectUndeterminedTextLanguage = from.selectUndeterminedTextLanguage
            ignoredTextSelectionFlags = from.ignoredTextSelectionFlags
            disabledTrackTypes.addAll(from.disabledTrackTypes)
            overrides.putAll(from.overrides)
            maxVideoWidth = from.maxVideoWidth
            maxVideoHeight = from.maxVideoHeight
            maxVideoBitrate = from.maxVideoBitrate
            forceLowestBitrate = from.forceLowestBitrate
            forceHighestSupportedBitrate = from.forceHighestSupportedBitrate
        }

        open fun setPreferredAudioLanguage(language: String?): Builder = apply { preferredAudioLanguages = listOfNotNull(language) }

        open fun setPreferredAudioLanguages(vararg languages: String): Builder = apply { preferredAudioLanguages = languages.toList() }

        open fun setPreferredTextLanguage(language: String?): Builder = apply { preferredTextLanguages = listOfNotNull(language) }

        open fun setPreferredTextLanguages(vararg languages: String): Builder = apply { preferredTextLanguages = languages.toList() }

        open fun setPreferredAudioRoleFlags(flags: Int): Builder = apply { preferredAudioRoleFlags = flags }

        open fun setPreferredTextRoleFlags(flags: Int): Builder = apply { preferredTextRoleFlags = flags }

        open fun setSelectUndeterminedTextLanguage(select: Boolean): Builder = apply { selectUndeterminedTextLanguage = select }

        open fun setIgnoredTextSelectionFlags(flags: Int): Builder = apply { ignoredTextSelectionFlags = flags }

        open fun setTrackTypeDisabled(trackType: Int, disabled: Boolean): Builder = apply {
            if (disabled) disabledTrackTypes.add(trackType) else disabledTrackTypes.remove(trackType)
        }

        open fun setOverrideForType(override: TrackSelectionOverride): Builder = apply {
            clearOverridesOfType(override.getType())
            overrides[override.mediaTrackGroup] = override
        }

        open fun addOverride(override: TrackSelectionOverride): Builder = apply { overrides[override.mediaTrackGroup] = override }

        open fun clearOverride(mediaTrackGroup: TrackGroup): Builder = apply { overrides.remove(mediaTrackGroup) }

        open fun clearOverridesOfType(trackType: Int): Builder = apply { overrides.entries.removeAll { it.value.getType() == trackType } }

        open fun clearOverrides(): Builder = apply { overrides.clear() }

        open fun setMaxVideoSize(maxVideoWidth: Int, maxVideoHeight: Int): Builder = apply {
            this.maxVideoWidth = maxVideoWidth
            this.maxVideoHeight = maxVideoHeight
        }

        open fun clearVideoSizeConstraints(): Builder = setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)

        open fun setMaxVideoBitrate(maxVideoBitrate: Int): Builder = apply { this.maxVideoBitrate = maxVideoBitrate }

        open fun setForceLowestBitrate(force: Boolean): Builder = apply { forceLowestBitrate = force }

        open fun setForceHighestSupportedBitrate(force: Boolean): Builder = apply { forceHighestSupportedBitrate = force }

        open fun build(): TrackSelectionParameters = TrackSelectionParameters(this)
    }

    companion object {
        val DEFAULT: TrackSelectionParameters = Builder().build()

        @Suppress("UNUSED_PARAMETER")
        fun getDefaults(context: android.content.Context): TrackSelectionParameters = DEFAULT
    }
}

/** Why playback failed, Media3's. */
open class PlaybackException(
    message: String?,
    cause: Throwable?,
    val errorCode: Int,
) : Exception(message, cause) {
    val timestampMs: Long = android.os.SystemClock.elapsedRealtime()

    fun getErrorCodeName(): String = getErrorCodeName(errorCode)

    companion object {
        const val ERROR_CODE_UNSPECIFIED = 1000
        const val ERROR_CODE_REMOTE_ERROR = 1001
        const val ERROR_CODE_BEHIND_LIVE_WINDOW = 1002
        const val ERROR_CODE_TIMEOUT = 1003
        const val ERROR_CODE_FAILED_RUNTIME_CHECK = 1004
        const val ERROR_CODE_IO_UNSPECIFIED = 2000
        const val ERROR_CODE_IO_NETWORK_CONNECTION_FAILED = 2001
        const val ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT = 2002
        const val ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE = 2003
        const val ERROR_CODE_IO_BAD_HTTP_STATUS = 2004
        const val ERROR_CODE_IO_FILE_NOT_FOUND = 2005
        const val ERROR_CODE_IO_NO_PERMISSION = 2006
        const val ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED = 2007
        const val ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE = 2008
        const val ERROR_CODE_PARSING_CONTAINER_MALFORMED = 3001
        const val ERROR_CODE_PARSING_MANIFEST_MALFORMED = 3002
        const val ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED = 3003
        const val ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED = 3004
        const val ERROR_CODE_DECODER_INIT_FAILED = 4001
        const val ERROR_CODE_DECODER_QUERY_FAILED = 4002
        const val ERROR_CODE_DECODING_FAILED = 4003
        const val ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES = 4004
        const val ERROR_CODE_DECODING_FORMAT_UNSUPPORTED = 4005
        const val ERROR_CODE_DECODING_RESOURCES_RECLAIMED = 4006
        const val ERROR_CODE_AUDIO_TRACK_INIT_FAILED = 5001
        const val ERROR_CODE_AUDIO_TRACK_WRITE_FAILED = 5002
        const val ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED = 5003
        const val ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED = 5004
        const val ERROR_CODE_DRM_UNSPECIFIED = 6000
        const val ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED = 7000
        const val ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED = 7001

        fun getErrorCodeName(errorCode: Int): String = when (errorCode) {
            ERROR_CODE_UNSPECIFIED -> "ERROR_CODE_UNSPECIFIED"
            ERROR_CODE_TIMEOUT -> "ERROR_CODE_TIMEOUT"
            ERROR_CODE_IO_UNSPECIFIED -> "ERROR_CODE_IO_UNSPECIFIED"
            ERROR_CODE_IO_FILE_NOT_FOUND -> "ERROR_CODE_IO_FILE_NOT_FOUND"
            ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> "ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE"
            ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "ERROR_CODE_PARSING_CONTAINER_MALFORMED"
            ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED"
            ERROR_CODE_DECODER_INIT_FAILED -> "ERROR_CODE_DECODER_INIT_FAILED"
            ERROR_CODE_DECODING_FAILED -> "ERROR_CODE_DECODING_FAILED"
            ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "ERROR_CODE_DECODING_FORMAT_UNSUPPORTED"
            ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES"
            ERROR_CODE_AUDIO_TRACK_INIT_FAILED -> "ERROR_CODE_AUDIO_TRACK_INIT_FAILED"
            else -> "invalid error code"
        }
    }
}

/** Speed and pitch, Media3's. */
class PlaybackParameters(val speed: Float, val pitch: Float) {
    constructor(speed: Float) : this(speed, 1f)

    fun withSpeed(speed: Float): PlaybackParameters = PlaybackParameters(speed, pitch)

    fun getMediaTimeUsForPlayoutTimeMs(timeMs: Long): Long = (timeMs * 1000 * speed).toLong()

    override fun equals(other: Any?): Boolean = other is PlaybackParameters && other.speed == speed && other.pitch == pitch

    override fun hashCode(): Int = 31 * speed.hashCode() + pitch.hashCode()

    companion object {
        val DEFAULT: PlaybackParameters = PlaybackParameters(1f)
    }
}

/** The picture's size, Media3's. */
class VideoSize(val width: Int, val height: Int, val pixelWidthHeightRatio: Float) {
    constructor(width: Int, height: Int) : this(width, height, 1f)

    val unappliedRotationDegrees: Int = 0

    override fun equals(other: Any?): Boolean =
        other is VideoSize && other.width == width && other.height == height && other.pixelWidthHeightRatio == pixelWidthHeightRatio

    override fun hashCode(): Int = (31 * width + height) * 31 + pixelWidthHeightRatio.hashCode()

    companion object {
        val UNKNOWN: VideoSize = VideoSize(0, 0)
    }
}

/** The playlist's structure; on iOS one item at a time, so empty or a single window. */
abstract class Timeline {
    abstract fun getWindowCount(): Int

    fun isEmpty(): Boolean = getWindowCount() == 0

    companion object {
        val EMPTY: Timeline = object : Timeline() {
            override fun getWindowCount(): Int = 0
        }
    }
}

/** How the sound is used, Media3's; the audio session belongs to the iOS host. */
class AudioAttributes private constructor(val usage: Int, val contentType: Int) {
    class Builder {
        private var usage = C.USAGE_MEDIA
        private var contentType = C.AUDIO_CONTENT_TYPE_UNKNOWN

        fun setUsage(usage: Int): Builder = apply { this.usage = usage }

        fun setContentType(contentType: Int): Builder = apply { this.contentType = contentType }

        fun build(): AudioAttributes = AudioAttributes(usage, contentType)
    }

    companion object {
        val DEFAULT: AudioAttributes = Builder().build()
    }
}

/** Media3's reader of bytes, which DataSource extends. */
interface DataReader {
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
}

/** A video's colour: space, range, transfer and bit depths, Media3's. */
class ColorInfo private constructor(
    val colorSpace: Int,
    val colorRange: Int,
    val colorTransfer: Int,
    val hdrStaticInfo: ByteArray?,
    val lumaBitdepth: Int,
    val chromaBitdepth: Int,
) {
    fun isValid(): Boolean = colorSpace != Format.NO_VALUE || colorRange != Format.NO_VALUE || colorTransfer != Format.NO_VALUE

    class Builder {
        private var colorSpace = Format.NO_VALUE
        private var colorRange = Format.NO_VALUE
        private var colorTransfer = Format.NO_VALUE
        private var hdrStaticInfo: ByteArray? = null
        private var lumaBitdepth = Format.NO_VALUE
        private var chromaBitdepth = Format.NO_VALUE

        fun setColorSpace(value: Int): Builder = apply { colorSpace = value }

        fun setColorRange(value: Int): Builder = apply { colorRange = value }

        fun setColorTransfer(value: Int): Builder = apply { colorTransfer = value }

        fun setHdrStaticInfo(value: ByteArray?): Builder = apply { hdrStaticInfo = value }

        fun setLumaBitdepth(value: Int): Builder = apply { lumaBitdepth = value }

        fun setChromaBitdepth(value: Int): Builder = apply { chromaBitdepth = value }

        fun build(): ColorInfo = ColorInfo(colorSpace, colorRange, colorTransfer, hdrStaticInfo, lumaBitdepth, chromaBitdepth)
    }

    companion object {
        val SDR_BT709_LIMITED: ColorInfo = Builder().setColorSpace(C.COLOR_SPACE_BT709).setColorRange(C.COLOR_RANGE_LIMITED)
            .setColorTransfer(C.COLOR_TRANSFER_SDR).build()

        fun isTransferHdr(colorInfo: ColorInfo?): Boolean =
            colorInfo != null && (colorInfo.colorTransfer == C.COLOR_TRANSFER_ST2084 || colorInfo.colorTransfer == C.COLOR_TRANSFER_HLG)
    }
}
