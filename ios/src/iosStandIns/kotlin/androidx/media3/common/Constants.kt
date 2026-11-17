package androidx.media3.common

import android.net.Uri

// Media3's constants on iOS, with Media3's values, so a value Android's player stores or compares
// means the same here.

object C {
    const val TIME_END_OF_SOURCE = Long.MIN_VALUE
    const val TIME_UNSET = Long.MIN_VALUE + 1
    const val INDEX_UNSET = -1
    const val POSITION_UNSET = -1
    const val RATE_UNSET = -Float.MAX_VALUE
    const val LENGTH_UNSET = -1
    const val PERCENTAGE_UNSET = -1
    const val MICROS_PER_SECOND = 1_000_000L
    const val NANOS_PER_SECOND = 1_000_000_000L
    const val BITS_PER_BYTE = 8
    const val BYTES_PER_FLOAT = 4

    const val RESULT_END_OF_INPUT = -1
    const val RESULT_MAX_LENGTH_EXCEEDED = -2
    const val RESULT_NOTHING_READ = -3
    const val RESULT_BUFFER_READ = -4
    const val RESULT_FORMAT_READ = -5

    const val TRACK_TYPE_NONE = -2
    const val TRACK_TYPE_UNKNOWN = -1
    const val TRACK_TYPE_DEFAULT = 0
    const val TRACK_TYPE_AUDIO = 1
    const val TRACK_TYPE_VIDEO = 2
    const val TRACK_TYPE_TEXT = 3
    const val TRACK_TYPE_IMAGE = 4
    const val TRACK_TYPE_METADATA = 5
    const val TRACK_TYPE_CAMERA_MOTION = 6

    const val SELECTION_FLAG_DEFAULT = 1
    const val SELECTION_FLAG_FORCED = 1 shl 1
    const val SELECTION_FLAG_AUTOSELECT = 1 shl 2

    const val ROLE_FLAG_MAIN = 1
    const val ROLE_FLAG_ALTERNATE = 1 shl 1
    const val ROLE_FLAG_SUPPLEMENTARY = 1 shl 2
    const val ROLE_FLAG_COMMENTARY = 1 shl 3
    const val ROLE_FLAG_DUB = 1 shl 4
    const val ROLE_FLAG_EMERGENCY = 1 shl 5
    const val ROLE_FLAG_CAPTION = 1 shl 6
    const val ROLE_FLAG_SUBTITLE = 1 shl 7
    const val ROLE_FLAG_SIGN = 1 shl 8
    const val ROLE_FLAG_DESCRIBES_VIDEO = 1 shl 9
    const val ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND = 1 shl 10
    const val ROLE_FLAG_ENHANCED_DIALOG_INTELLIGIBILITY = 1 shl 11
    const val ROLE_FLAG_TRANSCRIBES_DIALOG = 1 shl 12
    const val ROLE_FLAG_EASY_TO_READ = 1 shl 13
    const val ROLE_FLAG_TRICK_PLAY = 1 shl 14

    const val FORMAT_HANDLED = 4
    const val FORMAT_EXCEEDS_CAPABILITIES = 3
    const val FORMAT_UNSUPPORTED_DRM = 2
    const val FORMAT_UNSUPPORTED_SUBTYPE = 1
    const val FORMAT_UNSUPPORTED_TYPE = 0

    const val ENCODING_INVALID = 0
    const val ENCODING_PCM_8BIT = 3
    const val ENCODING_PCM_16BIT = 2
    const val ENCODING_PCM_16BIT_BIG_ENDIAN = 0x10000000
    const val ENCODING_PCM_24BIT = 0x20000000
    const val ENCODING_PCM_24BIT_BIG_ENDIAN = 0x50000000
    const val ENCODING_PCM_32BIT = 0x30000000
    const val ENCODING_PCM_32BIT_BIG_ENDIAN = 0x60000000
    const val ENCODING_PCM_FLOAT = 4
    const val ENCODING_MP3 = 9
    const val ENCODING_AAC_LC = 10
    const val ENCODING_AC3 = 5
    const val ENCODING_E_AC3 = 6
    const val ENCODING_E_AC3_JOC = 18
    const val ENCODING_AC4 = 17
    const val ENCODING_DTS = 7
    const val ENCODING_DTS_HD = 8
    const val ENCODING_DTS_UHD_P2 = 30
    const val ENCODING_DOLBY_TRUEHD = 14
    const val ENCODING_OPUS = 20

