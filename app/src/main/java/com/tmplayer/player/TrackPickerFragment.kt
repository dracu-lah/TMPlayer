package com.tmplayer.player

import android.os.Bundle
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Translator
import com.tmplayer.online.OnlineSubtitles
import java.util.Locale

/**
 * Full-screen audio / subtitle chooser.
 *
 * Uses leanback's guided-step list rather than a dialog, because that is the one list style on
 * Android TV that is already large, D-pad-native and readable from a sofa.
 *
 * Below the tracks sit the timing lines (earlier, later, reset) and, for subtitles, their look.
 * Those act in place and leave the picker open, so a line can be nudged a tenth at a time while it
 * plays behind the list, and the look changes on the subtitles as they are chosen.
 *
 * For subtitles, "Search online" sits under the tracks and opens [OnlineSubtitlesFragment].
 */
@UnstableApi
class TrackPickerFragment : GuidedStepSupportFragment() {

    private val trackType: Int get() = requireArguments().getInt(ARG_TRACK_TYPE)

    /** Rebuilt each time the picker opens, so indices always match the current player state. */
    private val options = mutableListOf<Option>()

    private class Option(val group: Tracks.Group?, val trackIndex: Int, val label: String)

    /** PlayerActivity runs on Theme.Leanback, which has no guided-step styling of its own. */
    override fun onProvideTheme(): Int = androidx.leanback.R.style.Theme_Leanback_GuidedStep

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        val title = if (trackType == C.TRACK_TYPE_AUDIO) L.tracksAudioLanguage else L.tracksSubtitles
        return GuidanceStylist.Guidance(title, "", "", null)
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val player = (activity as? TrackPickerHost)?.player ?: return
        options.clear()

        // Subtitles need an explicit "off"; audio always has at least one track playing.
        if (trackType == C.TRACK_TYPE_TEXT) {
            options += Option(null, -1, L.commonOff)
        }

        for (group in player.currentTracks.groups) {
            if (group.type != trackType) continue
            for (index in 0 until group.length) {
                if (!group.isTrackSupported(index)) continue
                options += Option(group, index, describe(group, index, options.size))
            }
        }

        if (options.isEmpty() || (trackType == C.TRACK_TYPE_TEXT && options.size == 1)) {
            val none = if (trackType == C.TRACK_TYPE_AUDIO) {
                L.tracksNoOtherAudio
            } else {
                L.tracksNoSubtitles
            }
            actions.add(GuidedAction.Builder(requireContext()).id(ID_NONE).title(none).build())
            // Sound can still be out of step with only the one track to choose from.
            if (trackType == C.TRACK_TYPE_AUDIO && options.isNotEmpty()) addTimingActions(actions)
            // No subtitles in the file is exactly when looking online is worth it.
            if (trackType == C.TRACK_TYPE_TEXT) addOnlineAction(actions)
            return
        }

