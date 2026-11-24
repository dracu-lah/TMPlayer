package com.tmplayer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.tmplayer.data.HintState
import com.tmplayer.data.SettingsStore

// The one place a one-time hint is decided (WP-68). Every new hint goes through OneTimeHint with
// an id from HintIds, so a new television user does not meet one on every screen:
//
// - at most one hint per screen visit (a HintSlot, held by ProvideHintSlot at the screen's root);
// - none during playback, except on the very first run (nothing was ever shown);
// - none at all once the tour was skipped twice;
// - one that is gone for good once its count reaches maxShows (1 is "said once").
//
// It is marked as it appears, so it is not repeated after the app is left mid-way.

/** How a hint is drawn: a [FocusTip] pill beside the focused control, or an inline [TipBubble]. */
enum class HintStyle { Focus, Bubble }

/** Who may show a hint on this screen visit: the first to claim it keeps it until the screen leaves. */
class HintSlot {
    private var owner: String? = null

    /** True when [id] holds the slot after the call (it may keep asking: the same id stays the owner). */
    fun claim(id: String): Boolean {
        if (owner == null) owner = id
        return owner == id
    }
}

val LocalHintSlot = staticCompositionLocalOf<HintSlot?> { null }

/**
 * A screen's root calls this once; every [OneTimeHint] inside it shares one slot per visit. Every
 * package that adds a hint must wrap its screen's root in this: without it each [OneTimeHint]
 * holds a slot of its own and the one-hint-per-screen cap does not apply.
 */
@Composable
fun ProvideHintSlot(content: @Composable () -> Unit) {
    val slot = remember { HintSlot() }
    CompositionLocalProvider(LocalHintSlot provides slot, content)
}

/**
 * Whether the registry allows a hint of [id] to start now: not silenced, not past [maxShows], and,
 * when [inPlayback], only on the first run. Pure, so it is tested without a screen.
 */
fun hintAllowed(state: HintState?, id: String, maxShows: Int = 1, inPlayback: Boolean = false): Boolean {
    if (state == null || state.hintsOff) return false
    if (state.count(id) >= maxShows) return false
    return !inPlayback || state.firstRun
}

/**
 * A one-time hint named [id]. While [visible] is true (a tile has focus, a screen has opened) and the
 * registry allows it, it claims the screen's slot, counts itself and shows [label] as a [FocusTip]
 * (place it inside the box of the control it names) or, with [HintStyle.Bubble], a [TipBubble].
 * [draw], when given, draws [label] in place of either (a sentence too long for a one line tip, the
 * phone's "your account moved" card, WP-103); the registry's rules are the same.
 */
@Composable
fun OneTimeHint(
    settings: SettingsStore,
    id: String,
    label: String,
    visible: Boolean = true,
    maxShows: Int = 1,
    inPlayback: Boolean = false,
    style: HintStyle = HintStyle.Focus,
    side: TipSide = TipSide.Below,
    draw: (@Composable (label: String) -> Unit)? = null,
) {
    val state by settings.hintState.collectAsState(initial = null)
    val own = remember { HintSlot() }
    val slot = LocalHintSlot.current ?: own
    var active by remember { mutableStateOf(false) }
    // Counted once per visit (this composition), however often focus comes and goes; the same
    // visit may show it again until it leaves, whatever the count reached.
    var counted by remember { mutableStateOf(false) }
    val allowed = counted || hintAllowed(state, id, maxShows, inPlayback)
    LaunchedEffect(visible, allowed) {
        if (!visible) {
            active = false
        } else if (!active && allowed && slot.claim(id)) {
            active = true
            if (!counted) {
                counted = true
                runCatching { settings.bumpHint(id) }
            }
        }
    }
    if (active) {
        if (draw != null) return draw(label)
        when (style) {
            HintStyle.Focus -> FocusTip(visible = true, label = label, side = side)
            HintStyle.Bubble -> TipBubble(label)
        }
    }
}
