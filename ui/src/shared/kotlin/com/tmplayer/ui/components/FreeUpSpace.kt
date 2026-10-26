package com.tmplayer.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tmplayer.data.FreeUpReview
import com.tmplayer.data.FreeUpReview.Group
import com.tmplayer.data.StorageChart
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.LocalOnFloating
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
import com.tmplayer.ui.theme.floatingSurface
import com.tmplayer.ui.theme.focusRing

/**
 * The top of the Downloads page and of Settings' Storage, the same on the phone, the television
 * and the desktop: the small storage ring beside what TMPlayer uses and what is free, and the one
 * Free up space button under them (user, 2026-10-22: "keep one Free up space button").
 *
 * The button opens the review ([FreeUpReviewDialog]). It is off, with a line saying so, when there
 * is nothing at all to free, and while a run is going ([busy]); after one, [result] says what it
 * freed. [focus] goes on the button, for a television page that lands the remote there.
 */
@Composable
fun StorageOverview(
    figures: StorageChart.Figures,
    measured: Boolean,
    nothingToFree: Boolean,
    onFreeUp: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    result: String? = null,
    large: Boolean = !isTouch(),
    focus: FocusRequester? = null,
) {
    val s = LocalStrings.current
    val f = s.formatter
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (large) 16.dp else 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StorageRing(figures, measured, if (large) 76.dp else 64.dp)
            Spacer(Modifier.width(if (large) 20.dp else 16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (measured) s.downloadsOverviewUsing(size = f.bytes(figures.app)) else s.storageMeasuring,
                    style = (if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium).merge(Tabular),
                    fontWeight = FontWeight.SemiBold,
                    color = Tone.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (measured && figures.deviceTotal > 0) {
                    Text(
                        s.downloadsStorageFree(free = f.bytes(figures.deviceFree), total = f.bytes(figures.deviceTotal)),
                        style = (if (large) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium).merge(Tabular),
                        color = Tone.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        ReviewButton(
            label = if (busy) s.downloadsReviewFreeing else s.downloadsFreeUpSpace,
            icon = TmIcons.Delete,
            onClick = onFreeUp,
            kind = ButtonKind.Primary,
            // Not greyed while the figures are measured or when nothing is listed: the press opens the
            // review, which says so itself, and a dimmed button read as broken (user, 2026-10-27).
            enabled = !busy,
            busy = busy,
            large = large,
            modifier = Modifier.fillMaxWidth().then(if (focus != null) Modifier.focusRequester(focus) else Modifier),
        )
        val line = when {
            busy -> null
            result != null -> result
            measured && nothingToFree -> s.downloadsOverviewNothing
            else -> null
        }
        if (line != null) {
            Text(
                line,
                style = MaterialTheme.typography.bodyMedium,
                color = Tone.muted,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Free up space's review, in a window of its own over the page: each group of what can go with
 * what it holds, the tick boxes ([FreeUpReview] decides which start ticked: never a download),
 * a running total and one Free up button. When the ticks include a download it asks first, in the
 * same window, with Keep them under the remote.
 *
 * [lowStorage] leads the list on a device short of room while Low storage mode is off: the one
 * answer that deletes nothing, with its own Turn on.
 */
@Composable
fun FreeUpReviewDialog(
    sections: List<FreeUpReview.Section>,
    onFree: (FreeUpReview.Picks) -> Unit,
    onDismiss: () -> Unit,
    lowStorage: LowStorageOffer? = null,
) {
    var confirming by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = { if (confirming) confirming = false else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val touch = isTouch()
            val panel = min(maxWidth - 32.dp, if (touch) 560.dp else 640.dp)
            FreeUpReviewPanel(
                sections = sections,
                onFree = onFree,
                onDismiss = onDismiss,
                lowStorage = lowStorage,
                confirming = confirming,
                onConfirming = { confirming = it },
                modifier = Modifier.width(panel).heightIn(max = maxHeight - if (maxHeight < 480.dp) 16.dp else 48.dp),
            )
        }
    }
}

/**
 * The short line a Free up space run leaves under its button: what it freed, and how many things
 * it had to leave because they were in use.
 */
fun freeUpResult(outcome: FreeUpReview.Outcome): String {
    val freed = if (outcome.freed > 0) {
        com.tmplayer.i18n.L.downloadsReviewFreed(size = com.tmplayer.i18n.Translator.messages.formatter.bytes(outcome.freed))
    } else {
        com.tmplayer.i18n.L.downloadsReviewFreedNone
    }
    return if (outcome.skipped > 0) freed + ". " + com.tmplayer.i18n.L.downloadsReviewSkipped(count = outcome.skipped) else freed
}

/** Low storage mode as the review offers it: [on] once it has been turned on from here. */
class LowStorageOffer(val mode: String, val windowBytes: Long, val on: Boolean, val onTurnOn: () -> Unit)

/**
 * The review's panel without its window, so a test can draw it as it stands. [confirming] is the
 * question before a download is deleted, drawn in the panel's place.
 */
@Composable
fun FreeUpReviewPanel(
    sections: List<FreeUpReview.Section>,
    onFree: (FreeUpReview.Picks) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    lowStorage: LowStorageOffer? = null,
    confirming: Boolean = false,
    onConfirming: (Boolean) -> Unit = {},
    /** Ticks to start from, for a picture of the sheet part way through a choice. */
    startPicks: FreeUpReview.Picks? = null,
) {
    val s = LocalStrings.current
    val f = s.formatter
    val shown = remember(sections) { FreeUpReview.shown(sections) }
    var picks by remember { mutableStateOf(startPicks ?: FreeUpReview.initial(sections)) }
    // A new measurement while the sheet is up (a download finishing, a refresh): the viewer's
    // ticks carry over, gone items drop out, and nothing new is ticked behind their back that is a
    // download.
    var before by remember { mutableStateOf(sections) }
    if (before != sections) {
        picks = FreeUpReview.carry(picks, before, sections)
        before = sections
    }
    val total = FreeUpReview.bytes(picks, sections)
    val doomed = FreeUpReview.downloadsPicked(picks, sections)
    val nothing = FreeUpReview.nothingToFree(sections)
    val tv = !isTouch()
    val pad = if (tv) 28.dp else 24.dp
    val first = remember { FocusRequester() }
    // Where the remote lands: Low storage mode's Turn on when it leads, else the first group that
    // can be ticked, else Cancel (nothing to tick, or only things that are kept).
    val firstGroup = shown.indexOfFirst { it.tickable }
    val landOnLow = lowStorage != null && !lowStorage.on
    val landOnCancel = !landOnLow && firstGroup < 0
    val keep = remember { FocusRequester() }
    androidx.compose.runtime.CompositionLocalProvider(LocalOnFloating provides true) {
        Column(modifier.floatingSurface().padding(vertical = pad)) {
            if (confirming) {
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = pad),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(TmIcons.Warning, contentDescription = null, tint = Tone.danger, modifier = Modifier.size(24.dp))
                    Text(s.downloadsReviewConfirmTitle(count = doomed.size), style = MaterialTheme.typography.headlineSmall, color = Tone.text)
                    Text(
                        s.downloadsReviewConfirmBody(
                            count = doomed.size,
                            downloads = f.bytes(doomed.sumOf { it.bytes }),
                            size = f.bytes(total),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tone.muted,
                    )
                }
                Spacer(Modifier.size(20.dp))
                ButtonRow(pad) {
                    // Keep them first and under the remote: a press that cannot be taken back is
                    // never the one a careless OK lands on.
                    ReviewButton(s.downloadsKeepThem, null, { onConfirming(false) }, ButtonKind.Quiet, large = tv, modifier = Modifier.focusRequester(keep))
                    ReviewButton(
                        s.downloadsReviewConfirm(size = f.bytes(total)),
                        null,
                        {
                            onConfirming(false)
                            onFree(picks)
                        },
                        ButtonKind.Danger,
                        large = tv,
                    )
                }
                FocusWhenUp(keep, key = "confirm")
                return@Column
            }
            Column(Modifier.padding(horizontal = pad)) {
                Text(s.downloadsFreeUpSpace, style = MaterialTheme.typography.headlineSmall, color = Tone.text)
                Text(
                    s.downloadsFreeUpSpaceNote,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tone.muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.size(12.dp))
            // The groups and their items scroll between the heading and the buttons, which stay on
            // screen however short the window (a phone on its side) and however long the list.
            LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                if (lowStorage != null) {
                    item(key = "low") { LowStorageLine(lowStorage, pad, if (landOnLow) first else null) }
                }
                if (nothing) {
                    item(key = "nothing") {
                        Text(
                            s.downloadsReviewNothing,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Tone.text,
                            modifier = Modifier.padding(horizontal = pad, vertical = 12.dp),
                        )
                    }
                }
                shown.forEachIndexed { index, section ->
                    item(key = "g-" + section.group.name) {
                        GroupLine(
                            section = section,
                            tick = FreeUpReview.tick(picks, section),
                            ticked = FreeUpReview.bytes(picks, section),
                            pad = pad,
                            focus = if (!landOnLow && index == firstGroup) first else null,
                            onToggle = { picks = FreeUpReview.toggleGroup(picks, section) },
                        )
                    }
                    items(section.items, key = { "i-" + section.group.name + "-" + it.key }) { item ->
                        ItemLine(
                            item = item,
                            checked = FreeUpReview.id(section.group, item.key) in picks.ids,
                            pad = pad,
                            onToggle = { picks = FreeUpReview.toggleItem(picks, section, item.key) },
                        )
                    }
                }
            }
            HorizontalDivider(color = Tone.outline)
            Spacer(Modifier.size(12.dp))
            if (!nothing) {
                Text(
                    s.downloadsReviewTotal(size = f.bytes(total)),
                    style = MaterialTheme.typography.titleMedium.merge(Tabular),
                    fontWeight = FontWeight.SemiBold,
                    color = Tone.text,
                    modifier = Modifier.padding(horizontal = pad).padding(bottom = 12.dp),
                )
            }
            ButtonRow(pad) {
                ReviewButton(if (nothing) s.commonClose else s.commonCancel, null, onDismiss, ButtonKind.Quiet, large = tv, modifier = if (landOnCancel) Modifier.focusRequester(first) else Modifier)
                if (!nothing) {
                    ReviewButton(
                        s.downloadsReviewButton(size = f.bytes(total)),
                        null,
                        { if (doomed.isNotEmpty()) onConfirming(true) else onFree(picks) },
                        ButtonKind.Primary,
                        enabled = total > 0,
                        large = tv,
                    )
                }
            }
            // Again once Low storage mode is on from here, whose Turn on the remote was standing on.
            FocusWhenUp(first, key = "review-$landOnLow")
        }
    }
}

/**
 * Puts the remote on [requester] once the panel is up, and again when [key] changes (the review
 * and its question). The dialog's window takes a frame or two to be handed focus, so it is asked
 * a few times. Nothing on a touch screen.
 */
@Composable
private fun FocusWhenUp(requester: FocusRequester, key: Any) {
    if (isTouch()) return
    LaunchedEffect(key) {
        repeat(6) {
            kotlinx.coroutines.delay(50)
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ButtonRow(pad: Dp, content: @Composable () -> Unit) {
    // Wraps on a narrow phone with a long total rather than squeezing either button's words.
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = pad),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/** A group's line: its tick (all, none or some), its colour from the ring, name, what it is, size. */
@Composable
private fun GroupLine(
    section: FreeUpReview.Section,
    tick: FreeUpReview.Tick,
    ticked: Long,
    pad: Dp,
    focus: FocusRequester?,
    onToggle: () -> Unit,
) {
    val s = LocalStrings.current
    val f = s.formatter
    val state = when (tick) {
        FreeUpReview.Tick.On -> ToggleableState.On
        FreeUpReview.Tick.Off -> ToggleableState.Off
        FreeUpReview.Tick.Some -> ToggleableState.Indeterminate
    }
    val detail = when (section.group) {
        Group.Unfinished -> s.downloadsReviewGroupUnfinished
        Group.Recent -> s.downloadsReviewGroupRecent(count = section.count.coerceAtLeast(1))
        Group.Unmatched -> s.downloadsReviewGroupUnmatched
        Group.Pictures -> s.downloadsReviewGroupPictures
        Group.Downloads -> s.downloadsReviewGroupDownloads(count = section.items.size)
    }
    // A group ticked by item says what its ticks free of what it holds; one cleared whole, its size.
    val size = if (section.byItem && tick == FreeUpReview.Tick.Some) {
        s.commonOfTotal(done = f.bytes(ticked), total = f.bytes(section.freeable))
    } else {
        f.bytes(section.freeable)
    }
    TickLine(
        state = state,
        enabled = section.tickable,
        onToggle = onToggle,
        pad = pad,
        focus = focus,
        top = 6.dp,
    ) { lit ->
        Box(Modifier.size(10.dp).clip(CircleShape).background(storagePartColor(section.group.part)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                storagePartName(section.group.part),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (lit) Tone.onFocusFill else Tone.text,
            )
            Text(detail, style = MaterialTheme.typography.bodySmall, color = if (lit) Tone.onFocusFill else Tone.muted)
            if (section.names.isNotEmpty()) {
                Text(
                    section.names.take(3).joinToString(", ") + if (section.names.size > 3) ", ..." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (lit) Tone.onFocusFill else Tone.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(size, style = MaterialTheme.typography.bodyMedium.merge(Tabular), color = if (lit) Tone.onFocusFill else Tone.text, maxLines = 1)
    }
}

/** One item under its group: its own tick, or, when it is kept for a reason, the reason instead. */
@Composable
private fun ItemLine(item: FreeUpReview.Item, checked: Boolean, pad: Dp, onToggle: () -> Unit) {
    val s = LocalStrings.current
    val f = s.formatter
    TickLine(
        state = if (checked && item.free) ToggleableState.On else ToggleableState.Off,
        enabled = item.free,
        onToggle = onToggle,
        pad = pad,
        indent = 22.dp,
    ) { lit ->
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (lit) Tone.onFocusFill else Tone.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item.lock?.let { lock ->
                Text(
                    when (lock) {
                        FreeUpReview.Lock.Downloading -> s.downloadsReviewLockedDownloading
                        FreeUpReview.Lock.Playing -> s.downloadsReviewLockedPlaying
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Tone.caution,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(f.bytes(item.bytes), style = MaterialTheme.typography.bodySmall.merge(Tabular), color = if (lit) Tone.onFocusFill else Tone.muted, maxLines = 1)
    }
}

/**
 * A line with a tick box at its start, the whole of it the target: a thumb need not find the box,
 * and on a television OK ticks the line the remote is on, which fills with the focus colour.
 */
@Composable
private fun TickLine(
    state: ToggleableState,
    enabled: Boolean,
    onToggle: () -> Unit,
    pad: Dp,
    focus: FocusRequester? = null,
    indent: Dp = 0.dp,
    top: Dp = 0.dp,
    content: @Composable androidx.compose.foundation.layout.RowScope.(lit: Boolean) -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val tv = !isTouch()
    val lit = tv && focused
    val shape = RoundedCornerShape(Corner.Medium)
    val tick = rememberToggleTick()
    val fill by animateColorAsState(if (lit) Tone.focusFill else Color.Transparent, tween(140), label = "reviewLine")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = pad - 8.dp + indent, end = pad - 8.dp, top = top)
            .clip(shape)
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .focusRing(focused, shape)
            .background(fill)
            .triStateToggleable(
                state = state,
                interactionSource = interactions,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Checkbox,
                onClick = {
                    tick(state != ToggleableState.On)
                    onToggle()
                },
            )
            .alpha(if (enabled) 1f else 0.7f)
            .padding(horizontal = 8.dp, vertical = if (tv) 8.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TriStateCheckbox(state = state, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        content(lit)
    }
}

/** Low storage mode at the head of the review, while it is off and the device is short of room. */
@Composable
private fun LowStorageLine(offer: LowStorageOffer, pad: Dp, focus: FocusRequester?) {
    val s = LocalStrings.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = pad, vertical = 4.dp)
            .clip(RoundedCornerShape(Corner.Medium))
            .background(Tone.surfaceHigh)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (offer.on) {
            Text(s.downloadsFreeLowStorageDone(mode = offer.mode), style = MaterialTheme.typography.bodyMedium, color = Tone.text)
            return@Column
        }
        Text(s.downloadsFreeLowStorage(mode = offer.mode), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Tone.text)
        Text(
            s.downloadsFreeLowStorageDetail(size = s.formatter.bytes(offer.windowBytes)),
            style = MaterialTheme.typography.bodySmall,
            color = Tone.muted,
        )
        ReviewButton(
            s.confirmTurnOn,
            null,
            offer.onTurnOn,
            ButtonKind.Quiet,
            large = !isTouch(),
            modifier = if (focus != null) Modifier.focusRequester(focus) else Modifier,
        )
    }
}

internal enum class ButtonKind { Primary, Quiet, Danger }

/**
 * The review's and the overview's buttons, one look on every device: the accent fill for the
 * press that does the job, the quiet step for Cancel, red words for a delete. Under the remote
 * (or the keyboard) every kind fills with the focus colour, the delete with red, as the
 * television's other buttons do.
 */
@Composable
internal fun ReviewButton(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    kind: ButtonKind,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    large: Boolean = false,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val target = when {
        focused && kind == ButtonKind.Danger -> Tone.danger
        focused && !isTouch() -> Tone.focusFill
        kind == ButtonKind.Primary -> Tone.accent
        else -> Tone.surfaceHigh
    }
    val background by animateColorAsState(target, tween(140), label = "reviewButton")
    val foreground = when {
        focused && kind == ButtonKind.Danger -> Color.White
        focused && !isTouch() -> Tone.onFocusFill
        kind == ButtonKind.Primary -> Tone.onAccent
        kind == ButtonKind.Danger -> Tone.danger
        else -> Tone.text
    }
    Row(
        modifier
            .heightIn(min = if (large) 48.dp else 44.dp)
            .clip(CircleShape)
            .focusRing(focused && kind != ButtonKind.Danger, CircleShape)
            .background(background)
            // A quiet button keeps an edge, so it still reads as a button on a fill of its own
            // colour (Low storage mode's box in the review).
            .then(if (kind != ButtonKind.Primary && !focused) Modifier.border(1.dp, Tone.outline, CircleShape) else Modifier)
            .clickable(interactionSource = interactions, indication = LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled || busy) 1f else 0.45f)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), color = foreground, strokeWidth = 2.dp)
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(20.dp))
        }
        Text(
            label,
            style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = foreground,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val Tabular = TextStyle(fontFeatureSettings = "tnum")
