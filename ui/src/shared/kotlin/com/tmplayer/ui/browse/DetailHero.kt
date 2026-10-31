package com.tmplayer.ui.browse

import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.data.DetailAction
import com.tmplayer.data.WatchPoint
import com.tmplayer.i18n.Messages
import com.tmplayer.ui.components.ArtBackdrop
import com.tmplayer.ui.components.BackdropSource
import com.tmplayer.ui.components.Micro
import com.tmplayer.ui.components.SwapIcon
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.components.pressScale
import com.tmplayer.ui.components.rememberToggleTick
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Danger
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.Scrims
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.focusRing

/*
 * The pieces a title page is built from, shared by a video's detail page and a show's page on
 * every platform: the backdrop with its long fades, the facts line with dots between, the one
 * bright Play pill, the round actions beside it, and a synopsis that opens on a press.
 *
 * Everything here stays TMPlayer's: the accent is the app's own, the facts are the file's
 * (quality, size, the chat it came from), and nothing scores or ranks a title.
 */

/** One fact on a title page's facts line: plain text, or [chip] for the quality, outlined. */
@Immutable
internal data class Fact(val text: String, val chip: Boolean = false, val accent: Boolean = false)

/**
 * "2024 · 1h 52m · [1080p] · 1.8 GB · Film Club": the facts in labelLarge with a small dot between
 * each, wrapping a whole fact at a time. A dot only ever sits between two facts on the same line,
 * never at the start or the end of one. The quality sits in an outline, as the one fact that tells
 * two copies of a film apart.
 */