    const val USAGE_MEDIA = 1
    const val AUDIO_CONTENT_TYPE_UNKNOWN = 0
    const val AUDIO_CONTENT_TYPE_SPEECH = 1
    const val AUDIO_CONTENT_TYPE_MUSIC = 2
    const val AUDIO_CONTENT_TYPE_MOVIE = 3

    const val WAKE_MODE_NONE = 0
    const val WAKE_MODE_LOCAL = 1
    const val WAKE_MODE_NETWORK = 2

    const val VIDEO_SCALING_MODE_SCALE_TO_FIT = 1
    const val VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING = 2
    const val VIDEO_SCALING_MODE_DEFAULT = VIDEO_SCALING_MODE_SCALE_TO_FIT

    const val VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF = Int.MIN_VALUE
    const val VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS = 0

    const val COLOR_SPACE_BT601 = 2
    const val COLOR_SPACE_BT709 = 1
    const val COLOR_SPACE_BT2020 = 6
    const val COLOR_TRANSFER_SDR = 3
    const val COLOR_TRANSFER_ST2084 = 6
    const val COLOR_TRANSFER_HLG = 7
    const val COLOR_RANGE_LIMITED = 2
    const val COLOR_RANGE_FULL = 1

    const val STEREO_MODE_MONO = 0
    const val STEREO_MODE_TOP_BOTTOM = 1
    const val STEREO_MODE_LEFT_RIGHT = 2

    const val DATA_TYPE_UNKNOWN = 0
    const val DATA_TYPE_MEDIA = 1
    const val DATA_TYPE_MEDIA_INITIALIZATION = 2

    const val BUFFER_FLAG_KEY_FRAME = 1
    const val BUFFER_FLAG_END_OF_STREAM = 1 shl 2
    const val BUFFER_FLAG_FIRST_SAMPLE = 1 shl 27
    const val BUFFER_FLAG_LAST_SAMPLE = 1 shl 29
    const val BUFFER_FLAG_ENCRYPTED = 1 shl 30
    const val BUFFER_FLAG_DECODE_ONLY = 1 shl 31

    const val CRYPTO_TYPE_NONE = 0

    const val CONTENT_TYPE_OTHER = 4

    const val LANGUAGE_UNDETERMINED = "und"

    fun usToMs(timeUs: Long): Long = if (timeUs == TIME_UNSET || timeUs == TIME_END_OF_SOURCE) timeUs else timeUs / 1000

    fun msToUs(timeMs: Long): Long = if (timeMs == TIME_UNSET || timeMs == TIME_END_OF_SOURCE) timeMs else timeMs * 1000
}

/** Media3's MIME types, and the track type each names. */
object MimeTypes {
    const val BASE_TYPE_VIDEO = "video"
    const val BASE_TYPE_AUDIO = "audio"
    const val BASE_TYPE_TEXT = "text"
    const val BASE_TYPE_IMAGE = "image"
    const val BASE_TYPE_APPLICATION = "application"

    const val VIDEO_MP4 = "video/mp4"
    const val VIDEO_MATROSKA = "video/x-matroska"
    const val VIDEO_WEBM = "video/webm"
    const val VIDEO_H263 = "video/3gpp"
    const val VIDEO_H264 = "video/avc"
    const val VIDEO_H265 = "video/hevc"
    const val VIDEO_VP8 = "video/x-vnd.on2.vp8"
    const val VIDEO_VP9 = "video/x-vnd.on2.vp9"
    const val VIDEO_AV1 = "video/av01"
    const val VIDEO_MP2T = "video/mp2t"
    const val VIDEO_MP4V = "video/mp4v-es"
    const val VIDEO_MPEG = "video/mpeg"
    const val VIDEO_PS = "video/mp2p"
    const val VIDEO_MPEG2 = "video/mpeg2"
    const val VIDEO_VC1 = "video/wvc1"
    const val VIDEO_DIVX = "video/divx"
    const val VIDEO_FLV = "video/x-flv"
    const val VIDEO_DOLBY_VISION = "video/dolby-vision"
    const val VIDEO_OGG = "video/ogg"
    const val VIDEO_AVI = "video/x-msvideo"
    const val VIDEO_MJPEG = "video/mjpeg"
    const val VIDEO_MP42 = "video/mp42"
    const val VIDEO_MP43 = "video/mp43"
    const val VIDEO_RAW = "video/raw"
    const val VIDEO_UNKNOWN = "video/x-unknown"

