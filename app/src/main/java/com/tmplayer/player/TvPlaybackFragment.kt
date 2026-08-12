package com.tmplayer.player

import android.os.Bundle
import androidx.leanback.app.VideoSupportFragment
import androidx.leanback.app.VideoSupportFragmentGlueHost
import androidx.leanback.widget.PlaybackSeekDataProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.leanback.LeanbackPlayerAdapter
import kotlinx.coroutines.launch

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
        // loading screen and reads as two loaders for the same wait. Disabling it is enough:
        // show() returns immediately once the flag is off. Do not also call
        // setProgressBarView(null): that method dereferences its argument to check for a
        // parent, so null takes the activity down before the surface is ever created.
        progressBarManager.disableProgressBar()

        val owner = activity as PlayerActivity
        val player = owner.player ?: return

        val adapter = LeanbackPlayerAdapter(requireContext(), player, PROGRESS_UPDATE_MS)
        val created = TvPlayerGlue(
            context = requireContext(),
            adapter = adapter,
            onSkip = owner::skipBy,
            onPickSubtitles = { owner.showTrackPicker(C.TRACK_TYPE_TEXT) },
            onPickAudio = { owner.showTrackPicker(C.TRACK_TYPE_AUDIO) },
            onPreviousEpisode = { owner.episodes.value.previous?.let(owner::playEpisode) },
            onNextEpisode = { owner.episodes.value.next?.let(owner::playEpisode) },
            onCycleSpeed = { owner.cycleSpeed() },
            onCycleScale = { owner.cycleScale() },
        )
        created.host = VideoSupportFragmentGlueHost(this)
        created.title = owner.mediaTitle
        created.subtitle = owner.mediaSubtitle
        created.isSeekEnabled = true
        // A provider with no thumbnails still unlocks continuous D-pad scrubbing on the seek
        // bar, which is the seek most people holding a remote will reach for.
        created.seekProvider = PlaybackSeekDataProvider()
        glue = created

        // Neighbouring episodes arrive after playback starts, once the chat search completes.
        // They never hold playback up; the transport row adds its buttons when they land.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                owner.episodes.collect {
                    created.setEpisodes(
                        hasPrevious = it.previous != null,
                        hasNext = it.next != null,
                        previousLabel = owner.episodeLabel("Previous", it.previous),
                        nextLabel = owner.episodeLabel("Next", it.next),
                    )
                }
            }
        }
    }

    /** True while the transport row is on screen; the activity uses it to route D-pad keys. */
    fun controlsVisible(): Boolean = isControlsOverlayVisible

    /** The activity applies the change and then asks the row to say what it now reads. */
    fun showPlaybackSpeed(value: Float) = glue?.setPlaybackSpeed(value)

    fun showVideoScale(value: VideoScale) = glue?.setVideoScale(value)

    // Leanback raises and hides the transport row itself, on a key press, on pause, and on its
    // own auto-hide timer. Overriding both ends of that is the only way to hear about it, and the
    // activity needs to: the download figure rides with the controls.

    override fun showControlsOverlay(runAnimation: Boolean) {
        super.showControlsOverlay(runAnimation)
        (activity as? PlayerActivity)?.onControlsVisibilityChanged(true)
    }

    override fun hideControlsOverlay(runAnimation: Boolean) {
        super.hideControlsOverlay(runAnimation)
        (activity as? PlayerActivity)?.onControlsVisibilityChanged(false)
    }

    override fun onDestroy() {
        glue?.host = null
        glue = null
        super.onDestroy()
    }

    private companion object {
        const val PROGRESS_UPDATE_MS = 500
    }
}