        options.forEachIndexed { index, option ->
            val selected = isSelected(player.currentTracks, option)
            actions.add(
                GuidedAction.Builder(requireContext())
                    .id(index.toLong())
                    .title(option.label)
                    .description(if (selected) L.tracksPlayingNow else null)
                    .checkSetId(GuidedAction.DEFAULT_CHECK_SET_ID)
                    .checked(selected)
                    .build(),
            )
        }
        if (trackType == C.TRACK_TYPE_TEXT) addOnlineAction(actions)
        addTimingActions(actions)
        if (trackType == C.TRACK_TYPE_TEXT) addStyleActions(actions)
    }

    /** "Search online", in a build that carries the OpenSubtitles key. */
    private fun addOnlineAction(actions: MutableList<GuidedAction>) {
        if (!OnlineSubtitles.available) return
        actions += GuidedAction.Builder(requireContext())
            .id(ID_ONLINE)
            .title(L.onlineSearchOnline)
            .description(L.onlineSearchOnlineDetail)
            .build()
    }

    private fun addTimingActions(actions: MutableList<GuidedAction>) {
        val subtitles = trackType == C.TRACK_TYPE_TEXT
        actions += plain(ID_EARLIER, if (subtitles) L.tracksSubtitlesEarlier else L.tracksSoundEarlier)
        actions += plain(ID_LATER, if (subtitles) L.tracksSubtitlesLater else L.tracksSoundLater)
        actions += plain(ID_RESET, L.tracksResetTiming)
        actions.forEach(::describeTiming)
    }

    private fun addStyleActions(actions: MutableList<GuidedAction>) {
        actions += plain(ID_SIZE, L.tracksSubtitleSize)
        actions += plain(ID_BOX, L.tracksBackgroundBox)
        actions += plain(ID_POSITION, L.tracksSubtitlePosition)
        actions.forEach(::describeStyle)
    }

    private fun plain(id: Long, title: String) =
        GuidedAction.Builder(requireContext()).id(id).title(title).build()

    /** The offset now in force, under each timing line, so a press shows where it got to. */
    private fun describeTiming(action: GuidedAction) {
        val delays = (activity as? TrackPickerHost)?.syncDelaysNow() ?: return
        val ms = if (trackType == C.TRACK_TYPE_TEXT) delays.subtitleMs else delays.audioMs
        when (action.id) {
            ID_EARLIER, ID_LATER -> action.description = L.tracksDelayStep(SyncDelays.label(ms))
            ID_RESET -> {
                action.description = if (ms == 0L) L.tracksDelayNone else L.tracksDelayNow(SyncDelays.label(ms))
                action.isEnabled = ms != 0L
            }
        }
    }

    private fun describeStyle(action: GuidedAction) {
        val style = (activity as? TrackPickerHost)?.subtitleStyleNow() ?: return
        when (action.id) {
            ID_SIZE -> action.description = style.size.label
            ID_BOX -> action.description = if (style.box) L.commonOn else L.commonOff
            ID_POSITION -> action.description = style.position.label
        }
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        val activity = activity as? TrackPickerHost
        val player = activity?.player
        if (activity != null && action.id in IN_PLACE) {
            actOnPlace(activity, action.id)
            return
        }
        if (action.id == ID_ONLINE) {
            GuidedStepSupportFragment.add(parentFragmentManager, OnlineSubtitlesFragment(), id)
            return
        }
        if (player == null || action.id == ID_NONE) {
            finishGuidedStepSupportFragments()
            return
        }

        val option = options.getOrNull(action.id.toInt())
        if (option != null) {
            val builder = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(trackType)
            if (option.group == null) {
                builder.setTrackTypeDisabled(trackType, true)
            } else {
                builder
                    .setTrackTypeDisabled(trackType, false)
                    .setOverrideForType(
                        TrackSelectionOverride(option.group.mediaTrackGroup, option.trackIndex),
                    )
            }
            player.trackSelectionParameters = builder.build()
        }
        finishGuidedStepSupportFragments()
    }

    /** A timing or look line: applied at once, the picker stays where it is. */
    private fun actOnPlace(activity: TrackPickerHost, id: Long) {
        when (id) {
            ID_EARLIER -> activity.stepDelay(trackType, -1)
            ID_LATER -> activity.stepDelay(trackType, 1)
            ID_RESET -> activity.stepDelay(trackType, 0)
            ID_SIZE -> activity.changeSubtitleStyle(activity.subtitleStyleNow().nextSize())
            ID_BOX -> activity.subtitleStyleNow().let { activity.changeSubtitleStyle(it.copy(box = !it.box)) }
            ID_POSITION -> activity.changeSubtitleStyle(activity.subtitleStyleNow().nextPosition())
        }
        for (action in actions) {
            if (action.id !in IN_PLACE) continue
            describeTiming(action)
            describeStyle(action)
            notifyActionChanged(findActionPositionById(action.id))
        }
        // Reset greys itself out at zero, and focus on a disabled line is lost: move it to Later.
        if (id == ID_RESET) setSelectedActionPosition(findActionPositionById(ID_LATER))
    }

    private fun isSelected(tracks: Tracks, option: Option): Boolean {
        if (option.group == null) {
            return tracks.groups.none { it.type == trackType && it.isSelected }
        }
        return option.group.isTrackSelected(option.trackIndex)
    }

    /** Language first, then sound format and channel layout: what tells dual audio apart. */
    private fun describe(group: Tracks.Group, index: Int, position: Int): String {
        val format = group.getTrackFormat(index)
        val language = format.language
            ?.takeIf { it.isNotBlank() && it != "und" }
            ?.let { Translator.messages.formatter.trackLanguage(it) ?: it }
        val label = format.label?.takeIf { it.isNotBlank() }

        val name = when {
            label != null && language != null && !label.contains(language, ignoreCase = true) ->
                "$language ($label)"
            label != null -> label
            language != null -> language
            else -> L.tracksTrackNumber((position + 1).toString())
        }

        val details = buildList {
            soundFormat(format)?.let { add(it) }
            if (format.channelCount > 0) add(channels(format.channelCount))
        }

        return if (details.isEmpty()) name else "$name  ·  ${details.joinToString(" ")}"
    }

    /**
     * The two sound-format names a viewer might recognise, and nothing else. Anything outside the
     * list is left out: the channel layout beside it already identifies the track.
     */
    private fun soundFormat(format: Format): String? {
        val id = "${format.codecs.orEmpty()} ${format.sampleMimeType.orEmpty()}".lowercase(Locale.ROOT)
        return when {
            DOLBY.any { id.contains(it) } -> "Dolby"
            DTS.any { id.contains(it) } -> "DTS"
            else -> null
        }
    }

    private fun channels(count: Int) = when (count) {
        1 -> L.tracksMono
        2 -> L.tracksStereo
        6 -> "5.1"
        8 -> "7.1"
        else -> L.tracksChannels(count.toString())
    }

    companion object {
        private const val ARG_TRACK_TYPE = "track_type"
        private const val ID_NONE = -1L

        // Well clear of the track lines, whose ids are their positions in the list.
        private const val ID_EARLIER = 1_000L
        private const val ID_LATER = 1_001L
        private const val ID_RESET = 1_002L
        private const val ID_SIZE = 1_010L
        private const val ID_BOX = 1_011L
        private const val ID_POSITION = 1_012L
        private const val ID_ONLINE = 1_020L
        private val IN_PLACE = setOf(ID_EARLIER, ID_LATER, ID_RESET, ID_SIZE, ID_BOX, ID_POSITION)

        // Spelled several ways depending on whether the id came from the MP4 sample entry or
        // from the MIME type, so both are matched.
        private val DOLBY = listOf("ac-3", "ac3", "ec-3", "eac3")
        private val DTS = listOf("dts", "dca")

        fun forType(trackType: Int) = TrackPickerFragment().apply {
            arguments = Bundle().apply { putInt(ARG_TRACK_TYPE, trackType) }
        }
    }
}
