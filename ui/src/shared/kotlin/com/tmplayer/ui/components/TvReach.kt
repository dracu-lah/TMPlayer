@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.tmplayer.ui.components

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import com.tmplayer.ui.nav.BackHandler
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * How a remote reaches a card or a line with buttons that floats over a TV screen (HX22): the
 * connection banner's Retry and Use a proxy were drawn over Home while the remote stayed on a
 * card, and no key got to them.
 *
 * One rule on the TV:
 * - A banner or a line that is not a question ([tvReachable]) never takes the remote, but Up from
 *   the top of the screen (the bar, or a page's top-most item) goes into a top one, Down from the
 *   bottom into a bottom one. While the remote is in it, Left and Right walk its buttons; Back,
 *   or the arrow back the way it came, puts the remote back where it was; and no arrow reaches
 *   the screen behind. If it goes away while it has the remote, the remote goes back too.
 * - A card that asks something ([tvHoldsFocus]) takes the remote as it comes up, keeps it inside
 *   until it is answered, and gives it back where it was.
 *
 * "Where it was" is the last return point a screen marked ([tvReturnPoint]): the bar's item the
 * remote was on, or the page under the bar, whose focus restorer puts the remote back on its item.
 * Without one, the remote moves out of the card the way it came in, onto what stands there.
 *
 * Phones and desktops are left as they are: none of this does anything off a TV.
 */
object TvReach {
    enum class Edge { Top, Bottom }

    internal class Spot(val edge: Edge, val focus: FocusRequester)

    private val spots = mutableStateListOf<Spot>()

    /** The last place a screen marked as one the remote can be put back on. */
    internal var returnPoint: FocusRequester? = null

    /** Where the remote goes when the card it is in is left or goes away. */
    private var leaveTo: FocusRequester? = null

    /** Bumped to give the remote back a frame later, where it could not be given back at once. */
    internal var giveBack by mutableIntStateOf(0)
        private set
    private var giveBackTo: FocusRequester? = null

    /** Whether a reachable card stands on [edge] now. */
    fun shown(edge: Edge): Boolean = spots.any { it.edge == edge }

    /**
     * The remote goes into the newest card on [edge], to come back to [from] (else the last return
     * point) when it leaves. True if there was one to go into.
     */
    fun enter(edge: Edge, from: FocusRequester? = null): Boolean {
        val spot = spots.lastOrNull { it.edge == edge } ?: return false
        val back = from ?: returnPoint
        keep(back)
        val went = runCatching { spot.focus.requestFocus() }.getOrDefault(false)
        if (went) leaveTo = back
        return went
    }

    internal fun add(spot: Spot) {
        spots.add(spot)
    }

    /** A card gone: if it had the remote, the remote goes back where it came from. */
    internal fun remove(spot: Spot, hadIt: Boolean) {
        spots.remove(spot)
        val back = takeLeaveTo() ?: returnPoint
        if (hadIt) giveBackTo(back)
    }

    /**
     * Gives the remote to [to] as a card that had it goes. At once, since the window has already
     * put it somewhere of its own (its first item) when the card was taken away; a frame later if
     * that cannot be done now.
     */
    internal fun giveBackTo(to: FocusRequester?) {
        if (to?.putBack() != true) later(to)
    }

    /** Notes which item inside [at] has the remote now, for [putBack] to find it again. */
    internal fun keep(at: FocusRequester?) {
        runCatching { at?.saveFocusedChild() }
    }

    /** The remote back on the item inside this it was on ([keep]), else on this. */
    internal fun FocusRequester.putBack(): Boolean =
        runCatching { restoreFocusedChild() || requestFocus() }.getOrDefault(false)

    /** The return point a card that is left should use, taken once. */
    internal fun takeLeaveTo(): FocusRequester? = leaveTo.also { leaveTo = null }

    /** Gives the remote to [to] a frame from now. */
    private fun later(to: FocusRequester?) {
        giveBackTo = to
        giveBack++
    }

    internal fun giveBackNow(manager: FocusManager) {
        val to = giveBackTo
        giveBackTo = null
        val went = to?.putBack() == true
        // Nothing to go back to: the remote goes onto the first thing there, never nowhere.
        if (!went) manager.moveFocus(FocusDirection.Enter)
    }
}

/**
 * Over the whole window, once: an Up (or Down) that nothing used, at the top (or bottom) of the
 * screen, goes into a reachable card on that edge; and a card gone while it held the remote gives
 * it back. Nothing on a phone or a desktop.
 */
@Composable
fun Modifier.tvReachRoot(): Modifier {
    if (isTouch()) return this
    val manager = LocalFocusManager.current
    val ask = TvReach.giveBack
    LaunchedEffect(ask) {
        if (ask == 0) return@LaunchedEffect
        withFrameNanos { }
        TvReach.giveBackNow(manager)
    }
    return onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        val edge = when (event.key) {
            Key.DirectionUp -> TvReach.Edge.Top
            Key.DirectionDown -> TvReach.Edge.Bottom
            else -> return@onKeyEvent false
        }
        if (!TvReach.shown(edge)) return@onKeyEvent false
        // What the key would have done anyway, and only where it found nothing: the card.
        val dir = if (edge == TvReach.Edge.Top) FocusDirection.Up else FocusDirection.Down
        manager.moveFocus(dir) || TvReach.enter(edge)
    }
}

/**
 * Marks [requester] as a place the remote can be put back on whenever the remote is inside this
 * (a bar's item, or a page whose focus restorer knows its last item).
 */
