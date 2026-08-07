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
) : PlaybackTransportControlGlue<LeanbackPlayerAdapter>(context, adapter) {

    private val rewind = PlaybackControlsRow.RewindAction(context)
    private val fastForward = PlaybackControlsRow.FastForwardAction(context)

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
        primaryActionsAdapter.add(rewind)
        super.onCreatePrimaryActions(primaryActionsAdapter) // play / pause
        primaryActionsAdapter.add(fastForward)
    }

    override fun onCreateSecondaryActions(secondaryActionsAdapter: ArrayObjectAdapter) {
        secondaryActionsAdapter.add(subtitles)
        secondaryActionsAdapter.add(audio)
    }

    override fun onActionClicked(action: Action) {
        when (action.id) {
            rewind.id -> onSkip(-SKIP_MS)
            fastForward.id -> onSkip(SKIP_MS)
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
