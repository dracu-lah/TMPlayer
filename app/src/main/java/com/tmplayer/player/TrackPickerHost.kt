package com.tmplayer.player

import androidx.media3.common.Player
import com.tmplayer.online.SubtitleTarget
import java.io.File

/**
 * What the track picker and the online subtitle list need from the screen playing the video:
 * the player, the timing and the look, and the online subtitle hand-off. The player activity is
 * one; the promo fixture is the other, so both lists can be drawn without a Telegram account.
 */
interface TrackPickerHost {
    val player: Player?

    fun syncDelaysNow(): SyncDelays

    fun stepDelay(trackType: Int, direction: Int)

    fun subtitleStyleNow(): SubtitleStyle

    fun changeSubtitleStyle(style: SubtitleStyle)

    /** The video as an online search wants it, its hash included when both ends are on the disk. */
    suspend fun onlineTarget(): SubtitleTarget?

    /** Loads a downloaded online subtitle over the video, as a picked file would be. */
    fun attachOnlineSubtitle(file: File, label: String)
}
