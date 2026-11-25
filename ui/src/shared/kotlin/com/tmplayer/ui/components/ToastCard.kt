package com.tmplayer.ui.components

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.Tone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * What a short message is about, which picks its colour, its icon and how the icon moves: a
 * confirmation that something worked, a plain note, a thing to be careful about, a failure.
 */
enum class ToastKind {
    /** A plain note (the default): the accent. */
    Info,

    /** Something worked: saved, copied, up to date, removed. Green, a tick that draws itself. */
    Success,

    /** Worth a second look, nothing has failed: amber, one gentle pulse. */
    Warning,

    /** It failed: red, the cross shakes once. */
    Error,
}

/** The colour a [ToastKind] is drawn in (its wash, its border and its icon). */
@Composable
fun ToastKind.tint(): Color = when (this) {
    ToastKind.Info -> Tone.accent
    ToastKind.Success -> Tone.success
    ToastKind.Warning -> Tone.caution
    ToastKind.Error -> Tone.danger
}

/** Sizes and the arithmetic of where a toast stands: one set of figures for the phone, the desktop and the TV. */
object ToastLayout {
    /** Between a toast and whatever is under it (the floating bar, the window's foot). */
    val Gap: Dp = 12.dp

    /** The round chip behind the icon. */
    val Chip: Dp = 32.dp

    /** The icon inside the chip. */
    val Glyph: Dp = 20.dp

    val Radius: Dp = 16.dp

    /** The chip and its icon in a compact status badge ([ToastCard]'s `compact`). */
    val BadgeChip: Dp = 24.dp
    val BadgeGlyph: Dp = 16.dp

    /** The widest a status badge grows: its words ellipsize past it. */
    val BadgeMax: Dp = 280.dp

    /** A phone's toast fills the width less this margin at each side, up to [PhoneMax]. */
    val PhoneMargin: Dp = 16.dp
    val PhoneMax: Dp = 560.dp

    /** The desktop's toast wraps its words between these. */
    val DesktopMin: Dp = 280.dp
    val DesktopMax: Dp = 480.dp

    /** A television toast wraps up to this, its text at [TvText]. */
    val TvMax: Dp = 720.dp
    val TvText = 18.sp

    /** How long the toast takes to come in, and to go. */
    const val EnterMs = 160
    const val ExitMs = 120

    /** The width of a phone's toast in a window [window] wide. */
    fun phoneWidth(window: Dp): Dp = min(max(window.value - PhoneMargin.value * 2, 0f), PhoneMax.value).dp

    /**
     * How far from the window's foot a toast's bottom edge is: [gap] over the highest of the bar
     * that floats at the foot ([floor], measured, see [ToastFloor]), the keyboard or the system
     * bar ([inset]) and the television's safe margin ([safe]). Pixels in, pixels out.
     */
    fun clearancePx(floor: Int, inset: Int, safe: Int, gap: Int): Int = max(max(floor, inset), safe) + gap
}

/**
 * How high the floating bar (and whatever else floats at the foot of the window) stands, measured
 * from the window's foot, so a toast is placed above it by what is really on screen rather than by
 * a constant the bar's design could outgrow. Each bar reports itself with [toastFloor]; the
 * highest one counts; one that leaves the screen takes itself out.
 */
object ToastFloor {
    private val tops = mutableStateMapOf<Any, Int>()

    /** The highest reported edge, in pixels from the window's foot; zero with nothing reported. */
    val px: Int get() = tops.values.maxOrNull() ?: 0

    fun report(owner: Any, fromFoot: Int) {
        if (tops[owner] != fromFoot) tops[owner] = fromFoot
    }

    fun clear(owner: Any) {
        tops.remove(owner)
    }
}

/** Reports this element's top edge to [ToastFloor] for as long as it is on screen. Put it on the floating bar. */
@Composable
fun Modifier.toastFloor(): Modifier {
    val owner = remember { Any() }
    val windowHeight by rememberUpdatedState(LocalWindowInfo.current.containerSize.height)
    DisposableEffect(owner) { onDispose { ToastFloor.clear(owner) } }
    return this.onGloballyPositioned { coordinates ->
        val top = coordinates.boundsInWindow().top
        if (top.isFinite()) ToastFloor.report(owner, max(0, windowHeight - top.roundToInt()))
    }
}

