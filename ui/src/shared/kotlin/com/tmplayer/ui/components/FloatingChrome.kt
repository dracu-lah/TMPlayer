package com.tmplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.focusRing
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.data.DeviceForm
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.floatingBorder

/**
 * The phone's floating chrome, outside the main bar: the round Back (or Close) button at the top
 * start of a page, and the sticky bar of a page's own actions at its foot.
 *
 * Both are drawn the way the main floating bar is (a pill or a circle in [FloatingTone.menu], the
 * floating hairline and a soft shadow), so a page's controls read as one family wherever they are.
 * A large phone is held from below, so everything a page does sits at the bottom; Back stays at the
 * top start, where every Android user looks for it.
 */
object FloatingChrome {
    /** The round Back button. */
    val Button = 44.dp
    val ButtonIcon = 22.dp

    /**
     * The detail page's pair over its picture, Back at the top start and Cast at the top end: a
     * full 48 dp touch target each, the circle and the icon a size up from [Button] so they read
     * over a busy backdrop, and the same as each other (user, 2026-10-19).
     */
    val PageButton = 48.dp
    val PageButtonIcon = 24.dp

    /**
     * The sticky bar, and the same in a phone held sideways, where height is what is short. Each
     * leaves the same room round its buttons on every side: 4 dp, and 2 dp when compact.
     */
    val Bar = 56.dp
    val CompactBar = 52.dp

    /** The sticky bar when its icons carry a caption under them. */
    val LabelledBar = 60.dp

    /** A sticky bar's button ([FloatingBarIcon]), and one with its caption under the glyph. */
    val Icon = 48.dp
    val LabelledIcon = 52.dp

    /** A captioned button on the desktop bar: one roomy width for all of them, a gap of [DesktopBarGap] between (user, 2026-11-25). */
    val DesktopSlot = 88.dp
    val DesktopBarGap = 12.dp

    /** Between the sticky bar and the navigation bar (or the keyboard). */
    val Margin = 12.dp

    /** Between the sticky bar and the last row of a list scrolled to its end. */
    val Gap = 8.dp

    /** Where the round Back button stands, from the status bar and the screen's start edge. */
    val Inset = 12.dp

    val Shadow = 8.dp

    /** How much a list under a sticky bar pads its end by, the navigation bar not included. */
    fun clearance(compact: Boolean, labelled: Boolean = false): Dp =
        Margin + (if (compact) CompactBar else if (labelled) LabelledBar else Bar) + Gap
}

/** True on a phone, where pages carry the floating chrome; the television and the desktop keep their own. */
@Composable
fun usesFloatingChrome(): Boolean = deviceForm() == DeviceForm.Phone

/**
 * A round floating button: the page's Back, or Close while a selection is on. Over the content, so
 * it is always there to press however far the page has scrolled. Its [label] is its tooltip
 * ([IconTip], under it, since it stands at the top), with [shortcut] beside it on the desktop.
 */
@Composable
fun FloatingRoundButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shortcut: String? = null,
    size: Dp = FloatingChrome.Button,
    iconSize: Dp = FloatingChrome.ButtonIcon,
    /** On: drawn filled in the hero colour (Cast while a TV is connected), and said so to TalkBack. */
    active: Boolean = false,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val fill by androidx.compose.animation.animateColorAsState(if (active) Tone.hero else FloatingTone.menu, Motion.standard(Motion.Fast), label = "roundFill")
    val tint by androidx.compose.animation.animateColorAsState(if (active) Tone.onHero else Tone.text, Motion.standard(Motion.Fast), label = "roundTint")
    // The modifier goes on a Box of its own: a tooltip box puts what it is given on the anchor
    // inside it, so an align() handed to it lined the anchor up within itself and the button
    // stayed at the top start (the detail page's Cast sat over Back that way).
    Box(modifier) {
        IconTip(label, shortcut = shortcut, side = TipSide.Below) {
            Box(
                Modifier
                    .pressScale(interactions)
                    .size(size)
                    .shadow(FloatingChrome.Shadow, CircleShape)
                    .clip(CircleShape)
                    .background(fill)
                    .border(floatingBorder(), CircleShape)
                    .focusRing(focused, CircleShape)
                    .clickable(interactionSource = interactions, indication = ripple(), role = Role.Button, onClick = onClick)
                    .semantics {
                        contentDescription = label
                        if (active) selected = true
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
            }
        }
    }
}

/** [FloatingRoundButton] in its place at the top start of the screen, clear of the status bar and a cutout. */
@Composable
fun FloatingBackButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = FloatingChrome.Button,
    iconSize: Dp = FloatingChrome.ButtonIcon,
) {
    FloatingRoundButton(
        icon = icon,
        label = label,
        onClick = onClick,
        modifier = modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(start = FloatingChrome.Inset, top = 10.dp),
        size = size,
        iconSize = iconSize,
    )
}

/**
 * [FloatingRoundButton] at the top end of the screen, the mirror of [FloatingBackButton]: a page's
 * one action over its picture (the detail page's Cast), clear of the status bar and a cutout.
 */
@Composable
fun FloatingEndButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = FloatingChrome.Button,
    iconSize: Dp = FloatingChrome.ButtonIcon,
    active: Boolean = false,
) {
    FloatingRoundButton(
        icon = icon,
        label = label,
        onClick = onClick,
        modifier = modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(end = FloatingChrome.Inset, top = 10.dp),
        size = size,
        iconSize = iconSize,
        active = active,
    )
}

