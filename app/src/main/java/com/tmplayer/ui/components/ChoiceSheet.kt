package com.tmplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.IconButton
import com.tmplayer.ui.theme.FloatingTone
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.LocalOnFloating
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import com.tmplayer.ui.theme.floatingSurface
import com.tmplayer.ui.theme.focusRing

/**
 * The language picker's modal, as every picker in the app draws it: Material 3's basic dialog, in
 * the middle of the screen over the dimmed page (or the dimmed video, in the player). The headline
 * at the top start, the [note] as supporting text under it, the choices under that, and with
 * [onClose] a Close button at the bottom end, which is where a Material dialog keeps the way out:
 * a text button on a phone, and on a television a button the remote reaches with Down from the
 * last line and fills with the focus colour. Back (and on a phone a tap outside) is [onDismiss],
 * which a picker with pages of its own uses to step back a page rather than to close.
 *
 * When the lines outgrow the panel they scroll inside it ([ChoiceList]), and dividers mark the
 * top and bottom of the scrolling part, as a Material dialog's do; a list that fits has none.
 *
 * Drawn by hand on the dialog surface rather than as Material's AlertDialog, because the same
 * panel has to work under a remote, where every line is a focus target ([TvChoiceRow]); the
 * phone's lines are Material's radio list rows ([ChoiceRow]).
 */
@Composable
fun ChoiceSheet(
    title: String,
    onDismiss: () -> Unit,
    note: String? = null,
    onClose: (() -> Unit)? = null,
    /** Hung on Close, for a picker that sends the remote there when it has nothing else to stand on. */
    closeFocus: FocusRequester? = null,
    ignoreRelease: Boolean = false,
    /**
     * On a phone, fill the screen instead of floating a panel in the middle of it: the player's
     * pickers, which a landscape phone otherwise squeezes to three lines. See [FullScreenChoiceSheet].
     */
    fullScreen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val touch = isTouch()
    val scrolls = remember { mutableStateOf(false) }
    // A tablet has the room for the floating panel; only a phone's short side goes full screen.
    val screen = LocalConfiguration.current
    val filled = fullScreen && touch && minOf(screen.screenWidthDp, screen.screenHeightDp).dp < FULL_SCREEN_BELOW
    FloatingWindow(onDismiss = onDismiss, ignoreRelease = ignoreRelease, immersive = filled) {
        if (filled) {
            FullScreenChoiceSheet(title, note, onClose ?: onDismiss, wide = maxWidth >= WIDE_FROM, content)
            return@FloatingWindow
        }
        val panel = min(maxWidth - PhonePad.Side * 2, if (touch) 560.dp else 640.dp)
        Column(
            Modifier
                .width(panel)
                .heightIn(max = maxHeight - 48.dp)
                .floatingSurface()
                // Material's 24 dp around a dialog's content; the text buttons at the foot carry
                // room of their own, so the bottom is less by that much.
                .padding(top = 24.dp, bottom = if (touch) 16.dp else 24.dp)
                .padding(horizontal = if (touch) PANEL_PAD_TOUCH else 28.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Tone.text)
            if (note != null) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tone.muted,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            CompositionLocalProvider(LocalListScrolls provides scrolls) {
                if (scrolls.value) ChoiceDivider()
                content()
                if (scrolls.value) ChoiceDivider()
            }
            if (onClose != null) {
                Row(
                    Modifier.fillMaxWidth().padding(top = if (touch) 8.dp else 16.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    SheetCloseButton(onClose, if (closeFocus != null) Modifier.focusRequester(closeFocus) else Modifier)
                }
            }
        }
    }
}

/**
 * A [ChoiceSheet] that fills a phone's screen: the title and an X that closes at the top, the way
 * Material's full-screen dialog has them, and every other dp given to the lines, which scroll under
 * the bar. With Close up top there is no button row eating the foot of a landscape screen, so a
 * picker that showed three lines in the floating panel shows five or six.
 *
 * [wide] (a landscape phone) is handed down as [LocalChoiceWide], so a picker with two kinds of
 * line (the subtitles' tracks, and their timing and look) can set them side by side.
 */
@Composable
private fun FullScreenChoiceSheet(
    title: String,
    note: String?,
    onClose: () -> Unit,
    wide: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val close = LocalStrings.current.commonClose
    Column(
        Modifier
            .fillMaxSize()
            .background(FloatingTone.dialog)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)),
    ) {
        // The divider runs the full width; the words and the lines keep clear of a cutout.
        val sides = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        Row(
            sides.fillMaxWidth().heightIn(min = 64.dp).padding(start = PANEL_PAD_TOUCH, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = Tone.text)
                if (note != null) Text(note, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
            }
            IconButton(onClick = onClose) {
                M3Icon(Icons.Filled.Close, contentDescription = close, tint = Tone.text)
            }
        }
        HorizontalDivider(color = Tone.outline)
        CompositionLocalProvider(LocalChoiceWide provides wide, LocalChoiceFullScreen provides true) {
            Column(
                Modifier.weight(1f).then(sides).fillMaxWidth().padding(horizontal = PANEL_PAD_TOUCH).padding(top = 8.dp),
                content = content,
            )
        }
    }
}

