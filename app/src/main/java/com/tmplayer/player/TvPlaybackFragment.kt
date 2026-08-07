package com.tmplayer.player

import android.os.Bundle
import androidx.leanback.app.VideoSupportFragment
import androidx.leanback.app.VideoSupportFragmentGlueHost
import androidx.leanback.widget.PlaybackSeekDataProvider
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.leanback.LeanbackPlayerAdapter

/**
 * Hosts the video surface and leanback's transport row. The player itself belongs to
 * [PlayerActivity] so it survives this fragment and the track pickers layered over it.
 */
@UnstableApi
class TvPlaybackFragment : VideoSupportFragment() {

    private var glue: TvPlayerGlue? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Leanback shows a buffering spinner of its own, which lands on top of TMPlayer's
        // loading screen and reads as two loaders for the same wait.
        progressBarManager.disableProgressBar()
        progressBarManager.setProgressBarView(null)

        val owner = activity as PlayerActivity
        val player = owner.player ?: return

        val adapter = LeanbackPlayerAdapter(requireContext(), player, PROGRESS_UPDATE_MS)
        val created = TvPlayerGlue(
            context = requireContext(),
            adapter = adapter,
            onSkip = owner::skipBy,
            onPickSubtitles = { owner.showTrackPicker(C.TRACK_TYPE_TEXT) },
            onPickAudio = { owner.showTrackPicker(C.TRACK_TYPE_AUDIO) },
        )
        created.host = VideoSupportFragmentGlueHost(this)
        created.title = owner.mediaTitle
        created.subtitle = owner.mediaSubtitle
        created.isSeekEnabled = true
        // A provider with no thumbnails still unlocks continuous D-pad scrubbing on the seek
        // bar, which is the seek most people holding a remote will reach for.
        created.seekProvider = PlaybackSeekDataProvider()
        glue = created
    }

    /** True while the transport row is on screen — the activity uses it to route D-pad keys. */
    fun controlsVisible(): Boolean = isControlsOverlayVisible

    override fun onDestroy() {
        glue?.host = null
        glue = null
        super.onDestroy()
    }

    private companion object {
        const val PROGRESS_UPDATE_MS = 500
    }
}