/**
 * [FloatingEndButton] that draws [content] in place of an icon (WP-103): the account's picture at
 * the top end of the phone's Home and Downloads, where the gear was. The same insets and a full
 * [FloatingChrome.PageButton] target, with nothing drawn round [content] at rest: the picture keeps
 * its own outline. [content] is told when the keyboard's focus is on it (a tablet, a Chromebook or
 * an iPad keyboard), to draw its focus look; a screen reader hears [label], then [onClickLabel].
 */
@Composable
fun FloatingEndButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onClickLabel: String? = null,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    Box(
        modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            // The icon button's centre: its 44 dp circle at 12 dp and 10 dp from the corner.
            .padding(end = FloatingChrome.Inset - 2.dp, top = 8.dp),
    ) {
        IconTip(label, side = TipSide.Below) {
            Box(
                Modifier
                    .size(FloatingChrome.PageButton)
                    .pressScale(interactions)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = interactions,
                        indication = ripple(),
                        role = Role.Button,
                        onClickLabel = onClickLabel,
                        onClick = onClick,
                    )
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) { content(focused) }
        }
    }
}

/**
 * A page's own actions, in a pill at the foot of the screen that stays put while the page scrolls.
 *
 * It sits above the navigation bar and above the keyboard when one is up ([WindowInsets.safeDrawing]
 * takes in both), so a search field drawn in it rides up with the keyboard and comes back down
 * when the keyboard goes. [compact] is for a phone held sideways. [fill] stretches the pill across
 * the screen, for a search field or a selection's count and actions.
 */
@Composable
fun StickyActionBar(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    fill: Boolean = false,
    /** Its icons carry captions ([FloatingBarIcon]'s caption): the bar is [FloatingChrome.LabelledBar] high. */
    labelled: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(start = 16.dp, end = 16.dp, bottom = FloatingChrome.Margin),
        contentAlignment = Alignment.Center,
    ) {
        val height = if (compact) FloatingChrome.CompactBar else if (labelled) FloatingChrome.LabelledBar else FloatingChrome.Bar
        Row(
            (if (fill) Modifier.fillMaxWidth() else Modifier)
                .height(height)
                .shadow(FloatingChrome.Shadow, CircleShape)
                .clip(CircleShape)
                .background(FloatingTone.menu)
                .border(floatingBorder(), CircleShape)
                // The end buttons sit as far from the pill's round ends as from its top and
                // bottom, so a pressed or hovered one is concentric with the pill.
                .padding(horizontal = (height - if (labelled) FloatingChrome.LabelledIcon else FloatingChrome.Icon) / 2),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** True when the window is wide and short, a phone held sideways: the sticky bar goes compact. */
@Composable
fun rememberCompactChrome(): Boolean {
    val height = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height
    val density = androidx.compose.ui.platform.LocalDensity.current
    return with(density) { height.toDp() } < COMPACT_BELOW
}

/**
 * One icon in a sticky bar or an action pill: a 48 dp target around a 24 dp glyph, named for a
 * screen reader. It sinks under the finger ([pressScale]), and a new [icon] grows in where the old
 * one shrinks away ([SwapIcon]). With a [caption] the glyph has its name in small type under it,
 * as a tab bar does, and the button widens to fit it (the bar is then [StickyActionBar]'s labelled
 * height).
 */
@Composable
fun FloatingBarIcon(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Tone.text,
    /** Set for a toggle (the star): read out as on or off. */
    checked: Boolean? = null,
    enabled: Boolean = true,
    /** A word under the glyph; null for the glyph alone. */
    caption: String? = null,
    /** Draws the glyph in place of [icon], for one that animates on its own (the star). */
    glyph: (@Composable () -> Unit)? = null,
    /** The desktop's key for it, shown in its tooltip. */
    shortcut: String? = null,
    /** The least width of a captioned button; equal across a bar that gives every button the same. */
    slotWidth: Dp = FloatingChrome.LabelledIcon,
) {
    val interactions = remember { MutableInteractionSource() }
    val ink = if (enabled) tint else tint.copy(alpha = 0.38f)
    // A glyph alone is named in a tooltip ([IconTip]); one with its caption already says what it
    // is, and has a tooltip only for the desktop's key.
    if (caption != null && shortcut == null) {
        BarIconBody(icon, label, onClick, modifier, ink, checked, enabled, caption, glyph, interactions, slotWidth)
    } else {
        IconTip(label, modifier, shortcut = shortcut) {
            BarIconBody(icon, label, onClick, Modifier, ink, checked, enabled, caption, glyph, interactions, slotWidth)
        }
    }
}

@Composable
private fun BarIconBody(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    ink: Color,
    checked: Boolean?,
    enabled: Boolean,
    caption: String?,
    glyph: (@Composable () -> Unit)?,
    interactions: MutableInteractionSource,
    slotWidth: Dp,
) {
    val focused by interactions.collectIsFocusVisibleAsState()
    Box(
        modifier
            .then(if (caption == null) Modifier.size(FloatingChrome.Icon) else Modifier.height(FloatingChrome.LabelledIcon).widthIn(min = slotWidth))
            .pressScale(interactions)
            // A pill like the bar round it, so its ends follow the bar's at the same inset.
            .clip(CircleShape)
            .focusRing(focused, CircleShape)
            .clickable(interactionSource = interactions, indication = ripple(), enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                role = Role.Button
                if (checked != null) selected = checked
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = if (caption == null) 0.dp else 6.dp)) {
            if (glyph != null) glyph() else SwapIcon(icon, ink)
            if (caption != null) {
                Text(
                    caption,
                    style = captionStyle(),
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

private val COMPACT_BELOW = 480.dp