    const val AUDIO_MP4 = "audio/mp4"
    const val AUDIO_AAC = "audio/mp4a-latm"
    const val AUDIO_MATROSKA = "audio/x-matroska"
    const val AUDIO_WEBM = "audio/webm"
    const val AUDIO_MPEG = "audio/mpeg"
    const val AUDIO_MPEG_L1 = "audio/mpeg-L1"
    const val AUDIO_MPEG_L2 = "audio/mpeg-L2"
    const val AUDIO_MPEGH_MHA1 = "audio/mha1"
    const val AUDIO_MPEGH_MHM1 = "audio/mhm1"
    const val AUDIO_RAW = "audio/raw"
    const val AUDIO_ALAW = "audio/g711-alaw"
    const val AUDIO_MLAW = "audio/g711-mlaw"
    const val AUDIO_AC3 = "audio/ac3"
    const val AUDIO_E_AC3 = "audio/eac3"
    const val AUDIO_E_AC3_JOC = "audio/eac3-joc"
    const val AUDIO_AC4 = "audio/ac4"
    const val AUDIO_TRUEHD = "audio/true-hd"
    const val AUDIO_DTS = "audio/vnd.dts"
    const val AUDIO_DTS_HD = "audio/vnd.dts.hd"
    const val AUDIO_DTS_EXPRESS = "audio/vnd.dts.hd;profile=lbr"
    const val AUDIO_DTS_X = "audio/vnd.dts.uhd;profile=p2"
    const val AUDIO_VORBIS = "audio/vorbis"
    const val AUDIO_OPUS = "audio/opus"
    const val AUDIO_AMR = "audio/amr"
    const val AUDIO_AMR_NB = "audio/3gpp"
    const val AUDIO_AMR_WB = "audio/amr-wb"
    const val AUDIO_FLAC = "audio/flac"
    const val AUDIO_ALAC = "audio/alac"
    const val AUDIO_MSGSM = "audio/gsm"
    const val AUDIO_OGG = "audio/ogg"
    const val AUDIO_WAV = "audio/wav"
    const val AUDIO_UNKNOWN = "audio/x-unknown"

    const val TEXT_VTT = "text/vtt"
    const val TEXT_SSA = "text/x-ssa"
    const val TEXT_UNKNOWN = "text/x-unknown"

    const val APPLICATION_MP4 = "application/mp4"
    const val APPLICATION_WEBM = "application/webm"
    const val APPLICATION_MATROSKA = "application/x-matroska"
    const val APPLICATION_ID3 = "application/id3"
    const val APPLICATION_CEA608 = "application/cea-608"
    const val APPLICATION_CEA708 = "application/cea-708"
    const val APPLICATION_SUBRIP = "application/x-subrip"
    const val APPLICATION_TTML = "application/ttml+xml"
    const val APPLICATION_TX3G = "application/x-quicktime-tx3g"
    const val APPLICATION_MP4VTT = "application/x-mp4-vtt"
    const val APPLICATION_MP4CEA608 = "application/x-mp4-cea-608"
    const val APPLICATION_VOBSUB = "application/vobsub"
    const val APPLICATION_PGS = "application/pgs"
    const val APPLICATION_DVBSUBS = "application/dvbsubs"
    const val APPLICATION_MEDIA3_CUES = "application/x-media3-cues"

    fun getTopLevelType(mimeType: String?): String? = mimeType?.substringBefore('/', "")?.takeIf { it.isNotEmpty() }

    fun isVideo(mimeType: String?): Boolean = getTopLevelType(mimeType) == BASE_TYPE_VIDEO

    fun isAudio(mimeType: String?): Boolean = getTopLevelType(mimeType) == BASE_TYPE_AUDIO

