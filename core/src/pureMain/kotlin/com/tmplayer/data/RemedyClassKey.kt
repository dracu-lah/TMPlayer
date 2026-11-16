package com.tmplayer.data

/**
 * The class a video plays as, for [RemedyMemory] (RL2, research 4.3): the chipset, the Android
 * version, the picture's kind ([DecodeMemory.keyOf]: codec, bit depth, size and rate bucket), the
 * codec profile, the container family and the audio codec. Never a title or a file id, so one
 * remedy learned on one video serves every video of the same kind on the same kind of device.
 *
 * As text: `sun50iw6p1/28/hevc:10:1080:30/2/mkv/eac3`, at most [MAX_LENGTH] characters, made only
 * of `a-z 0-9 . _ - : /` so it never collides with the memory's separators.
 */
object RemedyClassKey {

    const val MAX_LENGTH = 64

    fun of(
        device: DeviceFacts,
        picture: DecodeJudge.Picture,
        codecs: String? = null,
        container: String? = null,
        audioMime: String? = null,
    ): String {
        val chip = listOf(device.socModel, device.hardware, device.board)
            .map { clean(it, CHIP_MAX) }.firstOrNull { it.isNotEmpty() } ?: "unknown"
        val sdk = device.sdk.coerceIn(0, 999)
        val kind = clean(DecodeMemory.keyOf(picture), KIND_MAX).ifEmpty { "-" }
        val parts = listOf(chip, sdk.toString(), kind, profileOf(codecs), containerOf(container), audioOf(audioMime))
        return parts.joinToString("/").take(MAX_LENGTH)
    }

    /** The profile bucket from a codecs string: `dv5` for Dolby Vision profile 5, else the second field (`2` of `hvc1.2.4.L153`). */
    fun profileOf(codecs: String?): String {
        val parts = codecs?.trim()?.lowercase()?.split('.') ?: return "-"
        val head = parts.firstOrNull().orEmpty()
        val second = parts.getOrNull(1).orEmpty()
        if (head in DOLBY_VISION) return "dv" + (second.toIntOrNull()?.coerceIn(0, 99) ?: "")
        return clean(second, PROFILE_MAX).ifEmpty { "-" }
    }

    /** `mp4`, `mkv`, `ts`, `avi` or `other`, from a container MIME type or a file extension. */
    fun containerOf(container: String?): String {
        val c = container?.trim()?.lowercase()?.substringAfterLast('/')?.substringAfterLast('.') ?: return "other"
        return when {
            c in setOf("mp4", "m4v", "mov", "quicktime", "3gp", "3gpp") -> "mp4"
            c in setOf("mkv", "webm", "x-matroska", "matroska", "mka") -> "mkv"
            c in setOf("ts", "m2ts", "mts", "mp2t", "mpeg-ts", "mpegts", "tsv") -> "ts"
            c in setOf("avi", "x-msvideo") -> "avi"
            else -> "other"
        }
    }

    /** A short audio codec family from its MIME type: `aac`, `ac3`, `eac3`, `dts`, `truehd`, `opus`, `-` for none. */
    fun audioOf(mime: String?): String {
        val m = mime?.trim()?.lowercase()?.substringAfter('/') ?: return "-"
        return when {
            m.isEmpty() -> "-"
            m.startsWith("mp4a") || m == "aac" -> "aac"
            m == "eac3" || m == "eac3-joc" -> "eac3"
            m == "ac3" -> "ac3"
            m.contains("dts") -> "dts"
            m.contains("true-hd") || m.contains("truehd") -> "truehd"
            else -> clean(m, AUDIO_MAX).ifEmpty { "-" }
        }
    }

    private fun clean(text: String, max: Int): String =
        text.trim().lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == '-' || it == ':' }.take(max)

    private val DOLBY_VISION = setOf("dvhe", "dvh1", "dav1", "dvav", "dva1")
    private const val CHIP_MAX = 16
    private const val KIND_MAX = 20
    private const val PROFILE_MAX = 4
    private const val AUDIO_MAX = 8
}