/**
 * True inside a [ChoiceSheet] laid full screen on a landscape phone, where a picker's two groups of
 * lines fit side by side ([ChoiceColumns]).
 */
val LocalChoiceWide = compositionLocalOf { false }

/** True inside a [ChoiceSheet] laid full screen, for a picker that draws its choices otherwise there. */
val LocalChoiceFullScreen = compositionLocalOf { false }

/**
 * Two [ChoiceList]s side by side, each scrolling on its own, for a full-screen picker on a
 * landscape phone. Each list runs through its half's share of the gap, as a single list runs
 * through the dialog's padding, so the two meet in the middle.
 */
@Composable
fun ColumnScope.ChoiceColumns(start: @Composable ColumnScope.() -> Unit, end: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.weight(1f, fill = false).fillMaxWidth()) {
        Column(Modifier.weight(1f)) { ChoiceList(start) }
        Spacer(Modifier.width(PANEL_PAD_TOUCH * 2))
        Column(Modifier.weight(1f)) { ChoiceList(end) }
    }
}

/** Below this short side a touch screen is a phone, and a full-screen picker fills it. */
private val FULL_SCREEN_BELOW = 600.dp

/** From this width a full-screen picker sets its groups side by side. */
private val WIDE_FROM = 640.dp

/**
 * Close at the bottom end of a [ChoiceSheet]: Material's text button on a phone, as a basic
 * dialog's action is; on a television the app's quiet button, which the remote can stand on.
 */
@Composable
fun SheetCloseButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = LocalStrings.current.commonClose
    if (isTouch()) {
        TextButton(onClick = onClick, modifier = modifier) { M3Text(label) }
    } else {
        CompositionLocalProvider(LocalOnFloating provides true) {
            TmSecondaryButton(onClick = onClick, modifier = modifier) { Text(label) }
        }
    }
}

/**
 * Sends the remote to [requester] once a panel is up (and again when [key] changes). Called from
 * inside the panel's content, which is a dialog window's own composition: asked from outside it,
 * the request came before the panel's lines existed and the window put focus on its first line.
 * Waits a frame, so the line is laid out and attached first. Nothing on a touch screen.
 */
@Composable
fun FocusOnOpen(requester: FocusRequester, key: Any? = Unit) {
    if (isTouch()) return
    LaunchedEffect(key) {
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
}

/** The divider over and under a list that scrolls, in the floating surfaces' hairline colour. */
@Composable
private fun ChoiceDivider() {
    HorizontalDivider(
        modifier = if (isTouch()) Modifier.bleed(PANEL_PAD_TOUCH) else Modifier,
        color = Tone.outline,
    )
}

/** Material's padding inside a dialog, on a phone. */
private val PANEL_PAD_TOUCH = 24.dp

/** Set by a [ChoiceList] whose lines outgrow the panel, so its [ChoiceSheet] draws the dividers. */
private val LocalListScrolls = compositionLocalOf<MutableState<Boolean>?> { null }

/**
 * One line of a [ChoiceSheet] on a television: the language picker's tile, which fills with the
 * focus colour under the remote and ticks the choice in force.
 *
 * [selected] true draws the tick, false the tick's empty place; null is a line that does something
 * rather than being chosen, with its [icon] in the tick's place. [trailing] sits at the far end
 * (the system language beside "System default"), [detail] under the title. A line that cannot be
 * chosen now ([enabled] false) is drawn at half strength and does nothing when pressed, but still
 * takes focus, so the remote is never dropped when a line greys out under it.
 */
@Composable
fun TvChoiceRow(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    trailing: String? = null,
    selected: Boolean? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Corner.Medium)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(if (focused) Tone.focusFill else Tone.surface)
            .focusRing(focused, shape)
            .clickable(
                interactionSource = interactions,
                indication = null,
                role = if (selected != null) Role.RadioButton else Role.Button,
            ) { if (enabled) onClick() }
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (focused) Tone.onFocusFill else Tone.text
        val faint = Modifier.alpha(if (enabled) 1f else DISABLED_ALPHA)
        val mark = when {
            selected == true -> Icons.Filled.Check
            selected == null -> icon
            else -> null
        }
        if (mark != null) {
            val tint = if (focused || selected == null) ink else Tone.accent
            Icon(mark, contentDescription = null, tint = tint, modifier = faint.size(22.dp))
        } else {
            Spacer(Modifier.size(22.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(faint.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = ink)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = if (focused) ink else Tone.muted)
            }
        }
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.bodyMedium, color = if (focused) ink else Tone.muted)
        }
    }
}

