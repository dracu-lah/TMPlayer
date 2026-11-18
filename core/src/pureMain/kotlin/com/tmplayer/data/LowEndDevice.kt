package com.tmplayer.data

/**
 * What the player does differently on a low-end device, pure so it is tested alone (ANR triage,
 * 2026-11-18): a Redmi 6A (4 cores, 2 GB, Android 9) stopped answering while FFmpeg decoded the
 * picture in software (`ExoPlayer:SimpleDecoder` in `FfmpegVideoDecoder.decode`). NextLib starts
 * one decoding thread per core, so all four cores went to the picture and none was left for the
 * screen. A normal device is left exactly as it was.
 *
 * Not changed: holding the decoder back until the player's screen has resumed. The trace was
 * of steady decoding well into the video, which a later start would not have helped.
 */
object LowEndDevice {

    /**
     * Low end for this purpose: Android's own low RAM flag, or less memory than
     * [DeviceFitRules]'s line (2 GiB on a phone, 1.5 GiB on a TV). Unlike [DeviceFitRules.assess],
     * an old Android version or a 32-bit only device is not counted: that says nothing of its CPU.
     */
    fun isLowEnd(lowRamDevice: Boolean, totalMemoryBytes: Long?, tv: Boolean): Boolean {
        val line = if (tv) DeviceFitRules.TV_MEMORY_BYTES else DeviceFitRules.PHONE_MEMORY_BYTES
        return lowRamDevice || (totalMemoryBytes != null && totalMemoryBytes in 1 until line)
    }

    /**
     * The decoding threads for FFmpeg's software video decoder: null leaves NextLib's own (one per
     * core). On a low-end device, half the cores rounded up and never all of them, so at least
     * one is free for the screen and the sound: 2 of 4, 4 of 8, 1 of 2 or of 1.
     */
    fun softwareVideoThreads(cores: Int, lowEnd: Boolean): Int? {
        if (!lowEnd || cores <= 0) return null
        return ((cores + 1) / 2).coerceAtMost(cores - 1).coerceAtLeast(1)
    }
}
