package com.tmplayer.player

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.leanback.media.PlaybackTransportControlGlue
import androidx.leanback.widget.Action
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.PlaybackControlsRow
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.leanback.LeanbackPlayerAdapter
import com.tmplayer.R

/**
 * Leanback's stock transport controls with three additions: explicit skip buttons, a subtitle
 * picker and an audio-track picker.
 *
 * The rest (progress bar, D-pad scrubbing, auto-hide, play/pause) is leanback's, which is
 * the point: a TV transport UI is not worth rewriting.
 */
@UnstableApi
class TvPlayerGlue(
    context: Context,
    adapter: LeanbackPlayerAdapter,
    private val onSkip: (Long) -> Unit,
    private val onPickSubtitles: () -> Unit,
    private val onPickAudio: () -> Unit,
    private val onPreviousEpisode: () -> Unit = {},
    private val onNextEpisode: () -> Unit = {},
) : PlaybackTransportControlGlue<LeanbackPlayerAdapter>(context, adapter) {

    private val rewind = PlaybackControlsRow.RewindAction(context)
    private val fastForward = PlaybackControlsRow.FastForwardAction(context)

    // The standard skip glyphs, so a series watcher recognises them from every other player.
    // They are built here but only put on the row once the chat has been asked whether the
    // episodes either side of this one exist: a button that does nothing is worse than no button.
    private val skipPrevious = PlaybackControlsRow.SkipPreviousAction(context)
    private val skipNext = PlaybackControlsRow.SkipNextAction(context)
    private var primary: ArrayObjectAdapter? = null

    // Labelled rather than left as bare glyphs: leanback reads label1 out as the action's
    // accessible name, and shows it as a tooltip on focus, so an unlabelled button is one a
    // viewer has to press to find out what it does.
    private val subtitles = Action(
        ID_SUBTITLES,
        "Subtitles",
        null,
        ContextCompat.getDrawable(context, R.drawable.ic_subtitles),
    )
    private val audio = Action(
        ID_AUDIO,
        "Audio",
        null,
        ContextCompat.getDrawable(context, R.drawable.ic_audio_language),
    )

    override fun onCreatePrimaryActions(primaryActionsAdapter: ArrayObjectAdapter) {
        primary = primaryActionsAdapter
        primaryActionsAdapter.add(rewind)
        super.onCreatePrimaryActions(primaryActionsAdapter) // play / pause
        primaryActionsAdapter.add(fastForward)
    }

    /**
     * Puts the episode buttons on the row, or takes them off.
     *
     * Called once the chat has been searched, which is after the row already exists, so the two
     * actions are inserted around what is there: previous before the rewind, next after the
     * fast-forward, the order every transport row on a television uses.
     */
    fun setEpisodes(hasPrevious: Boolean, hasNext: Boolean) {
        val adapter = primary ?: return
        setPresent(adapter, skipPrevious, hasPrevious, index = 0)
        setPresent(adapter, skipNext, hasNext, index = adapter.size())
    }

    private fun setPresent(
        adapter: ArrayObjectAdapter,
        action: Action,
        wanted: Boolean,
        index: Int,
    ) {
        val at = adapter.indexOf(action)
        when {
            wanted && at < 0 -> adapter.add(index, action)
            !wanted && at >= 0 -> adapter.remove(action)
        }
    }

    override fun onCreateSecondaryActions(secondaryActionsAdapter: ArrayObjectAdapter) {
        secondaryActionsAdapter.add(subtitles)
        secondaryActionsAdapter.add(audio)
    }

    override fun onActionClicked(action: Action) {
        when (action.id) {
            rewind.id -> onSkip(-SKIP_MS)
            fastForward.id -> onSkip(SKIP_MS)
            skipPrevious.id -> onPreviousEpisode()
            skipNext.id -> onNextEpisode()
            ID_SUBTITLES -> onPickSubtitles()
            ID_AUDIO -> onPickAudio()
            else -> super.onActionClicked(action)
        }
    }

    companion object {
        private const val ID_SUBTITLES = 1001L
        private const val ID_AUDIO = 1002L
        const val SKIP_MS = 30_000L
    }
}