/**
 * The lines of a [ChoiceSheet], in a column that scrolls inside the panel when they outgrow it and
 * leaves a short list short. On a television it keeps the focus ring's room above and below.
 * Rows sit edge to edge, as a Material list's do; groups are parted by [ChoiceSection].
 */
@Composable
fun ColumnScope.ChoiceList(content: @Composable ColumnScope.() -> Unit) {
    val touch = isTouch()
    val scroll = rememberScrollState()
    val scrolls = LocalListScrolls.current
    val outgrown = scroll.maxValue in 1 until Int.MAX_VALUE
    SideEffect { if (scrolls != null && scrolls.value != outgrown) scrolls.value = outgrown }
    // On a phone the rows run the dialog's full width, through its side padding, and carry that
    // padding inside themselves, as a Material dialog's list does: the press reaches the edges
    // and the radios still line up with the headline. A television's tiles stay inside it, so a
    // focused tile's fill and ring have the panel's edge around them.
    CompositionLocalProvider(LocalChoiceInset provides if (touch) PANEL_PAD_TOUCH else 20.dp) {
        Column(
            Modifier
                .weight(1f, fill = false)
                .then(if (touch) Modifier.bleed(PANEL_PAD_TOUCH) else Modifier)
                .verticalScroll(scroll)
                .padding(vertical = if (touch) 0.dp else Tv.FocusClearance),
            verticalArrangement = Arrangement.spacedBy(if (touch) 0.dp else 6.dp),
            content = content,
        )
    }
}

/** Lays this out [by] wider on each side than its parent's padding allows, edge to edge. */
private fun Modifier.bleed(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = (by * 2).roundToPx()
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = (constraints.minWidth + extra).coerceAtLeast(0),
            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.maxWidth,
        ),
    )
    layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
}

/**
 * A group's subheader inside a [ChoiceList] (the subtitle picker's Timing and Look): Material's
 * list subheader, `titleSmall` in the primary colour as Settings' group titles are on a phone,
 * lined up with the lines' radio or tick, with a gap above it that parts the groups.
 */
@Composable
fun ChoiceSection(title: String) {
    val touch = isTouch()
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = Tone.accent,
        modifier = Modifier.padding(
            start = if (touch) PANEL_PAD_TOUCH else 20.dp,
            top = if (touch) 16.dp else 12.dp,
            bottom = if (touch) 4.dp else 2.dp,
        ),
    )
}

/**
 * One line of a [ChoiceSheet], drawn as the device draws it: [ChoiceRow] on a touch screen,
 * [TvChoiceRow] under a remote. [selected] and [icon] mean what they mean on both.
 */
@Composable
fun ChoiceLine(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    selected: Boolean? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    if (isTouch()) {
        ChoiceRow(title, modifier.fillMaxWidth(), detail, selected, icon, enabled, onClick)
    } else {
        TvChoiceRow(title, modifier, detail, selected = selected, icon = icon, enabled = enabled, onClick = onClick)
    }
}

/** How strong a line that cannot be chosen yet is drawn. */
private const val DISABLED_ALPHA = 0.5f