fun Modifier.tvReturnPoint(requester: FocusRequester): Modifier =
    onFocusChanged { if (it.hasFocus) TvReach.returnPoint = requester }

/**
 * A banner or a line with buttons that does not take the remote but can be reached from [edge]
 * of the screen (see [TvReach]). [onHeld] says while the remote is in it, for one that would go
 * by itself to stay up. Nothing on a phone or a desktop.
 */
@Composable
fun Modifier.tvReachable(edge: TvReach.Edge, onHeld: (Boolean) -> Unit = {}): Modifier {
    if (isTouch()) return this
    val focus = remember { FocusRequester() }
    val manager = LocalFocusManager.current
    val spot = remember(edge) { TvReach.Spot(edge, focus) }
    var held by remember { mutableStateOf(false) }
    val gate = remember { Gate() }
    DisposableEffect(spot) {
        TvReach.add(spot)
        onDispose { TvReach.remove(spot, gate.hadIt()) }
    }
    val leave: () -> Unit = {
        val back = TvReach.takeLeaveTo() ?: TvReach.returnPoint
        gate.open = true
        val went = with(TvReach) { back?.putBack() == true }
        if (!went) manager.moveFocus(if (edge == TvReach.Edge.Top) FocusDirection.Down else FocusDirection.Up)
        gate.open = false
    }
    BackHandler(enabled = held, onBack = leave)
    return this
        .focusRequester(focus)
        .onFocusChanged {
            gate.focus(it.hasFocus)
            if (held != it.hasFocus) {
                held = it.hasFocus
                onHeld(it.hasFocus)
            }
        }
        .onKeyEvent { event -> reachableKey(event, edge, manager, gate, leave) }
        .focusProperties {
            // Only TvReach.enter brings the remote in, never an arrow passing by on the page: so
            // Up from the page still goes to the bar, and Up from the bar comes here.
            onEnter = { if (requestedFocusDirection in ARROWS) cancelFocusChange() }
            // Only leaving takes the remote out: an arrow at the card's edge is turned into it. A
            // screen that asks for the remote itself (a dialog opening) still gets it.
            onExit = {
                if (!gate.open && requestedFocusDirection in ARROWS) {
                    gate.triedOut = true
                    cancelFocusChange()
                }
            }
        }
        .focusGroup()
}

/**
 * A card that asks something on a TV: it takes the remote on [first] as it comes up, keeps it
 * inside until it is answered (Back is the caller's, as its "not now"), and gives it back where it
 * was when it goes. Nothing on a phone or a desktop.
 */
@Composable
fun Modifier.tvHoldsFocus(first: FocusRequester): Modifier {
    if (isTouch()) return this
    val manager = LocalFocusManager.current
    val back = remember { TvReach.returnPoint }
    val gate = remember { Gate() }
    LaunchedEffect(Unit) {
        TvReach.keep(back)
        runCatching { first.requestFocus() }
    }
    DisposableEffect(Unit) { onDispose { if (gate.hadIt()) TvReach.giveBackTo(back) } }
    return this
        .onFocusChanged { gate.focus(it.hasFocus) }
        .onKeyEvent { event ->
            val dir = arrow(event) ?: return@onKeyEvent false
            if (event.type == KeyEventType.KeyDown) manager.moveFocus(dir)
            true
        }
        .focusProperties { onExit = { if (requestedFocusDirection in ARROWS) cancelFocusChange() } }
        .focusGroup()
}

private val ARROWS = setOf(FocusDirection.Up, FocusDirection.Down, FocusDirection.Left, FocusDirection.Right)

private class Gate {
    var open = false
    var triedOut = false
    private var has = false
    private var lostAt: TimeSource.Monotonic.ValueTimeMark? = null

    fun focus(now: Boolean) {
        if (has && !now) lostAt = TimeSource.Monotonic.markNow()
        has = now
    }

    /**
     * Whether the card has the remote, or had it until a moment ago: taking a card away takes the
     * remote off it before the card is told it is gone.
     */
    fun hadIt(): Boolean = has || lostAt?.let { it.elapsedNow() < JUST_LOST } == true
}

private val JUST_LOST = 250.milliseconds

private fun arrow(event: KeyEvent): FocusDirection? = when (event.key) {
    Key.DirectionUp -> FocusDirection.Up
    Key.DirectionDown -> FocusDirection.Down
    Key.DirectionLeft -> FocusDirection.Left
    Key.DirectionRight -> FocusDirection.Right
    else -> null
}

/**
 * The arrows inside a reachable card: they move between its buttons; at its edge, the outward
 * arrow (Up for a top card) stays, and any other leaves the way the remote came. Every arrow is
 * used here, so none reaches the screen behind.
 */
private fun reachableKey(event: KeyEvent, edge: TvReach.Edge, manager: FocusManager, gate: Gate, leave: () -> Unit): Boolean {
    val dir = arrow(event) ?: return false
    if (event.type != KeyEventType.KeyDown) return true
    val outward = if (edge == TvReach.Edge.Top) FocusDirection.Up else FocusDirection.Down
    val inward = if (edge == TvReach.Edge.Top) FocusDirection.Down else FocusDirection.Up
    if (dir == inward) {
        leave()
        return true
    }
    gate.triedOut = false
    val moved = manager.moveFocus(dir)
    if ((!moved || gate.triedOut) && dir != outward) leave()
    return true
}
