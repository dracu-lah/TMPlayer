package com.tmplayer.player

/**
 * How the VLC engine is started (PR2): the arguments libVLC is created with. Only options VLC 3
 * has had for years, because an option libVLC does not know makes it refuse to start at all.
 */
data class VlcOptions(
    /** MediaCodec first, as ExoPlayer does; off sends the picture to VLC's FFmpeg. */
    val hardwareDecoding: Boolean = true,
    /** A low RAM device or a small heap (the 1 GB sticks): a shorter read ahead. */
    val lowMemory: Boolean = false,
) {
    fun arguments(): ArrayList<String> = arrayListOf(
        // Speed changes keep the pitch, as ExoPlayer's do.
        "--audio-time-stretch",
        // The stream is on 127.0.0.1, so the cache is for the TDLib download behind it, not for
        // the line: the low memory figure is what a 1 GB stick can hold beside the picture.
        "--network-caching=${if (lowMemory) LOW_CACHE_MS else CACHE_MS}",
        "--file-caching=$FILE_CACHE_MS",
        // Only the subtitle files the player hands over: never a search of the download folder.
        "--no-sub-autodetect-file",
        // Lines the size the app's own subtitles start at, with the system's fonts for any script.
        "--freetype-rel-fontsize=16",
    )

    companion object {
        const val CACHE_MS = 3_000
        const val LOW_CACHE_MS = 1_500
        const val FILE_CACHE_MS = 600
    }
}
