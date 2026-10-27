package com.tmplayer.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.MaterialTheme as M3Theme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Text as M3Text
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.R
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Translator
import com.tmplayer.online.DownloadResult
import com.tmplayer.online.OnlineSubtitles
import com.tmplayer.online.OnlineWords
import com.tmplayer.online.SearchResult
import com.tmplayer.online.SubtitleHit
import com.tmplayer.online.SubtitleNotice
import com.tmplayer.online.SubtitleTarget
import com.tmplayer.ui.components.ChoiceLine
import com.tmplayer.ui.components.ChoiceColumns
import com.tmplayer.ui.components.ChoiceList
import com.tmplayer.ui.components.ChoiceSection
import com.tmplayer.ui.components.ChoiceSheet
import com.tmplayer.ui.components.FocusOnOpen
import com.tmplayer.ui.components.LocalChoiceWide
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The audio and subtitle chooser, and the subtitles' "Search online" results, drawn as the
 * language picker's modal ([ChoiceSheet]) over the dimmed video on a phone and a television alike.
 *
 * Choosing a track acts on the one tap or press and closes. Below the tracks sit the timing lines
 * (earlier, later, reset) and, for subtitles, their look. Those act in place and leave the picker
 * open where it was scrolled to, so a line can be nudged a tenth at a time while it plays behind
 * the dimmed list, and the look changes on the subtitles as they are chosen.
 *
 * For subtitles, "Search online" sits under the tracks and turns the panel into what OpenSubtitles
 * (and SubDL, with the viewer's key) has for the video. Downloads work without signing in; what
 * stands in the way (the day's downloads spent, with or without an account, the service
 * unavailable) is said above the results, and the results it blocks are greyed out. Back returns
 * from the results to the tracks; Close at the bottom closes the picker from either page.
 *
 * A fragment on the activity's back stack, so the player knows the picker is up ([isOpen]) and
 * holds its controls while it is. The panel itself is a window of its own (a dialog), which takes
 * the remote's keys away from the player while it is up: nothing behind it seeks or pauses.
 */
@UnstableApi
class TrackPickerFragment : Fragment() {

    private val trackType: Int get() = requireArguments().getInt(ARG_TRACK_TYPE)
    private val host: TrackPickerHost? get() = activity as? TrackPickerHost

    /** A track to choose, its name and, under it, what tells it apart from a namesake. */
    private class Option(val group: Tracks.Group?, val trackIndex: Int, val label: String, val detail: String? = null)

    private enum class Page { Tracks, Online, Msone }

    /** The groups a page's lines fall into, in order, with the heading each one carries. */
    private enum class Group(val heading: () -> String?) {
        Tracks({ null }),
        Online({ null }),
        Timing({ L.tracksSectionTiming }),
        Look({ L.tracksSectionLook }),
    }

    /** One line of either page, drawn as the phone's or the television's row. */
    private class Line(
        val key: Any,
        val title: String,
        val detail: String? = null,
        val selected: Boolean? = null,
        val icon: ImageVector? = null,
        val enabled: Boolean = true,
        val group: Group = Group.Tracks,
        val onClick: () -> Unit,
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            // Not named Content: inside apply that resolves to ComposeView.Content, which draws
            // this same lambda again, and the player died of a stack overflow on every TV.
            setContent { TMPlayerTheme { PickerPanel() } }
        }

    /** Closes the picker from any page. */
    private fun close() {
        val fm = parentFragmentManager
        if (isAdded && !fm.isStateSaved) fm.popBackStack(BACK_STACK, FragmentManager.POP_BACK_STACK_INCLUSIVE)
    }

    @Composable
    private fun PickerPanel() {
        val startOnline = requireArguments().getBoolean(ARG_ONLINE)
        var page by remember { mutableStateOf(if (startOnline) Page.Online else Page.Tracks) }
        val touch = isTouch()
        val closeFocus = remember { FocusRequester() }
        val firstFocus = remember { FocusRequester() }

        // From the results, Back is a step back to the tracks; from the tracks it closes. The
        // panel is a dialog window of its own, so Back reaches it as the dialog's dismissal
        // (onDismiss below), never this fragment; a BackHandler here was never asked.
        val back: () -> Unit = { if (startOnline) close() else page = Page.Tracks }

        when (page) {
            Page.Tracks -> {
                // Bumped by every in-place line, so the timing and look figures under them redraw.
                var tick by remember { mutableIntStateOf(0) }
                val title = if (trackType == C.TRACK_TYPE_AUDIO) L.tracksAudioLanguage else L.tracksSubtitles
                ChoiceSheet(title = title, onDismiss = ::close, onClose = ::close, closeFocus = closeFocus, ignoreRelease = !touch, fullScreen = true) {
                    @Suppress("UNUSED_EXPRESSION") tick
                    val lines = trackLines(
                        onOnline = { page = Page.Online },
                        onMsone = { page = Page.Msone },
                        onInPlace = { tick++ },
                        lookIcon = ImageVector.vectorResource(R.drawable.ic_subtitles),
                    )
                    Lines(
                        lines,
                        firstFocus,
                        focusKey = lines.firstOrNull { it.selected == true }?.key ?: lines.firstOrNull()?.key,
                        onInPlace = { tick++ },
                        version = tick,
                    )
                    // Inside the dialog's own composition: asked from outside, the request came
                    // before the lines existed, and the window's own focus fell on the first line
                    // (Off) instead of the track playing.
                    FocusOnOpen(firstFocus)
                }
            }
            Page.Online -> OnlinePage(closeFocus, firstFocus, back, msone = false)
            Page.Msone -> OnlinePage(closeFocus, firstFocus, { page = Page.Tracks }, msone = true)
        }
    }

    // ---- the tracks ---------------------------------------------------------------------------

    private fun trackLines(onOnline: () -> Unit, onMsone: () -> Unit, onInPlace: () -> Unit, lookIcon: ImageVector): List<Line> {
        val host = host ?: return emptyList()
        val player = host.player ?: return emptyList()
        val options = buildList {
            // Subtitles need an explicit "off"; audio always has at least one track playing.
            if (trackType == C.TRACK_TYPE_TEXT) add(Option(null, -1, L.commonOff))
            var number = 0
            for (group in player.currentTracks.groups) {
                if (group.type != trackType) continue
                for (index in 0 until group.length) {
                    if (!group.isTrackSupported(index)) continue
                    number++
                    val format = group.getTrackFormat(index)
                    add(Option(group, index, describe(group, index, number - 1), tags(format).ifEmpty { null }?.joinToString("  ·  ")))
                }
            }
        }.let(::numberNamesakes)
        return buildList {
            val none = options.isEmpty() || (trackType == C.TRACK_TYPE_TEXT && options.size == 1)
            if (none) {
                add(Line("none", if (trackType == C.TRACK_TYPE_AUDIO) L.tracksNoOtherAudio else L.tracksNoSubtitles) { close() })
                // Sound can still be out of step with only the one track to choose from.
                if (trackType == C.TRACK_TYPE_AUDIO && options.isNotEmpty()) addAll(timingLines(host, onInPlace))
                // No subtitles in the file is exactly when looking online is worth it.
                if (trackType == C.TRACK_TYPE_TEXT) {
                    onlineLine(onOnline)?.let(::add)
                    msoneLine(onMsone)?.let(::add)
                }
                return@buildList
            }
            options.forEachIndexed { index, option ->
                val selected = isSelected(player.currentTracks, option)
                add(
                    Line(
                        key = "track$index",
                        title = option.label,
                        detail = listOfNotNull(if (selected) L.tracksPlayingNow else null, option.detail)
                            .ifEmpty { null }?.joinToString("  ·  "),
                        selected = selected,
                    ) { choose(option) },
                )
            }
            if (trackType == C.TRACK_TYPE_TEXT) {
                onlineLine(onOnline)?.let(::add)
                msoneLine(onMsone)?.let(::add)
            }
            addAll(timingLines(host, onInPlace))
            if (trackType == C.TRACK_TYPE_TEXT) addAll(styleLines(host, onInPlace, lookIcon))
        }
    }

    /** "Search online", in a build that carries the OpenSubtitles key. */
    private fun onlineLine(onOnline: () -> Unit): Line? {
        if (!OnlineSubtitles.available) return null
        return Line("online", L.onlineSearchOnline, L.onlineSearchOnlineDetail, icon = TmIcons.Search, group = Group.Online, onClick = onOnline)
    }

    /**
     * MSone's search, only on a device where it was switched on by hand (`msone` as the SubDL
     * key) while MSone is being set up. It needs no OpenSubtitles key.
     */
    private fun msoneLine(onMsone: () -> Unit): Line? {
        if (OnlineSubtitles.current?.store?.now?.msoneShown != true) return null
        return Line("msone", L.msoneSearch, L.msoneSearchDetail, icon = TmIcons.Search, group = Group.Online, onClick = onMsone)
    }

    /** The offset now in force, under each timing line, so a press shows where it got to. */
    private fun timingLines(host: TrackPickerHost, onInPlace: () -> Unit): List<Line> {
        val subtitles = trackType == C.TRACK_TYPE_TEXT
        val delays = host.syncDelaysNow()
        val ms = if (subtitles) delays.subtitleMs else delays.audioMs
        val step = L.tracksDelayStep(SyncDelays.label(ms))
        fun nudge(direction: Int) {
            host.stepDelay(trackType, direction)
            onInPlace()
        }
        return listOf(
            Line("earlier", if (subtitles) L.tracksSubtitlesEarlier else L.tracksSoundEarlier, step, icon = TmIcons.ChevronLeft, group = Group.Timing) { nudge(-1) },
            Line("later", if (subtitles) L.tracksSubtitlesLater else L.tracksSoundLater, step, icon = TmIcons.ChevronRight, group = Group.Timing) { nudge(1) },
            // Greys itself out at zero, but keeps the remote: the row stays focusable.
            Line(
                key = "reset",
                title = L.tracksResetTiming,
                detail = if (ms == 0L) L.tracksDelayNone else L.tracksDelayNow(SyncDelays.label(ms)),
                icon = TmIcons.Refresh,
                enabled = ms != 0L,
                group = Group.Timing,
            ) { nudge(0) },
        )
    }

    private fun styleLines(host: TrackPickerHost, onInPlace: () -> Unit, icon: ImageVector): List<Line> {
        val style = host.subtitleStyleNow()
        fun change(next: SubtitleStyle) {
            host.changeSubtitleStyle(next)
            onInPlace()
        }
        // Each line steps through its choices with one press, the way a remote drives them; the
        // subtitles behind the dimmed list are the preview.
        return listOf(
            Line("size", L.tracksSubtitleSize, style.size.label, icon = icon, group = Group.Look) { change(host.subtitleStyleNow().nextSize()) },
            Line("colour", L.subtitlesColour, style.colour.label, icon = icon, group = Group.Look) { change(host.subtitleStyleNow().nextColour()) },
            Line("edge", L.subtitlesEdge, style.edge.label, icon = icon, enabled = !style.box, group = Group.Look) { change(host.subtitleStyleNow().nextEdge()) },
            Line("box", L.tracksBackgroundBox, style.boxLevel.label, icon = icon, group = Group.Look) { change(host.subtitleStyleNow().nextBox()) },
            Line("face", L.subtitlesFace, style.face.label, icon = icon, group = Group.Look) { change(host.subtitleStyleNow().nextFace()) },
            Line("weight", L.subtitlesWeight, style.weightLabel, icon = icon, group = Group.Look) {
                host.subtitleStyleNow().let { change(it.copy(bold = !it.bold)) }
            },
            Line("position", L.tracksSubtitlePosition, style.position.label, icon = icon, group = Group.Look) { change(host.subtitleStyleNow().nextPosition()) },
            // Greys itself out on the default look, but keeps the remote: the row stays focusable.
            Line(
                key = "look_reset",
                title = L.subtitlesDefaultLook,
                detail = L.subtitlesDefaultLookDetail,
                icon = TmIcons.Refresh,
                enabled = !style.isDefault,
                group = Group.Look,
            ) { change(SubtitleStyle()) },
        )
    }

    private fun choose(option: Option) {
        val player = host?.player
        if (player != null) {
            val builder = player.trackSelectionParameters.buildUpon().clearOverridesOfType(trackType)
            if (option.group == null) {
                builder.setTrackTypeDisabled(trackType, true)
            } else {
                builder
                    .setTrackTypeDisabled(trackType, false)
                    .setOverrideForType(TrackSelectionOverride(option.group.mediaTrackGroup, option.trackIndex))
            }
            player.trackSelectionParameters = builder.build()
        }
        close()
    }

    // ---- online -------------------------------------------------------------------------------

    /**
     * The results of "Search online", or with [msone] of MSone's search. MSone's state at the
     * gateway (not set up, paused or stopped at its request) shows where the results would be,
     * with the gateway's own words under it, Check again, and a subtitle file of the viewer's own.
     */
    @Composable
    private fun OnlinePage(closeFocus: FocusRequester, firstFocus: FocusRequester, onBack: () -> Unit, msone: Boolean) {
        val online = OnlineSubtitles.current ?: return close()
        val scope = rememberCoroutineScope()
        var attempt by remember { mutableIntStateOf(0) }
        // Check again asks the gateway even inside the six hours between checks.
        var force by remember { mutableStateOf(false) }
        var result by remember { mutableStateOf<SearchResult?>(null) }
        var override by remember { mutableStateOf<SubtitleNotice?>(null) }
        var target by remember { mutableStateOf<SubtitleTarget?>(null) }
        var busy by remember { mutableStateOf<SubtitleHit?>(null) }

        LaunchedEffect(attempt) {
            result = null
            override = null
            val wanted = host?.onlineTarget()
            target = wanted
            if (msone && force) online.msoneStatus(force = true)
            force = false
            result = when {
                wanted == null -> SearchResult(emptyList())
                msone -> online.searchMsone(wanted)
                else -> online.search(wanted)
            }
        }

        ChoiceSheet(
            title = if (msone) L.msoneTitle else L.onlineResultsTitle,
            note = if (msone) L.msoneCredit else L.onlineSearchOnlineDetail,
            onDismiss = onBack,
            onClose = ::close,
            closeFocus = closeFocus,
            fullScreen = true,
        ) {
            val shown = result
            if (shown == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Tone.accent, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(if (msone) L.msoneSearching else L.onlineSearching, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
                }
                // A search that runs long says so, with Retry, rather than spinning in silence.
                com.tmplayer.ui.components.SlowAnswerNotice(key = attempt, onRetry = { attempt++ }, modifier = Modifier.padding(top = 12.dp))
                // Nothing to choose yet, so the remote waits on Close.
                FocusOnOpen(closeFocus)
                return@ChoiceSheet
            }
            val notice = override ?: shown.notice
            if (notice != null) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Corner.Medium))
                        .background(Tone.surfaceHigh)
                        .padding(12.dp),
                ) {
                    Text(OnlineWords.notice(notice), style = MaterialTheme.typography.bodyMedium, color = Tone.text)
                    // The gateway's own words, as it gave them (MSone's, quoted by the operator).
                    OnlineWords.message(notice)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Tone.muted, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            } else if (shown.hits.isEmpty()) {
                Text(if (msone) L.msoneNoResults else L.onlineNoResults, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
            }
            val lines = buildList {
                shown.hits.forEach { hit ->
                    add(
                        Line(
                            key = "hit:${hit.provider}:${hit.id}",
                            title = hit.release.ifBlank { hit.fileName },
                            detail = if (busy == hit) L.onlineDownloading else OnlineWords.hitDetail(hit),
                            enabled = online.blockedFor(hit) == null,
                        ) {
                            if (busy != null) return@Line
                            busy = hit
                            scope.launch {
                                when (val done = online.download(hit, target)) {
                                    is DownloadResult.Done -> {
                                        host?.attachOnlineSubtitle(done.file, done.label)
                                        close()
                                    }
                                    is DownloadResult.Failed -> override = done.notice
                                }
                                busy = null
                            }
                        },
                    )
                }
                if (notice is SubtitleNotice.MsoneOff) {
                    add(Line("retry", L.msoneCheckAgain, icon = TmIcons.Refresh, group = Group.Online) { force = true; attempt++ })
                } else if (notice == SubtitleNotice.Offline || notice == SubtitleNotice.Busy || notice == SubtitleNotice.MsoneBusy) {
                    add(Line("retry", L.commonTryAgain, icon = TmIcons.Refresh, group = Group.Online) { attempt++ })
                }
                // Nothing found online: a file of the viewer's own is the way on, where there is a
                // picker. For MSone also whenever it cannot search, paused or stopped included.
                val pick = host?.pickSubtitleFile
                if (shown.hits.isEmpty() && (notice == null || msone) && pick != null) {
                    add(Line("file", if (msone) L.msonePickFile else L.playerLoadSubtitleFile, icon = TmIcons.Folder, group = Group.Online) {
                        close()
                        pick()
                    })
                }
            }
            val focusKey = lines.firstOrNull { it.enabled }?.key
            Lines(lines, firstFocus, focusKey)
            // The remote goes to the first result that can be fetched, or to Close when there is
            // none, so it never sits on nothing.
            FocusOnOpen(if (focusKey != null) firstFocus else closeFocus, key = shown to focusKey)
        }
    }

    // ---- drawing ------------------------------------------------------------------------------

    /**
     * [lines] in a column that scrolls inside the panel ([ChoiceList]); the one keyed [focusKey]
     * carries [firstFocus]. A Column rather than a lazy list, so a refresh after an in-place line
     * keeps the scroll where the viewer left it and the focused line is always composed.
     *
     * Each group after the first opens with its heading (Timing, Look), or, for the one line
     * that needs no heading (Search online, Try again), with the same room and no words.
     */
    @Composable
    private fun androidx.compose.foundation.layout.ColumnScope.Lines(
        lines: List<Line>,
        firstFocus: FocusRequester,
        focusKey: Any?,
        onInPlace: (() -> Unit)? = null,
        /** Bumped by every in-place change, so the touch controls redraw what they read from the host. */
        version: Int = 0,
    ) {
        val (choose, adjust) = lines.partition { it.group == Group.Tracks || it.group == Group.Online }
        // On a touch screen the timing and the look are controls rather than lines: one stepper
        // for Earlier and Later, the size and position as segments, the box as a switch. Six
        // look-alike lines that each nudged or cycled something read as a puzzle under a finger.
        val touchAdjust: (@Composable () -> Unit)? = if (isTouch() && onInPlace != null && adjust.isNotEmpty()) {
            { TouchAdjust(timing = adjust.any { it.group == Group.Timing }, look = adjust.any { it.group == Group.Look }, version, onInPlace) }
        } else {
            null
        }
        // A landscape phone sets the tracks (and Search online) beside the timing and the look,
        // each half scrolling on its own, rather than one long list three lines of it at a time.
        if (LocalChoiceWide.current && choose.isNotEmpty() && adjust.isNotEmpty()) {
            ChoiceColumns(
                start = { LineRows(choose, firstFocus, focusKey) },
                end = { if (touchAdjust != null) touchAdjust() else LineRows(adjust, firstFocus, focusKey) },
            )
        } else if (touchAdjust != null) {
            ChoiceList {
                LineRows(choose, firstFocus, focusKey)
                touchAdjust()
            }
        } else {
            ChoiceList { LineRows(lines, firstFocus, focusKey) }
        }
    }

    /**
     * The timing and, for subtitles, the look, as a touch screen draws them. Each change acts at
     * once on the video behind and redraws the figures here through [onInPlace].
     */
    @Composable
    private fun TouchAdjust(timing: Boolean, look: Boolean, version: Int, onInPlace: () -> Unit) {
        val host = host ?: return
        // What the host holds is not Compose state: reading [version] is what redraws the figure
        // and the ticked segment after a press, rather than only when the picker opens again.
        @Suppress("UNUSED_EXPRESSION") version
        val subtitles = trackType == C.TRACK_TYPE_TEXT
        if (timing) {
            ChoiceSection(L.tracksSectionTiming)
            val delays = host.syncDelaysNow()
            val ms = if (subtitles) delays.subtitleMs else delays.audioMs
            fun nudge(direction: Int) {
                host.stepDelay(trackType, direction)
                onInPlace()
            }
            // Earlier and Later either side of where the timing stands now, so a press shows its
            // answer between the two buttons; Reset sits under the figure once there is one.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = ADJUST_PAD).padding(top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { nudge(-1) }, modifier = Modifier.weight(1f).height(48.dp)) {
                    M3Icon(TmIcons.ChevronLeft, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    M3Text(L.tracksEarlier, maxLines = 1)
                }
                Column(Modifier.width(112.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    M3Text(SyncDelays.label(ms), style = M3Theme.typography.titleLarge, color = Tone.text, maxLines = 1)
                    if (ms != 0L) {
                        TextButton(onClick = { nudge(0) }, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                            M3Text(L.tracksResetTiming, maxLines = 1)
                        }
                    } else {
                        M3Text(L.tracksDelayNone, style = M3Theme.typography.bodySmall, color = Tone.muted, maxLines = 2, textAlign = TextAlign.Center)
                    }
                }
                OutlinedButton(onClick = { nudge(1) }, modifier = Modifier.weight(1f).height(48.dp)) {
                    M3Text(L.tracksLater, maxLines = 1)
                    Spacer(Modifier.width(4.dp))
                    M3Icon(TmIcons.ChevronRight, contentDescription = null)
                }
            }
        }
        if (look) {
            val style = host.subtitleStyleNow()
            fun change(next: SubtitleStyle) {
                host.changeSubtitleStyle(next)
                onInPlace()
            }
            ChoiceSection(L.tracksSectionLook)
            SegmentsLabel(L.tracksSubtitleSize)
            Segments(SubtitleSize.entries.map { it.label }, SubtitleSize.entries.indexOf(style.size)) {
                change(host.subtitleStyleNow().copy(size = SubtitleSize.entries[it]))
            }
            SegmentsLabel(L.tracksSubtitlePosition)
            Segments(SubtitlePosition.entries.map { it.label }, SubtitlePosition.entries.indexOf(style.position)) {
                change(host.subtitleStyleNow().copy(position = SubtitlePosition.entries[it]))
            }
            val boldTick = com.tmplayer.ui.components.rememberToggleTick()
            SegmentsLabel(L.subtitlesColour)
            ColourSwatches(style.colour) { change(host.subtitleStyleNow().copy(colour = it)) }
            SegmentsLabel(L.tracksBackgroundBox)
            Segments(SubtitleBox.entries.map { it.label }, SubtitleBox.entries.indexOf(style.boxLevel)) {
                change(host.subtitleStyleNow().copy(boxLevel = SubtitleBox.entries[it]))
            }
            // The edge is for text without a box; with one it has nothing to do, and says so.
            if (!style.box) {
                SegmentsLabel(L.subtitlesEdge)
                Segments(SubtitleEdge.entries.map { it.label }, SubtitleEdge.entries.indexOf(style.edge)) {
                    change(host.subtitleStyleNow().copy(edge = SubtitleEdge.entries[it]))
                }
            }
            SegmentsLabel(L.subtitlesFace)
            Segments(SubtitleFace.entries.map { it.label }, SubtitleFace.entries.indexOf(style.face)) {
                change(host.subtitleStyleNow().copy(face = SubtitleFace.entries[it]))
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .toggleable(value = style.bold, role = Role.Switch) { on -> boldTick(on); change(host.subtitleStyleNow().copy(bold = on)) }
                    .padding(horizontal = ADJUST_PAD),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                M3Text(L.subtitlesWeightBold, style = M3Theme.typography.bodyLarge, color = Tone.text, modifier = Modifier.weight(1f))
                Switch(checked = style.bold, onCheckedChange = null)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = ADJUST_PAD).padding(bottom = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { change(SubtitleStyle()) }, enabled = !style.isDefault) {
                    M3Icon(TmIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    M3Text(L.subtitlesDefaultLook, maxLines = 1)
                }
            }
        }
    }

    /**
     * The five colours as round swatches, each named for a screen reader and ringed when chosen:
     * a colour is quicker to pick by sight than by its name.
     */
    @Composable
    private fun ColourSwatches(selected: SubtitleColour, onPick: (SubtitleColour) -> Unit) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ADJUST_PAD).padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SubtitleColour.entries.forEach { colour ->
                val chosen = colour == selected
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .then(
                            if (chosen) {
                                Modifier.border(3.dp, Tone.accent, CircleShape)
                            } else {
                                Modifier.border(1.dp, Tone.muted.copy(alpha = 0.5f), CircleShape)
                            },
                        )
                        .padding(5.dp)
                        .clip(CircleShape)
                        .background(androidx.compose.ui.graphics.Color(colour.argb))
                        .selectable(selected = chosen, role = Role.RadioButton) { onPick(colour) }
                        .semantics { contentDescription = colour.label },
                    contentAlignment = Alignment.Center,
                ) {
                    if (chosen) M3Icon(TmIcons.Check, contentDescription = null, tint = androidx.compose.ui.graphics.Color.Black, modifier = Modifier.size(20.dp))
                }
            }
        }
    }

    /** The name over a row of [Segments]. */
    @Composable
    private fun SegmentsLabel(title: String) {
        M3Text(
            title,
            style = M3Theme.typography.bodyMedium,
            color = Tone.muted,
            modifier = Modifier.padding(horizontal = ADJUST_PAD).padding(top = 8.dp, bottom = 8.dp),
        )
    }

    /** One of a few, as Material's segmented buttons: every choice in sight, one tap each. */
    @Composable
    private fun Segments(labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
        val segmentTick = com.tmplayer.ui.components.rememberStepTick()
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = ADJUST_PAD).padding(bottom = 12.dp)) {
            labels.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = index == selected,
                    onClick = { if (index != selected) segmentTick(); onPick(index) },
                    shape = SegmentedButtonDefaults.itemShape(index, labels.size),
                    label = { M3Text(label, maxLines = 1) },
                )
            }
        }
    }

    /** [lines] one under another, each group after the first opened by its heading or a gap. */
    @Composable
    private fun LineRows(lines: List<Line>, firstFocus: FocusRequester, focusKey: Any?) {
        lines.forEachIndexed { index, line ->
            val heading = line.group.heading()
            // A column that starts on Timing (the right half of a landscape phone) still names it.
            if (index == 0 && heading != null) {
                ChoiceSection(heading)
            } else if (index > 0 && lines[index - 1].group != line.group) {
                if (heading != null) ChoiceSection(heading) else Spacer(Modifier.height(GROUP_GAP))
            }
            ChoiceLine(
                title = line.title,
                detail = line.detail,
                selected = line.selected,
                icon = line.icon,
                enabled = line.enabled,
                modifier = if (line.key == focusKey) Modifier.focusRequester(firstFocus) else Modifier,
                onClick = line.onClick,
            )
        }
    }

    // ---- naming the tracks --------------------------------------------------------------------

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
     * What tells a subtitle track apart from another in the same language: the script or region
     * its tag names (Simplified, Traditional, Brazil), Forced, SDH or CC, and the kind of file
     * (PGS pictures, SRT, ASS). A file with three Chinese tracks by one encoder otherwise listed
     * three identical rows. Empty for sound, whose name already carries its format and channels.
     */
    private fun tags(format: Format): List<String> {
        if (trackType != C.TRACK_TYPE_TEXT) {
            return listOfNotNull(Translator.messages.formatter.trackLanguageVariant(format.language))
        }
        return buildList {
            Translator.messages.formatter.trackLanguageVariant(format.language)?.let(::add)
            if (format.selectionFlags and C.SELECTION_FLAG_FORCED != 0) add(L.tracksForced)
            when {
                format.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0 -> add("SDH")
                format.roleFlags and C.ROLE_FLAG_CAPTION != 0 -> add("CC")
            }
            subtitleFormat(format)?.let(::add)
        }
    }

    /** The subtitle file's kind, from its own MIME type or, once Media3 has parsed it, its codecs. */
    private fun subtitleFormat(format: Format): String? {
        val id = "${format.sampleMimeType.orEmpty()} ${format.codecs.orEmpty()}".lowercase(Locale.ROOT)
        return SUBTITLE_FORMATS.firstOrNull { (mime, _) -> id.contains(mime) }?.second
    }

    /**
     * Tracks that still read the same, name and tags alike, are told apart by their number
     * among the tracks of their kind, as a last resort.
     */
    private fun numberNamesakes(options: List<Option>): List<Option> {
        val seen = options.groupingBy { it.label to it.detail }.eachCount()
        var number = 0
        return options.map { option ->
            if (option.group == null) return@map option
            number++
            if ((seen[option.label to option.detail] ?: 0) < 2) return@map option
            val tag = L.tracksTrackNumber(number.toString())
            Option(option.group, option.trackIndex, option.label, listOfNotNull(option.detail, tag).joinToString("  ·  "))
        }
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

        /** The room that opens a group without a heading: about what a heading's top takes. */
        private val GROUP_GAP = 8.dp

        /** The touch controls' inset, the same 24 dp the lines carry, so they line up with the radios. */
        private val ADJUST_PAD = 24.dp
        private const val ARG_ONLINE = "online"
        private const val TAG = "track_picker"
        private const val BACK_STACK = "track_picker"

        // Spelled several ways depending on whether the id came from the MP4 sample entry or
        // from the MIME type, so both are matched.
        private val DOLBY = listOf("ac-3", "ac3", "ec-3", "eac3")
        private val DTS = listOf("dts", "dca")

        /** Subtitle MIME types (or codecs) and the name a viewer knows each by, most specific first. */
        private val SUBTITLE_FORMATS = listOf(
            "application/pgs" to "PGS",
            "application/vobsub" to "VobSub",
            "application/dvbsubs" to "DVB",
            "application/x-subrip" to "SRT",
            "text/x-ssa" to "ASS",
            "text/vtt" to "WebVTT",
            "application/ttml+xml" to "TTML",
            "application/cea-608" to "CEA-608",
            "application/cea-708" to "CEA-708",
            "application/x-quicktime-tx3g" to "TX3G",
        )

        /** Whether the picker is up, on either page. */
        fun isOpen(fm: FragmentManager): Boolean = fm.findFragmentByTag(TAG) != null

        /**
         * Opens the picker for [trackType] over [container]; with [online] straight on the
         * subtitles' "Search online" results, which Back then closes.
         */
        fun show(fm: FragmentManager, container: Int, trackType: Int, online: Boolean = false) {
            if (isOpen(fm) || fm.isStateSaved) return
            val picker = TrackPickerFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_TRACK_TYPE, trackType)
                    putBoolean(ARG_ONLINE, online)
                }
            }
            fm.beginTransaction().add(container, picker, TAG).addToBackStack(BACK_STACK).commit()
        }
    }
}
