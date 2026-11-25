package com.tmplayer.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import com.tmplayer.ui.components.softVerticalEdges
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.DeviceForm
import com.tmplayer.data.HintIds
import com.tmplayer.data.SettingsMap
import com.tmplayer.data.SettingsStore
import com.tmplayer.ui.components.HintStyle
import com.tmplayer.ui.components.LocalHintSlot
import com.tmplayer.ui.components.OneTimeHint
import com.tmplayer.ui.components.PageKeys
import com.tmplayer.ui.components.ProvideHintSlot
import com.tmplayer.ui.components.dropStaleArrowRepeats
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.FocusRecipe
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import com.tmplayer.ui.theme.focusRingOnTv

/**
 * The television's Settings (WP-07): a rail of the categories on the leading side, the open
 * category's rows beside it, and a header pinned above both that says where the remote is. The
 * shape every TV user knows from the system's own settings, YouTube and Plex.
 *
 * At 960 x 540 dp, inside the safe area ([Tv.SafeH] 48, [Tv.SafeV] 27): a 48 dp header, a 232 dp
 * rail, a 24 dp gap and the pane (about 608 dp), which ends in the detail strip (WP-74): 68 dp
 * for the focused row's sentence, so a row stays one line and nothing shifts when the remote moves.
 * The rail holds 9 categories of [RAIL_ITEM] each (432 dp inside about 438), which fits at Default
 * only: Larger and Largest multiply the density, so the rail scrolls (focus brings the item into
 * view) and never leaves an item with no height that the remote can still reach (F-04).
 *
 * The pane follows the rail: a move on the rail shows that category after [SETTLE_MS], so a held
 * Down composes one page when it stops, not every page it passed. The header sits outside every
 * list (never a sticky header), so a focused row can never scroll under it.
 *
 * Keys, from the focus map in the plan: on the rail Up and Down move and stop at the ends, the far
 * side stays, the pane side (Right, Left in a right to left layout) and OK enter the pane on its
 * first row, Back leaves Settings. In the pane an arrow that would leave it goes nowhere except
 * towards the rail, which lands on the open category's item; Back goes to that item too.
 */