    fun isImage(mimeType: String?): Boolean = getTopLevelType(mimeType) == BASE_TYPE_IMAGE

    fun isText(mimeType: String?): Boolean = getTopLevelType(mimeType) == BASE_TYPE_TEXT ||
        mimeType in TEXT_APPLICATIONS

    fun isMatroska(mimeType: String?): Boolean =
        mimeType != null && (mimeType.startsWith(VIDEO_WEBM) || mimeType.startsWith(AUDIO_WEBM) ||
            mimeType.startsWith(APPLICATION_WEBM) || mimeType.startsWith(VIDEO_MATROSKA) ||
            mimeType.startsWith(AUDIO_MATROSKA) || mimeType.startsWith(APPLICATION_MATROSKA))

    fun getTrackType(mimeType: String?): Int = when {
        mimeType.isNullOrEmpty() -> C.TRACK_TYPE_UNKNOWN
        isAudio(mimeType) -> C.TRACK_TYPE_AUDIO
        isVideo(mimeType) -> C.TRACK_TYPE_VIDEO
        isText(mimeType) -> C.TRACK_TYPE_TEXT
        isImage(mimeType) -> C.TRACK_TYPE_IMAGE
        mimeType == APPLICATION_ID3 -> C.TRACK_TYPE_METADATA
        else -> C.TRACK_TYPE_UNKNOWN
    }

    private val TEXT_APPLICATIONS = setOf(
        APPLICATION_CEA608, APPLICATION_CEA708, APPLICATION_SUBRIP, APPLICATION_TTML, APPLICATION_TX3G,
        APPLICATION_MP4VTT, APPLICATION_MP4CEA608, APPLICATION_VOBSUB, APPLICATION_PGS, APPLICATION_DVBSUBS,
        APPLICATION_MEDIA3_CUES,
    )
}

/** Media3's container kinds, and their guesses from a MIME type or a location. */
object FileTypes {
    const val UNKNOWN = -1
    const val AC3 = 0
    const val AC4 = 1
    const val ADTS = 2
    const val AMR = 3
    const val FLAC = 4
    const val FLV = 5
    const val MATROSKA = 6
    const val MP3 = 7
    const val MP4 = 8
    const val OGG = 9
    const val PS = 10
    const val TS = 11
    const val WAV = 12
    const val WEBVTT = 13
    const val JPEG = 14
    const val MIDI = 15
    const val AVI = 16

    fun inferFileTypeFromMimeType(mimeType: String?): Int = when (mimeType?.lowercase()?.substringBefore(';')) {
        null -> UNKNOWN
        "video/x-matroska", "audio/x-matroska", "video/webm", "audio/webm", "application/webm" -> MATROSKA
        "video/mp4", "audio/mp4", "application/mp4" -> MP4
        "audio/mpeg" -> MP3
        "video/mp2t" -> TS
        "video/mp2p" -> PS
        "video/x-flv" -> FLV
        "audio/ogg", "video/ogg", "application/ogg" -> OGG
        "audio/flac" -> FLAC
        "audio/wav", "audio/x-wav" -> WAV
        "video/x-msvideo" -> AVI
        "text/vtt" -> WEBVTT
        "audio/ac3", "audio/eac3" -> AC3
        "audio/ac4" -> AC4
        "audio/amr", "audio/amr-wb", "audio/3gpp" -> AMR
        "image/jpeg" -> JPEG
        else -> UNKNOWN
    }

    fun inferFileTypeFromUri(uri: Uri): Int {
        val name = uri.lastPathSegment?.lowercase() ?: return UNKNOWN
        return when (name.substringAfterLast('.', "")) {
            "mkv", "mka", "mks", "webm" -> MATROSKA
            "mp4", "m4a", "m4v", "mov", "3gp" -> MP4
            "mp3" -> MP3
            "ts", "mts", "m2ts" -> TS
            "flv" -> FLV
            "ogg", "oga", "ogv", "opus" -> OGG
            "flac" -> FLAC
            "wav" -> WAV
            "avi" -> AVI
            "vtt" -> WEBVTT
            "ac3", "ec3" -> AC3
            else -> UNKNOWN
        }
    }
}
