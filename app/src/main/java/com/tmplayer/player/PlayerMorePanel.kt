package com.tmplayer.player

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.fragment.app.FragmentActivity
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.R
import com.tmplayer.data.Bookmark
import com.tmplayer.i18n.Translator
import com.tmplayer.ui.components.ChoiceLine
import com.tmplayer.ui.components.ChoiceList
import com.tmplayer.ui.components.ChoicePage
import com.tmplayer.ui.components.ChoiceSheet
import com.tmplayer.ui.components.FocusOnOpen
import com.tmplayer.ui.components.LocalChoicePageHost
import com.tmplayer.ui.components.PhoneSheet
import com.tmplayer.ui.components.TvChoiceRow
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.components.keepScrollInSheet
import com.tmplayer.ui.components.rememberLessMotion
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.LocalOnFloating
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.floatingBorder
import com.tmplayer.ui.theme.focusRing

/**
 * What the More panel reads from the player and hands back to it. Every read is of state the
 * player already holds, so opening the panel asks nothing of TDLib, the disk or the network.
 */
interface PlayerMoreHost {
    /** A television: [tvEntries] are the lines, and the panel is always the side panel. */
    val tv: Boolean
    fun title(): String

    /** Whether the player has itself asked for the screen sideways (see sheetGoesSideways). */
    fun sideways(): Boolean
    fun phoneEntries(): List<PhoneMenuEntry>
    fun tvEntries(): List<PlayerMenuEntry>
    fun shape(): VideoScale
    fun speed(): Float
    fun skipSilence(): Boolean
    fun stereo(): Stereo3d
    fun stereoTagged(): Stereo3d.Layout?
    fun stereoDetail(): String?
    fun volumeBoost(): Boolean
    fun nightMode(): Boolean
    fun evenVolume(): Boolean
    fun sleepDetail(): String?
    fun sleepChoice(): Int?
    fun loop(): AbLoop?
    fun chapter(): String?
    fun watched(): Boolean

    /** The video's bookmarks and what to do with them, or null for a video with no message. */
    fun bookmarks(): BookmarkSheetActions?

    /** The playback details' heading and lines, or null before there is a player. */
    fun playbackDetails(): Pair<String, List<String>>?
    fun onPhoneEntry(entry: PhoneMenuEntry)
    fun onTvEntry(entry: PlayerMenuEntry)
    fun setShape(scale: VideoScale)
    fun setSpeed(speed: Float)
    fun setSkipSilence(on: Boolean)

    /** Answers with why 3D could not come on (or null) and the state in force. */
    fun setStereo(next: Stereo3d): Pair<String?, Stereo3d>
    fun setSleep(minutes: Int?)

    /** The panel has been asked to close: the controls let go, the remote goes back to ⋮. */
    fun closed()
}

/**
 * The player's More options (HX16), on the phone and the television alike: one press of ⋮ and
 * the whole list is up, each line with its value, and its pages (Picture shape, Speed, Sleep
 * timer, 3D, Bookmarks, the details, the remote's keys) open inside the same panel under a Back
 * line. It replaced a popup menu anchored to ⋮ whose lines opened a second window, and the
 * television's menu in the middle of the screen.
 *
 * Docked to the end edge (the left in a right-to-left language), full height, 360 dp across and
 * 400 dp on a television, sliding in over [Motion.Fast] (a fade with less motion asked for). A
 * phone held upright has no room beside the picture, so there it is the phone's bottom sheet at
 * full width, the same pages inside. Turning the phone with it open moves it between the two and
 * keeps the page.
 *
 * Drawn in Compose over the views player in a [ComposeView] of its own, which composes nothing
 * and is gone from layout while the panel is shut, so a 1 GB stick runs nothing for it then. While
 * it is up the player sends it every key and touch (PlayerActivity), and the remote's focus stays
 * inside it.
 */
