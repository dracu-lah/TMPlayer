package androidx.media3.extractor.ts

/** Media3's MPEG-TS payload reader flags. */
object DefaultTsPayloadReaderFactory {
    const val FLAG_ALLOW_NON_IDR_KEYFRAMES = 1
    const val FLAG_IGNORE_AAC_STREAM = 1 shl 1
    const val FLAG_IGNORE_H264_STREAM = 1 shl 2
    const val FLAG_DETECT_ACCESS_UNITS = 1 shl 3
    const val FLAG_IGNORE_SPLICE_INFO_STREAM = 1 shl 4
    const val FLAG_OVERRIDE_CAPTION_DESCRIPTORS = 1 shl 5
    const val FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS = 1 shl 6
}
