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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.DeviceForm
import com.tmplayer.data.SettingsMap
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
 * rail, a 24 dp gap and the pane (about 608 dp), which reaches the foot of the area: the detail
 * strip for the focused row's long sentence (WP-74) gets its room when it is built, not before.
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
    /** Quiet facts at the header's trailing end: the version line (WP-104). */
    headerEnd: @Composable () -> Unit = {},
    pane: @Composable () -> Unit,
) {
    var railHasFocus by remember { mutableStateOf(false) }
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
            end = headerEnd,
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
            }
            Spacer(Modifier.width(RAIL_GAP))
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .testTag(SETTINGS_PANE_TAG)
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
                    .focusGroup(),
            ) {
                PaneFade(paneKey, Modifier.fillMaxWidth().weight(1f)) { pane() }
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
    end: @Composable () -> Unit = {},
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
        Spacer(Modifier.width(16.dp))
        Box(Modifier.width(HEADER_END_WIDTH), contentAlignment = Alignment.CenterEnd) { end() }
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
            .height(RAIL_ITEM)
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
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * What the pinned header says the remote is under: the section title of the focused row. Written
 * from the rows' focus callbacks and read only by the header, so a focus move composes the header
 * alone. One per page shown, so a page never shows the last page's section.
 */
@Stable
internal class SettingsBreadcrumb {
    var section: String? by mutableStateOf(null)
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

/** How long the rail rests on an item before the pane follows it. */
internal const val SETTLE_MS = 150L

/** The pane's fade when it follows the rail. */
private const val FADE_MS = 120

internal val HEADER_HEIGHT = 48.dp
internal val RAIL_WIDTH = 232.dp
internal val RAIL_ITEM = 48.dp
private val RAIL_GAP = 24.dp
private val HEADER_END_WIDTH = 320.dp

private val ARROWS = setOf(FocusDirection.Up, FocusDirection.Down, FocusDirection.Left, FocusDirection.Right)
