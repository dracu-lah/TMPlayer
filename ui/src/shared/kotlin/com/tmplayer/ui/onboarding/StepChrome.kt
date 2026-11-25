package com.tmplayer.ui.onboarding

import com.tmplayer.ui.components.pressScale
import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tmplayer.data.DeviceForm
import com.tmplayer.ui.components.deviceForm
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import com.tmplayer.ui.theme.focusRing
import kotlin.math.abs

/**
 * Where a screen sits in the whole first run: its [index] across every step, and how many steps
 * each group has ([groups]: Appearance, Preferences, Quick tour, those that are shown). The bar at
 * the top draws a segment per step with a wider gap between groups.
 */
data class FlowPosition(val index: Int, val groups: List<Int>) {
    val total: Int get() = groups.sum()
    val isLast: Boolean get() = index == total - 1
}

/**
 * The frame every step of the first run shares (CP67, CP69), the same on a phone, a tablet, a
 * television and a desktop: at the top the group's name with "Step n of m", Skip all at the end of
 * that line, and the segmented bar; the step's own content in the middle; at the foot Back at the
 * start and the primary button (Next, or Done on the very last step) at the end.
 *
 * [onBack] null hides Back (the first step of all) but keeps its place, so Next never moves.
 * [onSkipAll] null hides Skip all, which the last step's Done already says. [onUseRecommended],
 * on the first step, takes Skip all's place with a button beside Next: one Left press from where a
 * remote starts takes the recommended settings and ends the setup. [content] gets
 * whether the screen is wide (a television, a desktop window, a phone on its side), where the
 * words go beside the answers or the picture rather than above them.
 */
@Composable
internal fun StepFrame(
    group: String,
    position: FlowPosition,
    primary: String,
    onPrimary: () -> Unit,
    onBack: (() -> Unit)?,
    onSkipAll: (() -> Unit)?,
    modifier: Modifier = Modifier,
    primaryFocus: FocusRequester? = null,
    onPrimaryFocused: () -> Unit = {},
    onUseRecommended: (() -> Unit)? = null,
    content: @Composable (wide: Boolean) -> Unit,
) {
    val s = LocalStrings.current
    val form = deviceForm()
    val outer = when (form) {
        DeviceForm.Tv -> Modifier.padding(horizontal = Tv.SafeH, vertical = Tv.SafeV)
        DeviceForm.Desktop -> Modifier.padding(horizontal = 40.dp, vertical = 28.dp)
        DeviceForm.Phone -> Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)
    }
    val swipe = if (form == DeviceForm.Phone) {
        Modifier.swipeSteps(onNext = onPrimary, onBack = { onBack?.invoke() })
    } else {
        Modifier
    }
    BoxWithConstraints(
        modifier.fillMaxSize().background(Tone.background).then(swipe).then(outer),
        contentAlignment = Alignment.Center,
    ) {
        val wide = maxWidth > maxHeight && maxWidth >= WIDE_FROM
        Column(Modifier.widthIn(max = if (wide) WIDE_MAX else NARROW_MAX).fillMaxSize()) {
            // Which app is being set up: the mark and the name, on a line of their own over the
            // step's line where the screen is narrow, at its start where there is room.
            val brand: @Composable () -> Unit = {
                com.tmplayer.ui.components.BrandLockup(layout = com.tmplayer.ui.components.BrandLayout.Row)
            }
            if (!wide) {
                brand()
                Spacer(Modifier.height(12.dp))
            }
            Row(Modifier.padding(end = com.tmplayer.ui.components.LocalWindowControlsInset.current), verticalAlignment = Alignment.CenterVertically) {
                if (wide) {
                    brand()
                    Spacer(Modifier.width(24.dp))
                }
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.weight(1f),
                    itemVerticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Two lines before a cut: "Step 1 of 6" was clipped with no ellipsis at twice the font on a 320 dp phone.
                    Text(group, style = MaterialTheme.typography.labelLarge, color = Tone.accent, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(
                        s.setupStep(position.index + 1, position.total),
                        style = MaterialTheme.typography.labelMedium,
                        color = Tone.muted,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                if (onSkipAll != null && onUseRecommended == null) {
                    QuietButton(s.setupSkipAll, modifier = Modifier.arrowsMoveFocus(), onClick = onSkipAll)
                } else {
                    // As tall as Skip all, so the bar under it does not move on the last step.
                    Spacer(Modifier.height(QUIET_HEIGHT))
                }
            }
            Spacer(Modifier.height(6.dp))
            StepBar(position)
            Spacer(Modifier.height(if (wide) 20.dp else 16.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) { content(wide) }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) QuietButton(s.setupBack, icon = true, modifier = Modifier.arrowsMoveFocus(), onClick = onBack)
                Spacer(Modifier.weight(1f))
                if (onUseRecommended != null) {
                    // Beside Next, not over it: the step's one main action stays Next, so this one
                    // stays tonal even under the focus (D-2).
                    SetupButton(
                        s.setupUseRecommended,
                        tonal = true,
                        modifier = Modifier.arrowsMoveFocus(),
                        onClick = onUseRecommended,
                    )
                    Spacer(Modifier.width(12.dp))
                }
                SetupButton(
                    primary,
                    primary = true,
                    // The arrows reach the answers from here on a desktop too, as a D-pad does.
                    modifier = (if (primaryFocus != null) Modifier.focusRequester(primaryFocus) else Modifier)
                        .onFocusChanged { if (it.isFocused) onPrimaryFocused() }
                        .arrowsMoveFocus(),
                    onClick = onPrimary,
                )
            }
        }
    }
}

