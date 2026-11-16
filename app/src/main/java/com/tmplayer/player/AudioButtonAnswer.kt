package com.tmplayer.player

import com.tmplayer.i18n.L

/**
 * What the audio button says when there is nothing to choose (Raju step 6.3): every press answers.
 * A video with one sound track used to open a list whose only line read "No other audio", which
 * reads as a fault; now the line says plainly how things are, and the delay lines under it (sound
 * out of step with the picture) are still there.
 */
object AudioButtonAnswer {

    /**
     * The line for a video whose sound can be [tracks] ways, or null when there is a real choice
     * (two or more). One track is "Only one audio track"; none (a silent video, or tracks this device
     * cannot play, which are listed apart) is "No audio tracks".
     */
    fun line(tracks: Int): String? = when {
        tracks <= 0 -> L.playerNoAudioTracks
        tracks == 1 -> L.playerOnlyOneAudio
        else -> null
    }
}