/** How a toast comes and goes: up and in over [ToastLayout.EnterMs], a plain fade out; fade only with less motion asked for. */
object ToastMotion {
    fun enter(still: Boolean): EnterTransition =
        if (still) fadeIn(tween(ToastLayout.EnterMs)) else fadeIn(tween(ToastLayout.EnterMs)) + slideInVertically(tween(ToastLayout.EnterMs)) { it / 2 }

    fun exit(): ExitTransition = fadeOut(tween(ToastLayout.ExitMs))
}

/** How a toast is sent away by hand: a swipe on a touch screen, a click on a desktop, or not at all. */
enum class ToastDismiss { None, Swipe, Click }

/**
 * The one toast: a card in the design system's surface with a wash of its kind's colour, a hairline
 * border of it (a border, never a shadow), a round icon chip that moves once as the toast arrives,
 * up to two lines of words and, at the end, an optional [action] (the caller draws it: a text
 * button on a touch screen or a desktop, a pill under a remote).
 *
 * It takes no focus of its own: only an [action], which the platform draws, can be reached. It is
 * a live region, polite, or assertive for an [ToastKind.Error], and its icon is hidden from the
 * screen reader since the words say it. [animateIcon] is false under reduced motion and while a
 * toast replaces another, so the icon stands in its final state.
 */
@Composable
fun ToastCard(
    kind: ToastKind,
    message: String,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    /** True where the card has a fixed width (a phone's): the words take the room and the action stands at the end. */
    fillWidth: Boolean = false,
    animateIcon: Boolean = true,
    /** A status badge rather than a toast: a 24 dp chip, one line, 6 dp above and below (about 36 dp high). */
    compact: Boolean = false,
    dismiss: ToastDismiss = ToastDismiss.None,
    onDismiss: () -> Unit = {},
    action: (@Composable () -> Unit)? = null,
) {
    val tint = kind.tint()
    val shape = RoundedCornerShape(ToastLayout.Radius)
    var drag by remember { mutableFloatStateOf(0f) }
    val latestDismiss by rememberUpdatedState(onDismiss)
    val gesture = when (dismiss) {
        ToastDismiss.None -> Modifier
        ToastDismiss.Click -> Modifier.pointerInput(Unit) { detectTapGestures(onTap = { latestDismiss() }) }
        ToastDismiss.Swipe -> Modifier
            .offset { IntOffset(drag.roundToInt(), 0) }
            .graphicsLayer { alpha = (1f - abs(drag) / 600f).coerceIn(0.2f, 1f) }
            .pointerInput(Unit) {
                val threshold = 96.dp.toPx()
                detectHorizontalDragGestures(
                    onDragEnd = { if (abs(drag) > threshold) latestDismiss() else drag = 0f },
                    onDragCancel = { drag = 0f },
                ) { _, amount -> drag += amount }
            }
    }
    Row(
        modifier
            .then(gesture)
            .clip(shape)
            .background(FloatingTone.menu)
            .background(tint.copy(alpha = WASH))
            .border(1.dp, tint.copy(alpha = EDGE), shape)
            .semantics(mergeDescendants = true) {
                liveRegion = if (kind == ToastKind.Error) LiveRegionMode.Assertive else LiveRegionMode.Polite
            }
            .padding(
                start = if (compact) 8.dp else 12.dp,
                end = if (action != null) 8.dp else if (compact) 12.dp else 16.dp,
                top = if (compact) 6.dp else 10.dp,
                bottom = if (compact) 6.dp else 10.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
    ) {
        Box(
            Modifier.size(if (compact) ToastLayout.BadgeChip else ToastLayout.Chip).clip(CircleShape).background(tint.copy(alpha = CHIP)),
            contentAlignment = Alignment.Center,
        ) { ToastGlyph(kind, tint, animateIcon, if (compact) ToastLayout.BadgeGlyph else ToastLayout.Glyph) }
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium.let { if (large) it.copy(fontSize = ToastLayout.TvText, lineHeight = 26.sp) else it },
            color = Tone.text,
            maxLines = if (compact) 1 else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = fillWidth),
        )
        action?.invoke()
    }
}