/**
 * The bar under the group's name: a segment per step, the ones done and the current one in the
 * accent, with a wider gap where one group ends and the next begins. Read out as "Step n of m"
 * by the line above it, so the bar itself is silent.
 */
@Composable
private fun StepBar(position: FlowPosition) {
    Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
        var step = 0
        position.groups.filter { it > 0 }.forEachIndexed { g, size ->
            if (g > 0) Spacer(Modifier.width(GROUP_GAP))
            repeat(size) { i ->
                if (i > 0) Spacer(Modifier.width(STEP_GAP))
                val at = step++
                val color by animateColorAsState(
                    when {
                        at == position.index -> Tone.accent
                        at < position.index -> Tone.accent.copy(alpha = 0.45f)
                        else -> Tone.surfaceHigh
                    },
                    label = "stepBar",
                )
                Box(Modifier.weight(1f).height(BAR).clip(CircleShape).background(color))
            }
        }
    }
}

/**
 * A sideways swipe across a setup step or a tour page: towards the end of the reading direction
 * goes on ([onNext]), back towards its start goes to the step before ([onBack]). A short drag does
 * nothing, so a finger resting on an answer does not turn the page.
 */
internal fun Modifier.swipeSteps(onNext: () -> Unit, onBack: () -> Unit): Modifier = composed {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val threshold = with(LocalDensity.current) { SWIPE_TURN.toPx() }
    val next by rememberUpdatedState(onNext)
    val back by rememberUpdatedState(onBack)
    pointerInput(rtl, threshold) {
        var travelled = 0f
        detectHorizontalDragGestures(
            onDragStart = { travelled = 0f },
            onDragEnd = {
                if (abs(travelled) >= threshold) {
                    val towardsEnd = if (rtl) travelled > 0f else travelled < 0f
                    if (towardsEnd) next() else back()
                }
            },
        ) { change, amount ->
            travelled += amount
            change.consume()
        }
    }
}

/**
 * A text button with no fill until it has the focus: Back at the foot and Skip all at the top.
 * Quieter than [SetupButton]'s primary, so the step's one main action is never in doubt.
 */
@Composable
internal fun QuietButton(label: String, icon: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val shown by interactions.collectIsFocusVisibleAsState()
    val touch = deviceForm() == DeviceForm.Phone
    Row(
        modifier
            // Sinks a little under a finger or a click, and comes back up on release.
            .pressScale(interactions, com.tmplayer.ui.components.Micro.PillPressScale)
            .heightIn(min = QUIET_HEIGHT)
            .clip(CircleShape)
            .background(if (shown) Tone.focusFill else Color.Transparent)
            .focusRing(shown, CircleShape)
            .clickable(
                interactionSource = interactions,
                indication = if (touch) LocalIndication.current else null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (shown) Tone.onFocusFill else Tone.text
        if (icon) {
            Icon(TmIcons.ArrowBack, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        // Two lines before an ellipsis: squeezed beside another button at twice the font, "Enter it yourself"
        // was cut to "Enter it" with nothing to say a word was missing (phone audit, 2026-11-25).
        Text(label, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** The step's heading and the sentence under it, the same size and spacing on every step. */
@Composable
internal fun StepHeading(title: String, body: String?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = Tone.text, modifier = Modifier.semantics { heading() })
        if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
    }
}

private val WIDE_FROM = 560.dp
private val NARROW_MAX = 600.dp
private val WIDE_MAX = 1200.dp
/** Material's 48 dp touch target, and room for the label at the largest font. */
private val QUIET_HEIGHT: Dp = 48.dp
private val BAR = 4.dp
private val STEP_GAP = 3.dp
private val GROUP_GAP = 12.dp

/** How far a swipe has to travel before it turns the page. */
private val SWIPE_TURN = 56.dp