@Composable
internal fun TvSettingsPane(
    /** The rail's categories, in the shared order. */
    pages: List<SettingsPage>,
    /** The category the pane shows: the rail item drawn selected. */
    open: SettingsPage,
    title: (SettingsPage) -> String,
    icon: @Composable (SettingsPage) -> ImageVector,
    railFocus: Map<SettingsPage, FocusRequester>,
    /** Where the pinned header reads its section from; one per page shown. */
    breadcrumb: SettingsBreadcrumb,
    /** The page the pane shows, which may be a sub-page of [open] (Troubleshooting under Help). */
    paneTitle: String,
    /** A key that changes when the pane's content does, for its fade. */
    paneKey: Any,
    /** The rail has settled on [SettingsPage] after a move: show it, leave the remote where it is. */
    onFollow: (SettingsPage) -> Unit,
    /** Right or OK on a rail item: open it if it is not open, and put the remote on its first row. */
    onEnterPane: (SettingsPage) -> Unit,
    /** Back with the remote in the pane: a sub-page closes, otherwise the rail. */
    onBackInPane: () -> Unit,
    /** The pane's first row for an arrow that comes in from the rail. */
    landing: FocusRequester,
    /** Where the remote goes when it first arrives from outside Settings: the rail item, or a deep link's row. */
    entry: FocusRequester,
    /**
     * Whether the remote has been inside Settings yet, held by the screen so a page drawn over the
     * pane (Devices, the PIN pad) does not count as leaving: the first arrival from outside (the
     * window taking focus picks the first item it finds) goes to [entry] instead.
     */
    arrived: BooleanArray,
    /** Opens Find a setting (WP-09): the header's button, which Up from the page's first row reaches; null: none. */
    onFind: (() -> Unit)? = null,
    findFocus: FocusRequester = remember { FocusRequester() },
    /** Where the Page and Channel keys' arrival is noted and the hint about them kept; null: no hint. */
    hints: SettingsStore? = null,
    /** Quiet facts at the header's trailing end. */
    headerEnd: @Composable () -> Unit = {},
    /** The foot of the rail, after the last category: the compact version, read only (not a stop for the remote). */
    railFooter: @Composable () -> Unit = {},
    pane: @Composable () -> Unit,
) {
    var railHasFocus by remember { mutableStateOf(false) }
    // The focused row's long sentence, shown in the strip under the pane (WP-74).
    val detail = remember { SettingsDetailHolder() }
    // The rail item the remote moved to with Up or Down, and the pane following it once it stops.
    // Only an arrow on the rail moves the pane: focus arriving on an item any other way (the
    // window's first focus, Left from the pane) leaves the page as it is.
    var cursor by remember { mutableStateOf<SettingsPage?>(null) }
    val arrow = remember { BooleanArray(1) }
    LaunchedEffect(cursor) {
        val target = cursor ?: return@LaunchedEffect
        kotlinx.coroutines.delay(SETTLE_MS)
        if (railHasFocus && target != open) onFollow(target)
    }
    // Back in the pane goes to the rail (or out of a sub-page) before it leaves Settings. Composed
    // after the screen's own handlers, so it is asked first.
    BackHandler(enabled = !railHasFocus) { onBackInPane() }

    val scope = rememberCoroutineScope()
    val jumping = remember { BooleanArray(1) }
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    // The arrows in screen terms: the rail is on the leading side, so towards the pane is Right in
    // a left to right layout and Left in Arabic or Hebrew.
    val towardsPane = if (ltr) FocusDirection.Right else FocusDirection.Left
    val towardsRail = if (ltr) FocusDirection.Left else FocusDirection.Right
    val paneKeyCode = if (ltr) Key.DirectionRight else Key.DirectionLeft

    Column(
        Modifier
            .fillMaxSize()
            .onFocusChanged { if (it.hasFocus) arrived[0] = true }
            .focusProperties {
                onEnter = {
                    if (!arrived[0]) {
                        arrived[0] = true
                        runCatching { entry.requestFocus() }
                    }
                }
            }
            .focusGroup()
            .padding(horizontal = Tv.SafeH, vertical = Tv.SafeV),
    ) {
        SettingsHeader(
            title = LocalStrings.current.settingsTitle,
            crumb = paneTitle,
            breadcrumb = breadcrumb,
            onRail = railHasFocus,
            find = onFind?.let { openFind ->
                {
                    FindButton(
                        onClick = openFind,
                        requester = findFocus,
                        // Down goes into the page, Left to the rail: the arrows leave the header the way they came.
                        onDown = { runCatching { landing.requestFocus() } },
                        onTowardsRail = { runCatching { railFocus.getValue(open).requestFocus() } },
                        towardsRailKey = if (ltr) Key.DirectionLeft else Key.DirectionRight,
                    )
                }
            },
            end = headerEnd,
            syncStays = open == SettingsPage.Accounts,
        )
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Column(
                Modifier
                    .width(RAIL_WIDTH)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .testTag(SETTINGS_RAIL_TAG)
                    .onFocusChanged { railHasFocus = it.hasFocus }
                    .focusProperties {
                        // In from the pane: on the open category, wherever the row was.
                        onEnter = {
                            if (requestedFocusDirection == towardsRail) runCatching { railFocus.getValue(open).requestFocus() }
                        }
                        // The ends stop, the far side stays; only the pane side leaves.
                        onExit = {
                            if (requestedFocusDirection in ARROWS && requestedFocusDirection != towardsPane) cancelFocusChange()
                        }
                    }
                    .focusGroup()
                    .onPreviewKeyEvent { event ->
                        if (event.key == Key.DirectionUp || event.key == Key.DirectionDown) {
                            arrow[0] = event.type == KeyEventType.KeyDown
                        }
                        false
                    }
                    .dropStaleArrowRepeats(),
                verticalArrangement = Arrangement.Top,
            ) {
                pages.forEach { item ->
                    RailItem(
                        label = title(item),
                        icon = icon(item),
                        selected = item == open,
                        requester = railFocus.getValue(item),
                        tag = railTag(item),
                        paneKey = paneKeyCode,
                        onFocused = { if (arrow[0]) cursor = item },
                        onEnter = {
                            cursor = null
                            onEnterPane(item)
                        },
                    )
                }
                // The version at the foot of the rail: read only, so the remote's order is the pages'.
                railFooter()
            }
            Spacer(Modifier.width(RAIL_GAP))
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .testTag(SETTINGS_PANE_TAG)
                    // Up from the page's first row: the header's Find a setting (WP-09).
                    .onPreviewKeyEvent { event ->
                        if (onFind != null && event.key == Key.DirectionUp && breadcrumb.onFirstRow) {
                            if (event.type == KeyEventType.KeyDown) runCatching { findFocus.requestFocus() }
                            return@onPreviewKeyEvent true
                        }
                        false
                    }
                    // Page and Channel keys: section to section (WP-75).
                    .onPreviewKeyEvent { event ->
                        val dir = PageKeys.step(event.key)
                        if (dir == 0 || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        // A jump on its way swallows the repeats of a held key (one section per press).
                        if (jumping[0]) return@onPreviewKeyEvent true
                        jumping[0] = true
                        if (PageKeys.isPageOrChannel(event.key) && hints != null) {
                            scope.launch { runCatching { hints.markHintSeen(HintIds.PAGE_KEY_ARRIVED) } }
                        }
                        scope.launch {
                            try {
                                breadcrumb.sectionJump(dir)
                            } finally {
                                jumping[0] = false
                            }
                        }
                        true
                    }
                    .focusProperties {
                        // In from the rail by an arrow: the page's first row, not the nearest one.
                        onEnter = {
                            if (requestedFocusDirection == towardsPane) runCatching { landing.requestFocus() }
                        }
                        // Up from the first row and Down from the last stay; the rail side goes to the rail.
                        onExit = {
                            if (requestedFocusDirection in ARROWS && requestedFocusDirection != towardsRail) cancelFocusChange()
                        }
                    }
                    .onFocusChanged { if (!it.hasFocus) detail.text = "" }
                    .focusGroup(),
            ) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    androidx.compose.runtime.CompositionLocalProvider(LocalSettingsDetail provides detail) {
                        // The last 24 dp of the page melt into the ground: a heading never ends half cut at the strip.
                        PaneFade(
                            paneKey,
                            Modifier.fillMaxSize().softVerticalEdges(
                                ground = Tone.background,
                                atTop = { breadcrumb.state?.canScrollBackward == true },
                                atBottom = { breadcrumb.state?.canScrollForward == true },
                            ),
                        ) { pane() }
                    }
                    if (hints != null) PageKeysHint(hints, visible = !railHasFocus, Modifier.align(Alignment.BottomCenter))
                }
                DetailStrip(detail)
            }
        }
    }
}