/** A toast's action on a touch screen or a desktop: a text button in the toast's colour. */
@Composable
fun ToastActionButton(label: String, kind: ToastKind, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(label, color = kind.tint(), style = MaterialTheme.typography.labelLarge)
    }
}

private const val WASH = 0.14f
private const val EDGE = 0.38f
private const val CHIP = 0.22f

/** The icon in the chip, and its one small movement: a tick that draws, a cross that shakes, a pulse, a scale in. */
@Composable
private fun ToastGlyph(kind: ToastKind, tint: Color, animate: Boolean, glyph: Dp = ToastLayout.Glyph) {
    val progress = remember(kind) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(kind, animate) {
        if (animate) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(glyphMs(kind), easing = com.tmplayer.ui.theme.Motion.Standard))
        } else {
            progress.snapTo(1f)
        }
    }
    val p = progress.value
    when (kind) {
        ToastKind.Success -> Canvas(Modifier.size(glyph)) {
            val k = size.width / 24f
            val full = Path().apply {
                moveTo(5f * k, 12.5f * k)
                lineTo(9.5f * k, 17f * k)
                lineTo(19f * k, 7f * k)
            }
            val shown = Path()
            val measure = PathMeasure()
            measure.setPath(full, false)
            measure.getSegment(0f, measure.length * p, shown, true)
            drawPath(
                shown,
                tint,
                style = Stroke(width = (2.25f * glyph.value / ToastLayout.Glyph.value).dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        ToastKind.Error -> Icon(
            TmIcons.Close,
            contentDescription = null,
            tint = tint,
            // One shake: a few quick swings that settle.
            modifier = Modifier.size(glyph).graphicsLayer { translationX = shake(p) * 3.dp.toPx() },
        )
        ToastKind.Warning -> Icon(
            TmIcons.Warning,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(glyph).graphicsLayer {
                val s = pulse(p)
                scaleX = s
                scaleY = s
            },
        )
        ToastKind.Info -> Icon(
            TmIcons.Info,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(glyph).graphicsLayer {
                val s = 0.6f + 0.4f * p
                scaleX = s
                scaleY = s
                alpha = p.coerceIn(0f, 1f)
            },
        )
    }
}

/** How long each kind's icon takes: the tick about 300 ms, the others a little more. */
internal fun glyphMs(kind: ToastKind): Int = when (kind) {
    ToastKind.Success -> 300
    ToastKind.Error -> 360
    ToastKind.Warning -> 420
    ToastKind.Info -> 240
}

/** Sideways offset (-1..1) of the error cross at progress [p]: three swings, dying away. */
internal fun shake(p: Float): Float = (sin(p * 3f * 2f * PI) * (1f - p)).toFloat()

/** The warning's size at progress [p]: up to 1.18 and back, once. */
internal fun pulse(p: Float): Float = 1f + 0.18f * sin(p * PI).toFloat()

/**
 * What [rememberToast] hands back: called with the words alone it is the plain (String) -> Unit
 * every screen already passes around, and with a [ToastKind] it also says what the message is
 * (`toast(said, ToastKind.Success)`), which picks the card's colour and icon.
 */
class Toaster(private val post: (String, ToastKind) -> Unit) : (String) -> Unit {
    override fun invoke(message: String) = post(message, ToastKind.Info)

    operator fun invoke(message: String, kind: ToastKind) = post(message, kind)
}

/** [Toaster] with an action (Undo): the plain three argument call, or one that also names the [ToastKind]. */
class ActionToaster(private val post: (String, String, ToastKind, () -> Unit) -> Unit) : (String, String, () -> Unit) -> Unit {
    override fun invoke(message: String, action: String, onAction: () -> Unit) = post(message, action, ToastKind.Info, onAction)

    operator fun invoke(message: String, action: String, kind: ToastKind, onAction: () -> Unit) = post(message, action, kind, onAction)
}
