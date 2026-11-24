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

    /** The player whose tracks the picker lists and picks: [player], or the VLC engine's when VLC plays (PR2). */
    val trackPlayer: Player? get() = player

    fun syncDelaysNow(): SyncDelays

    fun stepDelay(trackType: Int, direction: Int)

    fun subtitleStyleNow(): SubtitleStyle

    fun changeSubtitleStyle(style: SubtitleStyle)

    /**
     * Whether the look is this video's own (WP-29 "This video only"): null where the video has nowhere to
     * keep one (a file opened from elsewhere, the fixture), and the switch is then not drawn.
     */
    val subtitleThisVideo: Boolean? get() = null

    /** Turns "This video only" on (the look in force becomes this video's) or off (it follows every video again). */
    fun setSubtitleThisVideo(on: Boolean) = Unit

    /** The video as an online search wants it, its hash included when both ends are on the disk. */
    suspend fun onlineTarget(): SubtitleTarget?

    /** Loads a downloaded online subtitle over the video, as a picked file would be. */
    fun attachOnlineSubtitle(file: File, label: String)

    /**
     * Runs SubMaker's "Make <Language>" [hit] for [target] as a background job: the video plays
     * on, and the loaded track is replaced as lines arrive. False when one runs for it already.
     */
    fun startTranslation(hit: com.tmplayer.online.SubtitleHit, target: SubtitleTarget): Boolean = false

    /** [hit], downloaded as [file], was picked for this video: remembered, so it comes back by itself (HX25). */
    fun onlineSubtitlePicked(hit: com.tmplayer.online.SubtitleHit, file: File) = Unit

    /**
     * A subtitle track, or Off, was chosen in the list: Off is remembered for the video; an embedded
     * track ends whatever was remembered (the latest choice wins); a side file ([external], loaded
     * online or from the phone) only ends an Off.
     */
    fun subtitleChoiceMade(off: Boolean, external: Boolean = false) = Unit

    /**
     * The way on when the online search found nothing: pick a subtitle file from the device.
     * Null where there is no picker (a television, the fixture).
     */
    val pickSubtitleFile: (() -> Unit)? get() = null

    /**
     * Subtitle files posted in the video's chat, best first, for "In this chat" (EG-F48). Empty
     * when there are none, the device is offline, the kids lock leaves the chat out, or the host
     * has no chat (the fixture); the entry is then not drawn.
     */
    suspend fun chatSubtitles(): List<ChatSubtitles.Hit> = emptyList()

    /** Downloads [hit] and lays it over the video: null when done, else the sentence to show. */
    suspend fun attachChatSubtitle(hit: ChatSubtitles.Hit): String? = null
}