/**
 * The pane's content fading in over [FADE_MS] when [key] changes, and not at all under Reduce
 * motion or on a low-end stick (one page composed, nothing animated). Only the new page is
 * composed: the old one is gone at once, so a TV stick never holds two pages.
 */
@Composable
private fun PaneFade(key: Any, modifier: Modifier, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val still = Motion.reduced || remember { com.tmplayer.data.LowEndProbe.isLowEnd(context) }
    val alpha = remember { Animatable(1f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(key) {
        if (first) {
            first = false
            return@LaunchedEffect
        }
        if (still) {
            alpha.snapTo(1f)
            return@LaunchedEffect
        }
        alpha.snapTo(0f)
        alpha.animateTo(1f, tween(FADE_MS))
    }
    Box(modifier.graphicsLayer { this.alpha = alpha.value }) { content() }
}

/**
 * The header pinned above Settings on a television, outside every list: "Settings" and where the
 * remote is ("Playback, Advanced"), on one 48 dp line, with [end] at the trailing side. Also the
 * About screen's, so the two read as one place.
 */
@Composable
internal fun SettingsHeader(
    title: String,
    /** The page shown. */
    crumb: String,
    /** The section of the focused row, when there is one; null for the page alone. */
    breadcrumb: SettingsBreadcrumb? = null,
    /** The remote on the rail: the page alone, no section. */
    onRail: Boolean = false,
    /** The Find a setting button, between the breadcrumb and [end] (WP-09); null on About. */
    find: (@Composable () -> Unit)? = null,
    end: @Composable () -> Unit = {},
    /** The page of the sync switch: a standing sync status stays here as a normal line, not only for its first seconds. */
    syncStays: Boolean = false,
) {
    val s = LocalStrings.current
    val section = breadcrumb?.section?.takeIf { !onRail && it.isNotBlank() }
    val where = if (section == null) crumb else s.settingsSectionOnTop(page = crumb, section = section)
    Row(
        Modifier
            .fillMaxWidth()
            .height(HEADER_HEIGHT)
            .testTag(SETTINGS_HEADER_TAG)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The app's own tile before its name (user, 2026-11-25): Settings and About say whose they are.
        com.tmplayer.ui.components.AppTile(32.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = Tone.text,
            maxLines = 1,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            where,
            style = MaterialTheme.typography.bodyMedium,
            color = Tone.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag(SETTINGS_CRUMB_TAG),
        )
        if (find != null) {
            find()
            Spacer(Modifier.width(16.dp))
        }
        // The television's one status, here as in the top bar (Settings has no bar): the same pill, to the right of Find a setting.
        val line by com.tmplayer.ui.browse.TvBarSignals.statusLine.collectAsState()
        val sync by com.tmplayer.ui.browse.TvBarSignals.statusSync.collectAsState()
        val work by com.tmplayer.ui.browse.TvBarSignals.statusWork.collectAsState()
        val connection by com.tmplayer.ui.browse.TvBarSignals.statusConnection.collectAsState()
        val standing by com.tmplayer.ui.browse.TvBarSignals.syncStanding.collectAsState()
        Box(Modifier.width(HEADER_END_WIDTH)) {
            com.tmplayer.ui.components.TvStatusPill(
                input = com.tmplayer.ui.components.TvStatusInput(line, sync, work, connection, standing = standing || syncStays),
                modifier = Modifier.align(Alignment.CenterStart).testTag(SETTINGS_STATUS_TAG),
            )
            Box(Modifier.align(Alignment.CenterEnd)) { end() }
        }
    }
}

/** The header's Find a setting (WP-09): a 48 dp TV button (the header line's height), never a rail item. OK opens the panel. */
@Composable
private fun FindButton(
    onClick: () -> Unit,
    requester: FocusRequester,
    onDown: () -> Unit,
    onTowardsRail: () -> Unit,
    towardsRailKey: Key,
) {
    com.tmplayer.ui.components.TmButton(
        onClick = onClick,
        modifier = Modifier
            .height(HEADER_HEIGHT)
            .focusRequester(requester)
            .testTag(FIND_BUTTON_TAG)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> { onDown(); true }
                    towardsRailKey -> { onTowardsRail(); true }
                    // Nothing above, and the far side holds: the header is one line.
                    Key.DirectionUp -> true
                    else -> false
                }
            },
    ) {
        Icon(com.tmplayer.ui.icons.TmIcons.Search, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(LocalStrings.current.settingsFindSetting, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/**
 * One category on the rail, the rail item role of `FocusRole`: at rest muted words; selected (the
 * pane shows it) a 3 dp accent bar on the leading edge and SemiBold in the text colour; focused the
 * focus fill and the ring. Selected and focused can show at once, the bar then in the ink on the
 * fill. A fixed [RAIL_ITEM] high, one line with an ellipsis at any font size.
 */
@Composable
private fun RailItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    requester: FocusRequester,
    tag: String,
    paneKey: Key,
    onFocused: () -> Unit,
    onEnter: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Corner.Medium)
    val ink = when {
        focused -> Tone.onFocusFill
        selected -> Tone.text
        else -> Tone.muted
    }
    val mark = if (focused) Tone.onFocusFill else Tone.accent
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = RAIL_ITEM)
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .onPreviewKeyEvent { event ->
                if (event.key == paneKey) {
                    if (event.type == KeyEventType.KeyDown) onEnter()
                    true
                } else {
                    false
                }
            }
            .clip(shape)
            .background(if (focused) Tone.focusFill else Color.Transparent)
            .drawBehind {
                if (selected) {
                    val bar = FocusRecipe.RailBar.toPx()
                    val x = if (layoutDirection == LayoutDirection.Ltr) 0f else size.width - bar
                    drawRect(mark, topLeft = Offset(x, 0f), size = Size(bar, size.height))
                }
            }
            .focusRingOnTv(focused, shape)
            .selectable(
                selected = selected,
                interactionSource = interactions,
                indication = null,
                role = Role.Tab,
                onClick = onEnter,
            )
            .testTag(tag)
            .padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        // Two lines of a 20 sp line height fit the 48 dp item, so "Appearance and language" wraps instead of ending in
        // an ellipsis (Mi TV, 2026-11-25) and the rail is no wider and no taller for it.
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 20.sp),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The sentence of the focused row, for the strip under the pane (WP-74). Written from the rows'
 * focus callbacks and read only inside [DetailStrip], so a focus move recomposes the strip and
 * never the page (the state is read in the narrowest scope).
 */
@Stable
internal class SettingsDetailHolder {
    var text: String by mutableStateOf("")
}

/** Provided by the television's pane: its rows hand their description to it. Null elsewhere. */
internal val LocalSettingsDetail = staticCompositionLocalOf<SettingsDetailHolder?> { null }

/**
 * A row's description goes to the strip while the row has the remote (television, inside the pane);
 * nowhere else, so a phone's row keeps its sentence in the row.
 */
@Composable
internal fun Modifier.detailOnFocus(text: String): Modifier {
    val holder = LocalSettingsDetail.current ?: return this
    var focused by remember { mutableStateOf(false) }
    // Written only while focused, and never cleared by a row losing it: the row that takes the
    // remote next writes its own words, so the strip never flickers empty between two rows.
    if (focused) LaunchedEffect(text) { holder.text = text }
    return this.onFocusChanged { state ->
        focused = state.hasFocus
        if (state.hasFocus) holder.text = text
    }
}

/**
 * The strip: a fixed [DETAIL_HEIGHT] under the pane holding two lines of the focused row's
 * sentence, the second ending in an ellipsis. An empty sentence keeps the space (nothing shifts).
 * Hidden from a screen reader: each row already speaks its own description with the row, once, so
 * there is no live region and nothing is read twice.
 */
@Composable
private fun DetailStrip(holder: SettingsDetailHolder) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(DETAIL_HEIGHT)
            .testTag(SETTINGS_DETAIL_TAG)
            .semantics { hideFromAccessibility() }
            .padding(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Text(
            holder.text,
            style = MaterialTheme.typography.bodyMedium,
            color = Tone.muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * What the pinned header says the remote is under: the section title of the focused row. Written
 * from the rows' focus callbacks and read only by the header, so a focus move composes the header
 * alone. One per page shown, so a page never shows the last page's section.
 *
 * It is also the pane's map of the rows composed now (WP-75): each row of [settingsGroups] attaches
 * its [FocusRequester] under its place in the page, so the remote can be put back on the row used
 * last (focus memory) or on the first row of the next section (Page and Channel keys), scrolling
 * the page to it first when it is not composed. Those fields are plain, never state: a focus move
 * writes them without composing anything.
 */
@Stable
internal class SettingsBreadcrumb {
    var section: String? by mutableStateOf(null)

    /** The page's list, set by the screen, so a jump can scroll it. */
    var state: LazyListState? = null

    /** Told the row (its place in the page) the remote moved onto, for the focus memory. */
    var onRow: (Int) -> Unit = {}

    private val rows = HashMap<Int, FocusRequester>()
    private var at = -1
    private var sectionStart = -1
    private var nextSection = -1
    private var previousSection = -1

    internal fun attach(row: Int, requester: FocusRequester) {
        rows[row] = requester
    }

    internal fun detach(row: Int, requester: FocusRequester) {
        if (rows[row] === requester) rows.remove(row)
    }

    /** The remote is on the page's first row: Up from it goes to the header's Find a setting (WP-09). */
    var onFirstRow: Boolean = false
        private set

    internal fun rowFocused(row: Int, sectionStart: Int, nextSection: Int, previousSection: Int, first: Boolean = false) {
        onFirstRow = first
        at = row
        this.sectionStart = sectionStart
        this.nextSection = nextSection
        this.previousSection = previousSection
        onRow(row)
    }

    /** Whether [row] is composed and takes the remote now. */
    private fun take(row: Int): Boolean =
        rows[row]?.let { runCatching { it.requestFocus() }.getOrDefault(false) } == true

    /** The composed row nearest [row] that takes the remote (a row the page no longer has, a disabled one). */
    private fun takeNearest(row: Int): Boolean =
        rows.keys.sortedBy { kotlin.math.abs(it - row) }.any { take(it) }

    /**
     * Puts the remote on the row at place [row] of the page, scrolling to it when it is not composed
     * yet; the nearest row that takes it when that one is gone. False only when no row took it.
     */
    suspend fun jumpTo(row: Int): Boolean {
        repeat(EXACT_FRAMES) {
            if (take(row)) return true
            androidx.compose.runtime.withFrameNanos { }
        }
        val list = state
        val base = list?.layoutInfo?.visibleItemsInfo?.firstNotNullOfOrNull { info ->
            settingsRowIndex(info.key)?.let { info.index - it }
        }
        if (list != null && base != null) {
            val total = list.layoutInfo.totalItemsCount
            if (total > 0) runCatching { list.scrollToItem((base + row).coerceIn(0, total - 1)) }
            repeat(EXACT_FRAMES) {
                androidx.compose.runtime.withFrameNanos { }
                if (take(row)) return true
            }
        }
        return takeNearest(row)
    }

    /**
     * Page Down, Channel Down and Fast forward ([direction] 1): the first row of the next section;
     * the Up keys (-1): the first row of this section, then of the one before. False when there is
     * nowhere to go (the last section, the first), so the key does nothing.
     */
    suspend fun sectionJump(direction: Int): Boolean {
        val target = when {
            direction > 0 -> nextSection
            at != sectionStart -> sectionStart
            else -> previousSection
        }
        if (target < 0) return false
        return jumpTo(target)
    }
}

/** The lazy item key of the row at place [index] in a page of [settingsGroups]. */
internal fun settingsRowKey(index: Int): String = "$ROW_KEY_PREFIX$index"

/** The place of the row a lazy item [key] is, or null for any other item. */
internal fun settingsRowIndex(key: Any): Int? =
    (key as? String)?.takeIf { it.startsWith(ROW_KEY_PREFIX) }?.removePrefix(ROW_KEY_PREFIX)?.toIntOrNull()

private const val ROW_KEY_PREFIX = "settings-row-"
private const val EXACT_FRAMES = 3

/**
 * The row the remote was on in each category, kept across a rotation or a process death (WP-75):
 * Right from a rail item lands there again. Keyed by the page's name; a row the page no longer has
 * falls back to the nearest one ([SettingsBreadcrumb.jumpTo]).
 */
internal class SettingsRowMemory(initial: Map<String, Int> = emptyMap()) {
    private val rows = HashMap(initial)

    fun get(page: SettingsPage): Int? = rows[page.name]

    fun put(page: SettingsPage, row: Int) {
        rows[page.name] = row
    }

    companion object {
        val Saver = androidx.compose.runtime.saveable.Saver<SettingsRowMemory, List<String>>(
            save = { memory -> memory.rows.map { "${it.key}=${it.value}" } },
            restore = { saved ->
                SettingsRowMemory(
                    saved.mapNotNull { entry ->
                        val row = entry.substringAfter('=', "").toIntOrNull() ?: return@mapNotNull null
                        entry.substringBefore('=') to row
                    }.toMap(),
                )
            },
        )
    }
}

/**
 * The one-time line under the pane that says the Page and Channel keys jump between sections, only
 * once such a key has arrived on this device (the registry's device fact, written by the first one
 * in any long list or here), and only with the remote in the pane.
 */
@Composable
private fun PageKeysHint(settings: SettingsStore, visible: Boolean, modifier: Modifier) {
    val state by settings.hintState.collectAsState(initial = null)
    val arrived = state?.seen(HintIds.PAGE_KEY_ARRIVED) == true
    val label = LocalStrings.current.settingsPageKeysHint
    val hint: @Composable () -> Unit = {
        Box(modifier.padding(bottom = 8.dp)) {
            OneTimeHint(
                settings = settings,
                id = HintIds.SETTINGS_PAGE_KEYS,
                label = label,
                visible = visible && arrived,
                style = HintStyle.Bubble,
            )
        }
    }
    // Under the screen's own slot when it has one, so one hint per visit holds here too.
    if (LocalHintSlot.current == null) ProvideHintSlot(hint) else hint()
}

/** Provided by the television's pane: [settingsGroups] rows report their section to it. Null elsewhere. */
internal val LocalSettingsBreadcrumb = staticCompositionLocalOf<SettingsBreadcrumb?> { null }

/** The television's rail: its order, and the category used last in this run of the app. */
internal object SettingsRail {
    /** The rail's categories on a television: the shared order (WP-08), 9 of them. */
    fun pages(): List<SettingsPage> = SettingsMap.pages(DeviceForm.Tv).mapNotNull { SettingsPage.of(it) }

    /**
     * The rail item used last, so Settings opened again in this session lands where it was left;
     * null the first time, which lands on Playback.
     */
    var last: SettingsPage? = null

    /** Where the rail lands when Settings opens without a page asked for. */
    fun entry(pages: List<SettingsPage>): SettingsPage =
        last?.takeIf { it in pages } ?: SettingsPage.Playback.takeIf { it in pages } ?: pages.firstOrNull() ?: SettingsPage.Playback
}

/** The rail item [page] sits under: a sub-page's parent (Troubleshooting under Help), else itself. */
internal val SettingsPage.railItem: SettingsPage get() = parent ?: this

internal fun railTag(page: SettingsPage): String = "settings-rail-${page.name}"

internal const val SETTINGS_RAIL_TAG = "settings-rail"
internal const val SETTINGS_PANE_TAG = "settings-pane"
internal const val SETTINGS_HEADER_TAG = "settings-header"
internal const val SETTINGS_CRUMB_TAG = "settings-crumb"
internal const val SETTINGS_STATUS_TAG = "settings-status"
internal const val SETTINGS_DETAIL_TAG = "settings-detail"

/** How long the rail rests on an item before the pane follows it. */
internal const val SETTLE_MS = 150L

/** The pane's fade when it follows the rail. */
private const val FADE_MS = 120

internal val HEADER_HEIGHT = 48.dp

/** The detail strip's height: two lines of body text and its padding (WP-74). */
internal val DETAIL_HEIGHT = 68.dp
internal val RAIL_WIDTH = 232.dp
internal val RAIL_ITEM = 48.dp
private val RAIL_GAP = 24.dp
private val HEADER_END_WIDTH = 320.dp

private val ARROWS = setOf(FocusDirection.Up, FocusDirection.Down, FocusDirection.Left, FocusDirection.Right)