@Composable
internal fun FactLine(facts: List<Fact>, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.labelLarge) {
    if (facts.isEmpty()) return
    val dot = Tone.muted
    Layout(
        modifier = modifier,
        content = {
            facts.forEach { fact ->
                Box(Modifier.size(4.dp).clip(CircleShape).background(dot))
                if (fact.chip) {
                    Text(
                        fact.text,
                        style = style,
                        color = Tone.text,
                        maxLines = 1,
                        modifier = Modifier
                            .border(1.dp, Tone.muted.copy(alpha = 0.6f), RoundedCornerShape(Corner.ExtraSmall))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                } else {
                    Text(
                        fact.text,
                        style = style,
                        color = if (fact.accent) Tone.accent else Tone.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val lineGap = 6.dp.roundToPx()
        val max = constraints.maxWidth
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val dots = measurables.filterIndexed { at, _ -> at % 2 == 0 }.map { it.measure(loose) }
        val texts = measurables.filterIndexed { at, _ -> at % 2 == 1 }.map { it.measure(loose) }
        // Each fact's line and x, and whether its dot shows (only after another fact on its line).
        val lines = mutableListOf(mutableListOf<Int>())
        val xs = IntArray(texts.size)
        val dotted = BooleanArray(texts.size)
        var x = 0
        texts.forEachIndexed { at, text ->
            val lead = gap + dots[at].width + gap
            if (x > 0 && x + lead + text.width > max) {
                lines.add(mutableListOf())
                x = 0
            }
            dotted[at] = x > 0
            xs[at] = if (dotted[at]) x + lead else 0
            x = xs[at] + text.width
            lines.last().add(at)
        }
        val heights = lines.map { line -> line.maxOf { texts[it].height } }
        val width = lines.maxOf { line -> line.maxOf { xs[it] + texts[it].width } }.coerceAtMost(max)
        val height = heights.sum() + lineGap * (lines.size - 1)
        layout(width, height) {
            var y = 0
            lines.forEachIndexed { row, line ->
                val h = heights[row]
                line.forEach { at ->
                    val text = texts[at]
                    text.placeRelative(xs[at], y + (h - text.height) / 2)
                    if (dotted[at]) {
                        val d = dots[at]
                        d.placeRelative(xs[at] - gap - d.width, y + (h - d.height) / 2)
                    }
                }
                y += h + lineGap
            }
        }
    }
}

/**
 * The one bright action on a title page: Play, Resume, "Resume S2 E4". A near-white pill on the
 * dark theme and a near-black one in daylight ([Tone.hero]), 52 dp tall (48 on a television), at
 * most [HeroPillMax] wide. Under the remote, focus swaps the fill to [Tone.focusFill]: no scale
 * and no ring, the same plain fill swap as the Home carousel's own pills (user, 2026-11-01, a
 * scale and a ring on top of an already-swapped fill read as repetitive).
 */
@Composable
internal fun HeroPill(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    /** A large window's title page: a size up, with a wider stance, as the one thing to press. */
    large: Boolean = false,
) {
    val tv = !isTouch()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val ink = if (focused) Tone.onFocusFill else Tone.onHero
    Row(
        modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .testTag(PLAY_PILL_TAG)
            .widthIn(max = HeroPillMax)
            .heightIn(min = if (tv) 48.dp else if (large) 56.dp else 52.dp)
            .then(if (large) Modifier.widthIn(min = 176.dp) else Modifier)
            // Under a finger the pill gives a little and springs back, as every button here does.
            .then(if (tv) Modifier else Modifier.pressScale(interactions, Micro.PillPressScale))
            .clip(CircleShape)
            .background(if (focused) Tone.focusFill else Tone.hero)
            .clickable(interactionSource = interactions, indication = if (tv) null else LocalIndication.current, onClick = onClick)
            .padding(start = if (large) 24.dp else 20.dp, end = if (large) 30.dp else 24.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        // A press of Play on its way to the player turns the triangle into a spinner, so the
        // press answers at once even while the file is still being looked up (PlayPress).
        androidx.compose.animation.Crossfade(com.tmplayer.ui.components.PlayPress.starting, label = "heroPillBusy") { busy ->
            if (busy) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(color = ink, strokeWidth = 2.5.dp, modifier = Modifier.size(20.dp))
                }
            } else {
                Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(24.dp))
            }
        }
        // A long label in a long language wraps rather than losing its end.
        Text(label, style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = ink, maxLines = 2)
    }
}

/**
 * One of the actions beside [HeroPill]. Under a finger or a pointer a 48 dp round button with its
 * label under it, the way a streaming app's secondary actions sit; on a television a quiet pill
 * with the icon and the label, which reads from a sofa and takes the focus fill as every other
 * control there does. [detail] is read out with the label, where the round button has no room
 * for it.
 */
@Composable
internal fun RoundAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    detail: String? = null,
    focusRequester: FocusRequester? = null,
    /** The word or two under the round button; [label] stays for the pill and for screen readers. */
    short: String = label,
    /**
     * A switch rather than a step: true for one that turns something on (Mark as watched, Add to
     * Watch later), false for one that turns it off, null for neither. A finger feels it tick.
     */
    toggles: Boolean? = null,
    /** A download under way, 0 to 1: a ring round the button fills as it goes. */
    progress: Float? = null,
    /**
     * Its place held for a button that may still come (the trailer, while the title's extras are on
     * their way): laid out, not drawn, not reachable. It fades in where it stood once it is real.
     */
    ghost: Boolean = false,
    /** With [ghost]: it is still on its way, so a soft placeholder of the button stands in its place. */
    waiting: Boolean = false,
    /**
     * False for a button that only says how things stand (Downloaded): dimmed, and a press does
     * nothing. It stays reachable, so a remote whose focus was on Download keeps it there.
     */
    enabled: Boolean = true,
) {
    if (ghost && waiting) {
        Box(modifier) {
            RoundAction(label, icon, onClick, short = short, ghost = true)
            val shimmer = com.tmplayer.ui.components.rememberShimmer()
            if (isTouch()) {
                com.tmplayer.ui.components.SkeletonBox(shimmer, Modifier.align(Alignment.TopCenter).padding(top = 4.dp).size(48.dp), CircleShape)
            } else {
                com.tmplayer.ui.components.SkeletonBox(shimmer, Modifier.matchParentSize(), CircleShape)
            }
        }
        return
    }
    val tv = !isTouch()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val shown by animateFloatAsState(if (ghost) 0f else 1f, Motion.standard(), label = "roundActionShown")
    val modifier = modifier
        .graphicsLayer { alpha = shown }
        .then(if (ghost) Modifier.clearAndSetSemantics {} else Modifier)
    val requester = if (focusRequester != null && !ghost) Modifier.focusRequester(focusRequester) else Modifier
    val press: () -> Unit = if (enabled) onClick else ({})
    val off = if (enabled) Modifier else Modifier.semantics { disabled() }
    if (tv) {
        val ink = when {
            focused -> Tone.onFocusFill
            !enabled -> Tone.muted
            destructive -> Tone.danger
            else -> Tone.text
        }
        Row(
            modifier
                .then(requester)
                .heightIn(min = 48.dp)
                .clip(CircleShape)
                .background(
                    when {
                        focused && destructive -> Danger
                        focused -> Tone.focusFill
                        else -> Tone.surfaceHigh
                    },
                )
                .clickable(interactionSource = interactions, indication = null, enabled = !ghost, onClick = press)
                .then(off)
                .padding(start = 16.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, color = ink, maxLines = 1)
        }
        return
    }
    val ink = when {
        !enabled -> Tone.muted
        destructive -> Tone.danger
        else -> Tone.text
    }
    val tick = rememberToggleTick()
    Column(
        modifier
            .then(requester)
            .width(RoundActionWidth)
            .clip(RoundedCornerShape(Corner.Medium))
            .clickable(interactionSource = interactions, indication = if (enabled) LocalIndication.current else null, enabled = !ghost) {
                if (enabled) {
                    toggles?.let(tick)
                    onClick()
                }
            }
            .then(if (ghost) Modifier else Modifier.semantics { contentDescription = listOfNotNull(label, detail).joinToString(". ") })
            .then(off)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(48.dp)
                .pressScale(interactions)
                .downloadRing(progress)
                .clip(CircleShape)
                .background(if (focused) Tone.focusFill else Tone.surfaceHigh)
                .border(1.dp, if (focused) Color.Transparent else Tone.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            // A switch's icon turns into its other one (the check into the cross, the clock into
            // the cross) rather than blinking, so the press visibly did something.
            SwapIcon(icon, if (focused) Tone.onFocusFill else ink, size = 22.dp)
        }
        // Up to three lines, in type small enough for the longest word to fit the button's width:
        // in Malayalam "Mark as watched" ran past two lines, and German and French broke
        // "Herunterladen" and "Recommencer" in the middle of the word.
        Text(
            short,
            style = fitLongestWord(short, MaterialTheme.typography.labelSmall, RoundActionWidth),
            color = ink,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A ring over the round button's own outline while a download runs: the share done drawn in the
 * accent from the top, sweeping on smoothly between the updates rather than jumping, and fading in
 * as the Download button turns into the Cancel one. Nothing when [progress] is null.
 */
@Composable
internal fun Modifier.downloadRing(progress: Float?): Modifier {
    val shown by animateFloatAsState(if (progress != null) 1f else 0f, Motion.standard(Motion.Normal), label = "ringShown")
    val sweep by animateFloatAsState((progress ?: 0f).coerceIn(0f, 1f), Motion.standard(Motion.Slow), label = "ringSweep")
    val accent = Tone.accent
    val track = Tone.outline
    return drawWithContent {
        drawContent()
        if (shown <= 0f) return@drawWithContent
        val width = 2.5.dp.toPx()
        val inset = width / 2
        val arcSize = androidx.compose.ui.geometry.Size(size.width - width, size.height - width)
        val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawArc(track, 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke, alpha = shown)
        if (sweep > 0f) drawArc(accent, -90f, 360f * sweep, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke, alpha = shown)
    }
}

/**
 * An overview or a caption, cut to three lines under a finger and four on a television, with
 * "More" under it while it is cut. A press opens it whole and "Less" closes it. On a remote it is
 * a stop only while there is more to read, so a short one is not a dead step for the D-pad.
 *
 * With [holdLines] it takes all its lines and the "More" line's room whatever its length, so an
 * overview arriving in place of a caption (or a short one) moves nothing under it ([SynopsisSlot]).
 */
@Composable
internal fun Synopsis(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    lines: Int? = null,
    holdLines: Boolean = false,
) {
    val s = LocalStrings.current
    val tv = !isTouch()
    val cutAt = lines ?: if (tv) 4 else 3
    var open by remember(text) { mutableStateOf(false) }
    var cut by remember(text) { mutableStateOf(false) }
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val shape = RoundedCornerShape(Corner.Small)
    val pressable = cut || open
    val measurer = rememberTextMeasurer()
    val bodyStyle = androidx.compose.material3.LocalTextStyle.current.merge(style)
    Layout(
        modifier = modifier
            .clip(shape)
            .background(if (focused) Tone.surfaceHigh else Color.Transparent)
            .focusRing(focused, shape)
            .then(
                if (pressable) {
                    Modifier.clickable(interactionSource = interactions, indication = if (tv) null else LocalIndication.current) { open = !open }
                } else {
                    Modifier
                },
            )
            .padding(vertical = 2.dp, horizontal = if (tv) 6.dp else 0.dp),
        content = {
            Text(
                text,
                style = style,
                color = Tone.text,
                maxLines = if (open) Int.MAX_VALUE else cutAt,
                minLines = if (holdLines && !open) cutAt else 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (open) s.commonLess else s.commonMore,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = Tone.accent,
                // Held with nothing more to read: the room kept, the word neither shown nor read out.
                modifier = Modifier
                    .graphicsLayer { alpha = if (cut || open) 1f else 0f }
                    .then(if (pressable) Modifier else Modifier.clearAndSetSemantics {}),
            )
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val body = measurables[0].measure(loose)
        // Whether the words run past their lines, worked out here rather than from the text's own
        // layout a frame later: "More" is in its place on the first frame, instead of arriving
        // under the words and pushing everything below it down by a line.
        if (!open) {
            val room = if (constraints.hasBoundedWidth) constraints.maxWidth else body.width
            val over = measurer.measure(
                text,
                bodyStyle,
                overflow = TextOverflow.Ellipsis,
                maxLines = cutAt,
                constraints = androidx.compose.ui.unit.Constraints(maxWidth = room),
            ).hasVisualOverflow
            if (over != cut) cut = over
        }
        val more = if (cut || open || holdLines) measurables[1].measure(loose) else null
        val gap = 2.dp.roundToPx()
        val width = maxOf(body.width, more?.width ?: 0)
        val height = body.height + (more?.let { gap + it.height } ?: 0)
        layout(width, height) {
            body.placeRelative(0, 0)
            more?.placeRelative(0, body.height + gap)
        }
    }
}

/**
 * Where the viewer stopped, at rest on the page: a short line of the share watched and "24 min
 * left" beside it. Nothing for a video never started or already finished (the facts line says
 * Watched for that).
 */
@Composable
internal fun ResumeLine(watched: WatchPoint?, finished: Boolean, durationSec: Int, modifier: Modifier = Modifier) {
    if (finished || watched == null || watched.fraction <= 0f) return
    val s = LocalStrings.current
    val leftSec = when {
        watched.durationMs > 0 -> (watched.durationMs - watched.positionMs) / 1000
        durationSec > 0 -> durationSec - watched.positionMs / 1000
        else -> 0L
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ProgressLine(watched.fraction, Modifier.width(RESUME_LINE))
        if (leftSec > 0) {
            Text(s.browseTimeLeft(s.formatter.duration(leftSec)), style = MaterialTheme.typography.labelLarge, color = Tone.muted, maxLines = 1)
        }
    }
}

/**
 * "Part 4 of 7 is not in this chat", under the facts of a video cut into byte pieces when one of
 * them is missing ([com.tmplayer.data.SplitPieces]). Nothing for any other video.
 */
@Composable
internal fun PiecesMissingLine(item: com.tmplayer.data.MediaItem, modifier: Modifier = Modifier) {
    if (!item.isJoined) return
    val s = LocalStrings.current
    val line = remember(item, s) { com.tmplayer.data.SplitPieces.missingLine(item) } ?: return
    Text(line, style = MaterialTheme.typography.labelLarge, color = Tone.muted, maxLines = 2, modifier = modifier)
}

/**
 * A page's backdrop: the art ([ArtBackdrop], which falls back from the online picture to the
 * Telegram thumbnail's soft field to the generated cover) with the long fades text needs.
 * [fromStart] adds the start fade, for a wide page whose text sits on the start half; the foot
 * always runs [fade] down into the page.
 */
@Composable
internal fun PageBackdrop(source: BackdropSource, modifier: Modifier = Modifier, fromStart: Boolean = false, fade: Dp = BACKDROP_FADE) {
    Box(modifier) {
        ArtBackdrop(source, Modifier.fillMaxSize())
        if (fromStart) Box(Modifier.fillMaxSize().background(Scrims.startLong()))
        Box(Modifier.fillMaxSize().background(Scrims.bottomLong(fade)))
    }
}

/**
 * How tall a backdrop is across the top of a tall page [width] wide in a window [height] tall.
 * An online picture is a real backdrop and gets Nuvio's measure, width x 1.33 within 420 to 760
 * dp; Telegram's own thumbnail is small and soft, so it gets a shorter frame. Either way it
 * leaves the lower third of the window for the title and Play.
 */
internal fun tallBackdropHeight(width: Dp, height: Dp, online: Boolean): Dp {
    val wanted = if (online) (width * 1.33f).coerceIn(420.dp, 760.dp) else (width * 0.9f).coerceIn(280.dp, 520.dp)
    return wanted.coerceAtMost(height * 0.66f)
}

/**
 * How much of a wide page's width the backdrop takes, from the end edge: the whole of it, as
 * [com.tmplayer.ui.components.EDGE_SHARE] is for the Home carousel (a share short of 1 still left
 * a flat, undrawn sliver at the start edge that read as a cut picture, user, 2026-11-01), so the
 * two read as the same hero rather than two different widths of picture.
 */
internal const val BACKDROP_SHARE = 1f

/** How fast a tall page's backdrop moves against the page's own scroll. */
internal const val PARALLAX = 0.5f

/** The Play pill's tag, for the tests that check it stays where it was first drawn. */
const val PLAY_PILL_TAG = "play-pill"

/** How wide the Play pill grows at most. */
internal val HeroPillMax = 420.dp

/**
 * The short word under [row]'s round button. A menu line says "Mark as watched", but in Russian or
 * Malayalam that runs to words too long for a 72 dp button, so the button says "Watched" and reads
 * the full line out. A line with no short form here keeps its own label.
 */
internal fun roundLabel(row: DetailRow, s: Messages): String = when (row.action) {
    DetailAction.StartOver -> s.detailRoundRestart
    DetailAction.MarkWatched -> s.detailRoundWatched
    DetailAction.MarkUnwatched -> s.detailRoundUnwatched
    DetailAction.AddWatchLater -> s.detailRoundWatchLater
    DetailAction.RemoveWatchLater -> s.detailRoundRemove
    DetailAction.Download -> s.detailRoundDownload
    DetailAction.SaveToDownloads -> s.detailRoundSave
    DetailAction.InDownloads -> s.detailRoundDownloaded
    DetailAction.RemoveDownload -> s.detailRoundDelete
    DetailAction.CancelDownload -> s.detailRoundStop
    DetailAction.SelectVideos -> s.detailRoundSelect
    DetailAction.OpenElsewhere -> s.detailRoundOpenWith
    DetailAction.CopyLink -> s.detailRoundLink
    DetailAction.OpenChat -> s.detailRoundChat
    DetailAction.SplitCopy -> s.detailRoundSplit
    DetailAction.JoinCopies -> s.detailRoundJoin
    DetailAction.WrongMatch -> s.detailRoundWrongMatch
    DetailAction.UseMatch -> s.detailRoundUseMatch
    DetailAction.Rename -> s.detailRoundRename
    // Play rows beside the pill are the extras, told apart by their wording.
    else -> roundLabel(row.label, s)
}

/** The short word for a round button known only by its [label], as a series page's are. */
internal fun roundLabel(label: String, s: Messages): String = when (label) {
    s.metadataTrailer -> s.detailRoundTrailer
    s.detailStartOver -> s.detailRoundRestart
    s.metadataTurnOn -> s.detailRoundShowInfo
    s.seriesDownloadSeason -> s.detailRoundSeason
    s.seriesDownloadUnwatched -> s.detailRoundUnseen
    else -> label
}

/** A round action and its label under it. */
internal val RoundActionWidth = 72.dp

/**
 * [style], made smaller (down to [MIN_WORD_SCALE] of its size) until the longest word of [text]
 * fits [width] whole, so a narrow label wraps between words rather than inside one. Shared with
 * a large window's own round actions ([WideIconAction]), whose captions are measured the same way.
 */
@Composable
internal fun fitLongestWord(text: String, style: TextStyle, width: Dp): TextStyle {
    val measurer = rememberTextMeasurer()
    val room = with(LocalDensity.current) { width.toPx() }
    return remember(text, style, room) {
        val widest = text.split(' ').filter { it.isNotEmpty() }.maxOfOrNull { word ->
            measurer.measure(word, style, maxLines = 1, softWrap = false).size.width
        } ?: 0
        if (widest <= room) style else style.copy(fontSize = style.fontSize * (room / widest).coerceAtLeast(MIN_WORD_SCALE))
    }
}

private const val MIN_WORD_SCALE = 0.8f

/** The long fade at the foot of a page's backdrop. */
internal val BACKDROP_FADE = 320.dp

private val RESUME_LINE = 160.dp