class PlayerMorePanel(
    activity: FragmentActivity,
    root: ViewGroup,
    private val host: PlayerMoreHost,
) {
    private sealed interface Page {
        data object Main : Page
        data object Shape : Page
        data object Speed : Page
        data object Stereo : Page
        data object Sleep : Page
        data object Keys : Page
        class Details(val title: String, val lines: List<String>) : Page
        class Bookmarks(val actions: BookmarkSheetActions) : Page
        class BookmarkActions(val mark: Bookmark, val actions: BookmarkSheetActions) : Page

        /** A note for a new bookmark at [positionMs] ([editing] null), or for [editing]. */
        class Note(val positionMs: Long, val editing: Bookmark?, val actions: BookmarkSheetActions, val fromList: Boolean) : Page
    }

    /** The page shown, or null while the panel is shut and nothing is composed. */
    private var page by mutableStateOf<Page?>(null)

    /** In and out; a new one each opening, so every opening slides in. */
    private var shown by mutableStateOf(MutableTransitionState(false))

    /** Bumped after a line that leaves the panel up, so the lines read the player again. */
    private var version by mutableIntStateOf(0)

    /** The main page's line that opened the page shown, which the remote returns to on Back. */
    private var openedFrom: Kind? = null

    /** What the system Back does on the page shown (a page's own step back), set by the page. */
    private var pageBack: (() -> Unit)? = null

    private val view = ComposeView(activity).apply {
        // Not named Content: inside apply that resolves to ComposeView.Content, which draws
        // this same lambda again (the TV player died of a stack overflow that way once).
        setContent { TMPlayerTheme { PanelRoot() } }
        visibility = View.GONE
    }

    init {
        root.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** True from the press of ⋮ until the panel is asked to close. */
    val isOpen: Boolean get() = page != null && shown.targetState

    /** Opens the panel on its main page. */
    fun open() {
        if (isOpen) return
        openedFrom = null
        page = Page.Main
        shown = MutableTransitionState(false).apply { targetState = true }
        view.visibility = View.VISIBLE
        view.bringToFront()
    }

    /** Opens it straight on the shapes' page, Back leading to the list (the render tests' view). */
    internal fun openOnShapes() {
        open()
        openedFrom = Kind.PictureShape
        page = Page.Shape
    }

    /** Closes it, sliding out; the player hears of it at once, not after the slide. */
    fun close() {
        if (!isOpen) return
        shown.targetState = false
        host.closed()
    }

    /** Closes it with no slide: picture in picture is coming, where there is room for nothing. */
    fun closeNow() {
        val wasOpen = isOpen
        finish()
        if (wasOpen) host.closed()
    }

    private fun finish() {
        page = null
        pageBack = null
        view.visibility = View.GONE
    }

    private fun refresh() {
        version++
    }

    private fun back() {
        val target = openedFrom
        page = Page.Main
        openedFrom = target
    }

    @Composable
    private fun PanelRoot() {
        val current = page ?: return
        val state = shown
        // Gone from layout once the slide out has ended.
        if (!state.targetState && state.isIdle && !state.currentState) {
            SideEffect { if (page != null && !shown.targetState) finish() }
        }
        BackHandler(enabled = state.targetState) {
            val step = pageBack
            if (current is Page.Main || step == null) close() else step()
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val side = host.tv || host.sideways() || maxWidth >= SIDE_FROM
            if (side) SidePanel(current, state, maxWidth) else BottomSheet(current, state)
        }
    }

    @Composable
    private fun SidePanel(current: Page, state: MutableTransitionState<Boolean>, room: androidx.compose.ui.unit.Dp) {
        val still = rememberLessMotion()
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val width = min(if (host.tv) TV_WIDTH else PHONE_WIDTH, room * 0.9f)
        val fade = tween<Float>(Motion.Fast)
        val slide = tween<androidx.compose.ui.unit.IntOffset>(Motion.Fast)
        AnimatedVisibility(visibleState = state, enter = EnterTransition.None, exit = ExitTransition.None) {
            Box(Modifier.fillMaxSize()) {
                // Outside the panel: a light shade, so the video playing still reads, and a tap there closes.
                Box(
                    Modifier
                        .fillMaxSize()
                        .animateEnterExit(enter = fadeIn(fade), exit = fadeOut(fade))
                        .background(Color.Black.copy(alpha = SCRIM_ALPHA))
                        .pointerInput(Unit) { detectTapGestures { close() } },
                )
                val shape = RoundedCornerShape(topStart = Corner.ExtraLarge, bottomStart = Corner.ExtraLarge)
                Column(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .animateEnterExit(
                            enter = if (still) fadeIn(fade) else slideInHorizontally(slide) { if (rtl) -it else it },
                            exit = if (still) fadeOut(fade) else slideOutHorizontally(slide) { if (rtl) -it else it },
                        )
                        .width(width)
                        .fillMaxHeight()
                        .clip(shape)
                        .background(FloatingTone.sheet)
                        .border(floatingBorder(), shape)
                        // A tap on the panel's own blank space is not a tap outside it.
                        .pointerInput(Unit) { detectTapGestures { } }
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End))
                        .imePadding()
                        .then(trapFocus()),
                ) {
                    Pages(current)
                }
            }
        }
    }

    /** The remote stays inside the panel while it is up; Left from a line does not reach the row. */
    @Composable
    private fun trapFocus(): Modifier =
        if (isTouch()) Modifier else Modifier.focusProperties { onExit = { if (shown.targetState) cancelFocusChange() } }.focusGroup()

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun BottomSheet(current: Page, state: MutableTransitionState<Boolean>) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        // Closed from the panel (a line chosen, ⋮ again): the sheet goes down before it goes.
        LaunchedEffect(state.targetState) {
            if (!state.targetState) {
                runCatching { sheetState.hide() }
                finish()
            }
        }
        PhoneSheet(onDismissRequest = { if (isOpen) close() else finish() }, sheetState = sheetState) {
            // The sheet is a window of its own, so its Back reaches here rather than the panel's.
            BackHandler(enabled = current !is Page.Main) { pageBack?.invoke() ?: back() }
            Column(Modifier.fillMaxWidth().keepScrollInSheet().imePadding()) { Pages(current) }
        }
    }

    @Composable
    private fun ColumnScope.Pages(current: Page) {
        AnimatedContent(
            targetState = current,
            modifier = Modifier.weight(1f, fill = false),
            transitionSpec = {
                val fade = tween<Float>(Motion.Fast)
                fadeIn(fade) togetherWith fadeOut(fade)
            },
            label = "morePage",
            contentKey = { it::class },
        ) { shownPage ->
            Column(Modifier.fillMaxWidth()) {
                if (shownPage is Page.Main) {
                    SideEffect { pageBack = null }
                    MainPage()
                } else {
                    CompositionLocalProvider(LocalChoicePageHost provides { choice -> PageFrame(choice) }) {
                        SubPage(shownPage)
                    }
                }
            }
        }
    }

    // ---- the main page ------------------------------------------------------------------------

    /** Every line the panel can carry: the phone's and the television's entries share these names. */
    private enum class Kind {
        LockScreen, PictureInPicture, PlaybackDetails, StartOver, PictureShape, Stereo, Speed,
        VolumeBoost, NightMode, EvenVolume, SleepTimer, AbLoop, NextChapter, PreviousChapter,
        NextFrame, PreviousFrame, SaveFrame, AddBookmark, Bookmarks, OpenInAnotherApp,
        LoadSubtitleFile, SaveToDownloads, MarkWatched, RemoteKeys,
    }

    private class Line(val kind: Kind, val label: String, val icon: ImageVector, val detail: String?)

    @Composable
    private fun ColumnScope.MainPage() {
        val s = LocalStrings.current
        val touch = isTouch()
        // Read so a line that leaves the panel up shows its new value.
        @Suppress("UNUSED_VARIABLE") val seen = version
        val kinds = if (host.tv) host.tvEntries().map { Kind.valueOf(it.name) } else host.phoneEntries().map { Kind.valueOf(it.name) }
        val bookmarks = remember { host.bookmarks() }
        val count = bookmarks?.bookmarks?.collectAsState()?.value?.size ?: 0
        val lines = kinds.map { line(it, count) }
        val requesters = remember { mutableMapOf<Kind, FocusRequester>() }
        Header(title = s.playerMoreOptions, subtitle = host.title().ifBlank { null })
        Column(Modifier.weight(1f, fill = false).fillMaxWidth().padding(horizontal = PAD)) {
            ChoiceList {
                lines.forEachIndexed { index, line ->
                    val requester = requesters.getOrPut(line.kind) { FocusRequester() }
                    ChoiceLine(
                        title = line.label,
                        detail = line.detail,
                        icon = line.icon,
                        modifier = Modifier.focusRequester(requester),
                    ) { choose(line.kind) }
                    // Lock the screen heads the list in a group of its own.
                    if (index == 0 && line.kind == Kind.LockScreen && lines.size > 1) {
                        HorizontalDivider(
                            color = Tone.outline,
                            modifier = Modifier.padding(vertical = if (touch) 4.dp else 6.dp),
                        )
                    }
                }
            }
        }
        val target = openedFrom?.let { requesters[it] } ?: lines.firstOrNull()?.let { requesters[it.kind] }
        if (target != null) FocusOnOpen(target, key = openedFrom)
    }

    @Composable
    private fun line(kind: Kind, bookmarkCount: Int): Line {
        val s = LocalStrings.current
        val volume = ImageVector.vectorResource(R.drawable.ic_volume)
        return when (kind) {
            Kind.LockScreen -> Line(kind, s.playerLockScreen, TmIcons.Lock, if (host.tv) s.playerHoldOkToUnlock else null)
            Kind.PictureInPicture -> Line(kind, s.playerPictureInPicture, ImageVector.vectorResource(R.drawable.ic_pip), null)
            Kind.PlaybackDetails -> Line(kind, s.playerPlaybackDetails, TmIcons.Info, null)
            Kind.StartOver -> Line(kind, s.playerStartOver, TmIcons.Refresh, null)
            Kind.PictureShape -> Line(kind, s.playerPictureShape, TmIcons.AspectRatio, host.shape().label)
            Kind.Stereo -> Line(kind, s.playerStereo, TmIcons.Visibility, host.stereoDetail() ?: s.commonOff)
            Kind.Speed -> Line(kind, s.playerSpeed, ImageVector.vectorResource(R.drawable.ic_speed), PlaybackSpeed.label(host.speed()))
            Kind.VolumeBoost -> Line(kind, s.playerVolumeBoost, volume, if (host.volumeBoost()) s.playerVolumeBoostOnDetail else s.commonOff)
            Kind.NightMode -> Line(kind, s.playerNightMode, TmIcons.VolumeDown, if (host.nightMode()) s.playerNightModeOnDetail else s.commonOff)
            Kind.EvenVolume -> Line(kind, s.playerEvenVolume, volume, if (host.evenVolume()) s.playerEvenVolumeOnDetail else s.commonOff)
            Kind.SleepTimer -> Line(kind, s.playerSleepTimer, TmIcons.Clock, host.sleepDetail() ?: s.commonOff)
            Kind.AbLoop -> {
                val loop = host.loop()
                val clock = Translator.messages.formatter::clock
                Line(
                    kind,
                    PlayerMenu.loopLabel(loop),
                    TmIcons.Repeat,
                    loop?.let { marked ->
                        marked.endMs?.let { s.playerRepeating(clock(marked.startMs), clock(it)) }
                            ?: s.playerLoopFromDetail(clock(marked.startMs))
                    },
                )
            }
            // The chapter playing under the step, so a step is a choice and not a guess.
            Kind.NextChapter -> Line(kind, s.playerChapterNext, TmIcons.SkipNext, host.chapter())
            Kind.PreviousChapter -> Line(kind, s.playerChapterPrevious, TmIcons.SkipPrevious, null)
            Kind.NextFrame -> Line(kind, s.playerFrameNext, TmIcons.ChevronRight, null)
            Kind.PreviousFrame -> Line(kind, s.playerFramePrevious, TmIcons.ChevronLeft, null)
            Kind.SaveFrame -> Line(kind, s.playerSaveFrame, TmIcons.Image, null)
            Kind.AddBookmark -> Line(kind, s.playerAddBookmark, TmIcons.Add, null)
            Kind.Bookmarks -> Line(kind, s.playerBookmarks, TmIcons.Bookmark, bookmarkCount.takeIf { it > 0 }?.let { s.playerBookmarksCount(it) })
            Kind.OpenInAnotherApp -> Line(kind, s.playerOpenInAnotherApp, TmIcons.OpenExternal, null)
            Kind.LoadSubtitleFile -> Line(kind, s.playerLoadSubtitleFile, TmIcons.Subtitles, null)
            Kind.SaveToDownloads -> Line(kind, s.playerSaveToDownloads, TmIcons.Download, s.playerSaveToDownloadsDetail)
            Kind.MarkWatched -> if (host.watched()) {
                Line(kind, s.playerMarkUnwatched, TmIcons.Close, s.playerMarkUnwatchedDetail)
            } else {
                Line(kind, s.playerMarkWatched, TmIcons.Check, s.playerMarkWatchedDetail)
            }
            Kind.RemoteKeys -> Line(kind, s.playerRemoteKeys, TmIcons.Remote, s.playerRemoteKeysDetail)
        }
    }

    /**
     * A line chosen. A page opens in place; a switch or a frame step acts and leaves the panel up
     * with the new value; anything else closes the panel first, so what it opens (the lock, the
     * corner window, another app) is on top.
     */
    private fun choose(kind: Kind) {
        val pageFor: Page? = when (kind) {
            Kind.PictureShape -> Page.Shape
            Kind.Speed -> Page.Speed
            Kind.Stereo -> Page.Stereo
            Kind.SleepTimer -> Page.Sleep
            Kind.RemoteKeys -> Page.Keys
            Kind.Bookmarks -> host.bookmarks()?.let { Page.Bookmarks(it) }
            Kind.PlaybackDetails -> host.playbackDetails()?.let { (title, lines) -> Page.Details(title, lines) }
            // The phone asks for a note first, at the moment the line was chosen; the television
            // saves at once, without the keyboard.
            Kind.AddBookmark -> if (host.tv) null else host.bookmarks()?.let { Page.Note(it.position(), null, it, fromList = false) }
            else -> null
        }
        if (pageFor != null) {
            openedFrom = kind
            page = pageFor
            return
        }
        val staysOpen = kind in STAYS_OPEN
        if (!staysOpen) close()
        if (host.tv) {
            runCatching { PlayerMenuEntry.valueOf(kind.name) }.getOrNull()?.let(host::onTvEntry)
        } else {
            runCatching { PhoneMenuEntry.valueOf(kind.name) }.getOrNull()?.let(host::onPhoneEntry)
        }
        if (staysOpen) refresh()
    }

    // ---- the pages ----------------------------------------------------------------------------

    @Composable
    private fun SubPage(current: Page) {
        @Suppress("UNUSED_VARIABLE") val seen = version
        when (current) {
            Page.Main -> Unit
            // A shape or a 3D choice takes effect at once and the page stays, beside the picture
            // it changes; a speed or a sleep length is the end of the errand and closes.
            Page.Shape -> ShapeSheet(
                current = host.shape(),
                onPick = { host.setShape(it); refresh() },
                onDismiss = ::back,
                onClose = ::close,
            )
            Page.Speed -> {
                var speed by remember { mutableStateOf(host.speed()) }
                var skipping by remember { mutableStateOf(host.skipSilence()) }
                SpeedSheet(
                    current = speed,
                    onPick = { close(); host.setSpeed(it) },
                    onDismiss = ::back,
                    onClose = ::close,
                    skipSilence = skipping,
                    onSkipSilence = { on -> skipping = on; host.setSkipSilence(on) },
                    onFine = { next -> speed = next; host.setSpeed(next) },
                )
            }
            Page.Stereo -> {
                var stereo by remember { mutableStateOf(host.stereo()) }
                var refused by remember { mutableStateOf<String?>(null) }
                StereoSheet(
                    current = stereo,
                    tagged = host.stereoTagged(),
                    refused = refused,
                    onChange = {
                        val (why, now) = host.setStereo(it)
                        refused = why
                        stereo = now
                    },
                    onDismiss = ::back,
                    onClose = ::close,
                )
            }
            Page.Sleep -> SleepSheet(
                running = host.sleepDetail(),
                chosen = host.sleepChoice(),
                onPick = { close(); host.setSleep(it) },
                onDismiss = ::back,
                onClose = ::close,
            )
            Page.Keys -> RemoteKeysPage(onBack = ::back)
            is Page.Details -> PlaybackDetailsSheet(current.title, current.lines, onClose = ::back)
            is Page.Bookmarks -> {
                val actions = current.actions
                val list by actions.bookmarks.collectAsState()
                BookmarksSheet(
                    bookmarks = list,
                    onAdd = {
                        val at = actions.position()
                        if (host.tv) actions.add(at, "") else page = Page.Note(at, null, actions, fromList = true)
                    },
                    onPick = { page = Page.BookmarkActions(it, actions) },
                    onDismiss = ::back,
                    onClose = ::close,
                )
            }
            is Page.BookmarkActions -> {
                val actions = current.actions
                val toList = { page = Page.Bookmarks(actions) }
                BookmarkActionsSheet(
                    mark = current.mark,
                    onPlay = { close(); actions.jump(current.mark) },
                    onNote = { page = Page.Note(current.mark.positionMs, current.mark, actions, fromList = true) },
                    onDelete = { actions.delete(current.mark); toList() },
                    onDismiss = toList,
                    onClose = ::close,
                )
            }
            is Page.Note -> {
                val actions = current.actions
                val done = { if (current.fromList) page = Page.Bookmarks(actions) else close() }
                val s = LocalStrings.current
                BookmarkNoteSheet(
                    title = s.playerBookmarkNoteTitle(Translator.messages.formatter.clock(current.positionMs)),
                    initial = current.editing?.note.orEmpty(),
                    saveLabel = if (current.editing != null) s.playerBookmarkSaveNote else s.playerBookmarkSave,
                    onSave = { note ->
                        val editing = current.editing
                        if (editing != null) actions.rename(editing, note) else actions.add(current.positionMs, note)
                        done()
                    },
                    onDismiss = { if (current.fromList) page = Page.Bookmarks(actions) else back() },
                    onClose = ::close,
                )
            }
        }
    }

    /** The panel's heading: what it is, the video under it, and on a touch screen an X. */
    @Composable
    private fun Header(title: String, subtitle: String?) {
        val s = LocalStrings.current
        val touch = isTouch()
        Row(
            Modifier.fillMaxWidth().padding(start = PAD, end = if (touch) 8.dp else PAD, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = Tone.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (touch) CloseButton(s.commonClose)
        }
    }

    @Composable
    private fun CloseButton(label: String) {
        com.tmplayer.ui.components.IconTip(label, side = com.tmplayer.ui.components.TipSide.Below) {
            IconButton(onClick = ::close) { M3Icon(TmIcons.Close, contentDescription = label, tint = Tone.text) }
        }
    }

    /**
     * A picker drawn as a page of the panel: its heading is the Back line (the arrow and the
     * title, as a settings panel's sub-page has it), the note under that, the lines under that.
     * On a television the Back line is a tile the remote can stand on; the remote lands on the
     * picker's own choice, or on Back when the page has none.
     */
    @Composable
    private fun PageFrame(choice: ChoicePage) {
        val s = LocalStrings.current
        val touch = isTouch()
        val backFocus = remember { FocusRequester() }
        var focusInside by remember { mutableStateOf(false) }
        SideEffect { pageBack = choice.onBack }
        Column(
            Modifier
                .fillMaxWidth()
                .onFocusChanged { focusInside = it.hasFocus },
        ) {
            val backModifier = Modifier
                .focusRequester(backFocus)
                .then(if (choice.closeFocus != null) Modifier.focusRequester(choice.closeFocus) else Modifier)
            if (touch) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    com.tmplayer.ui.components.IconTip(s.commonBack, side = com.tmplayer.ui.components.TipSide.Below) {
                        IconButton(onClick = choice.onBack, modifier = backModifier) {
                            M3Icon(TmIcons.ArrowBack, contentDescription = s.commonBack, tint = Tone.text)
                        }
                    }
                    Text(
                        choice.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = Tone.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                    CloseButton(s.commonClose)
                }
            } else {
                Column(Modifier.padding(start = PAD, end = PAD, top = 16.dp)) {
                    TvChoiceRow(title = choice.title, icon = TmIcons.ArrowBack, modifier = backModifier, onClick = choice.onBack)
                }
            }
            if (choice.note != null) {
                Text(
                    choice.note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tone.muted,
                    modifier = Modifier.padding(horizontal = PAD).padding(top = 4.dp),
                )
            }
            Spacer(Modifier.size(8.dp))
            CompositionLocalProvider(LocalOnFloating provides true) {
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().padding(horizontal = PAD), content = choice.content)
            }
        }
        if (!touch) {
            // A page with nothing of its own to stand on (the details) puts the remote on Back.
            LaunchedEffect(choice.title) {
                withFrameNanos { }
                withFrameNanos { }
                if (!focusInside) runCatching { backFocus.requestFocus() }
            }
        }
    }

    private companion object {
        /** From this width a touch screen has room for the panel beside the picture. */
        val SIDE_FROM = 600.dp
        val PHONE_WIDTH = 360.dp
        val TV_WIDTH = 400.dp

        /** The pages' side padding, which a [ChoiceList] on a phone runs its rows through. */
        val PAD = 24.dp

        /**
         * Lighter than a dialog's dim: the panel sits beside a video that keeps playing, which
         * should still read. Material's own scrim for a modal side sheet.
         */
        const val SCRIM_ALPHA = 0.32f

        /** Lines that act and leave the panel up: the switches and the frame steps. */
        val STAYS_OPEN = setOf(Kind.VolumeBoost, Kind.NightMode, Kind.EvenVolume, Kind.NextFrame, Kind.PreviousFrame)
    }
}

/**
 * The remote's keys, a page of the panel. Each row takes focus without doing anything when
 * pressed, which is what lets the D-pad walk a table taller than the screen.
 */
@Composable
private fun RemoteKeysPage(onBack: () -> Unit) {
    val s = LocalStrings.current
    val first = remember { FocusRequester() }
    ChoiceSheet(title = s.playerRemoteKeys, note = s.playerRemoteKeysClose, onDismiss = onBack) {
        ChoiceList {
            RemoteKeys.ROWS.forEachIndexed { index, (key, does) ->
                KeyRow(key, does, if (index == 0) Modifier.focusRequester(first) else Modifier)
            }
        }
        FocusOnOpen(first)
    }
}

@Composable
private fun KeyRow(key: String, does: String, modifier: Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val background by animateColorAsState(
        targetValue = if (focused) Tone.surfaceHigh else Color.Transparent,
        animationSpec = tween(140),
        label = "keyRow",
    )
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.Medium))
            .background(background)
            .focusRing(focused, RoundedCornerShape(Corner.Medium))
            .focusable(interactionSource = interactions)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(key, style = MaterialTheme.typography.titleMedium, color = Tone.text)
        Text(does, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
    }
}
