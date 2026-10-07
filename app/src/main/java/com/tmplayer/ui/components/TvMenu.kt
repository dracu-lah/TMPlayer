package com.tmplayer.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme as M3MaterialTheme
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Danger
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import com.tmplayer.ui.theme.floatingSurface
import com.tmplayer.ui.theme.focusRing

/** One line of a [TvMenu]. [detail] says what the action will do when the label cannot. */
data class MenuAction(
    val label: String,
    val icon: ImageVector,
    val detail: String? = null,
    val destructive: Boolean = false,
    val onSelect: () -> Unit,
)

/**
 * The list of things that can be done to whatever the viewer was standing on.
 *
 * A television remote has no second button, so every action beyond "open it" lives behind a hold of
 * OK, which the launcher and every other TV app already train people to try.
 *
 * The remote starts on the first line. The hold that opens it ends in a release, which the
 * window ignores ([FloatingWindow]'s ignoreRelease), so that line is not run without being chosen.
 * The heading stays put at the top and Close at the foot while a long list scrolls between them.
 *
 * It ends with Close at the bottom end ([SheetCloseButton]), as every picker and Material dialog
 * does, which Down from the last line reaches. Close is [onClose], which is [onDismiss] unless a
 * page of a bigger menu (the player's More) uses Back to step back a page and Close to shut it.
 */
@Composable
fun TvMenu(
    title: String,
    actions: List<MenuAction>,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    onClose: (() -> Unit)? = onDismiss,
) {
    val s = LocalStrings.current
    val first = remember { FocusRequester() }
    val touch = isTouch()

    if (touch) {
        TouchMenuSheet(title, subtitle, actions, onDismiss)
        return
    }

    // This menu is opened by a hold, so OK is still down as it appears; without ignoreRelease
    // the release would choose the first action on the viewer's behalf.
    FloatingWindow(onDismiss = onDismiss, ignoreRelease = true) {
        val panel = min(maxWidth - Tv.SafeH * 2, MENU_MAX)

        // The phone's sheet, in the middle of the screen: the sheet's fill, edge and corner.
        Column(
            Modifier
                .width(panel)
                // Inside the overscan: a long menu (the player's More) otherwise ran from the top
                // edge of the screen to the bottom, its heading and last row lost to the crop.
                .heightIn(max = maxHeight - Tv.SafeV * 2)
                .floatingSurface(FloatingTone.sheet)
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, bottom = 8.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Tone.text,
                    // A video is named by its file name, and one line of that is a prefix and
                    // three dots. The panel is bounded, so three lines is where it stops.
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tone.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // A list longer than the screen (the player's More, on a 540 dp television) scrolls
            // here, under the heading, and focus moving down brings each row into view.
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = Tv.FocusClearance),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                actions.forEachIndexed { index, action ->
                    MenuRow(action = action, touch = false, modifier = if (index == 0) Modifier.focusRequester(first) else Modifier)
                }
            }

            // The hint and Close share the last line: Close at the end, where a Material dialog
            // keeps its way out, one Down from the last action.
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    s.tvMenuHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = Tone.muted,
                    modifier = Modifier.weight(1f).padding(start = 4.dp, end = 12.dp),
                )
                if (onClose != null) SheetCloseButton(onClose)
            }
        }

        FocusOnOpen(first)
    }
}

/**
 * The same menu as the sheet a phone expects.
 *
 * [PhoneSheet] brings the drag handle, the working scrim, the swipe-down dismiss and the
 * bottom-of-the-screen position a thumb can actually reach, none of which has to be written here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TouchMenuSheet(
    title: String,
    subtitle: String?,
    actions: List<MenuAction>,
    onDismiss: () -> Unit,
) {
    // The handle is the one every other sheet on the phone draws, which is how a thumb already
    // knows this thing can be pulled down. A long name in landscape makes the menu taller than the
    // screen, so it scrolls, and only then keeps its scrolling to itself (see [keepScrollInSheet]):
    // a menu that fits is pulled down from anywhere.
    PhoneSheet(onDismissRequest = onDismiss) {
        val scroll = rememberScrollState()
        Column(
            Modifier
                .then(if (scroll.canScrollForward || scroll.canScrollBackward) Modifier.keepScrollInSheet() else Modifier)
                .verticalScroll(scroll)
                .padding(bottom = 24.dp),
        ) {
            // The heading is what the sheet is about rather than something that can be chosen, so
            // it sits in a section header's padding, not in a row, and it takes the scheme's text
            // colour rather than the content colour the sheet inherits. It gets as many lines as
            // it needs: a video's file name cut to one line is a row of dots that names nothing.
            Column(
                Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                M3Text(
                    title,
                    style = M3MaterialTheme.typography.titleLarge,
                    color = Tone.text,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    M3Text(
                        subtitle,
                        style = M3MaterialTheme.typography.bodyMedium,
                        color = Tone.muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            actions.forEach { action -> MenuRow(action = action, touch = true) }
        }
    }
}

@Composable
private fun MenuRow(action: MenuAction, touch: Boolean, modifier: Modifier = Modifier) {
    // The television's row is a filled tile that changes colour under focus, because focus is the
    // only thing it has to say where it is. The phone's is a list item on the sheet's own surface,
    // where a stack of coloured tiles would read as a stack of buttons rather than as a list.
    if (touch) {
        val destructive = action.destructive
        ListItem(
            modifier = modifier.clickable(onClick = action.onSelect),
            headlineContent = {
                M3Text(action.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
            },
            // Two lines: a phone's list is narrow enough that a sentence which fits on a
            // television lands here as half a sentence and three dots.
            supportingContent = action.detail?.let {
                { M3Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            },
            leadingContent = {
                M3Icon(action.icon, contentDescription = null, modifier = Modifier.size(24.dp))
            },
            colors = ListItemDefaults.colors(
                // The sheet is already a container, so a row on top of it is transparent and the
                // destructive one is told apart by its colour rather than by another surface.
                containerColor = Color.Transparent,
                headlineColor = if (destructive) Tone.danger else Tone.text,
                leadingIconColor = if (destructive) Tone.danger else Tone.muted,
                supportingColor = if (destructive) Tone.danger else Tone.muted,
            ),
        )
        return
    }

    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val background by animateColorAsState(
        targetValue = when {
            focused && action.destructive -> Danger
            focused -> Tone.focusFill
            else -> Tone.surfaceHigh
        },
        animationSpec = tween(140),
        label = "menuRow",
    )
    val foreground = when {
        focused -> Tone.onFocusFill
        action.destructive -> Danger
        else -> Tone.text
    }

    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.Medium))
            .background(background)
            .focusRing(focused, RoundedCornerShape(Corner.Medium))
            .clickable(
                interactionSource = interactions,
                // Nothing on a TV reacts to a press with a ripple, and the focus colour is the
                // whole feedback.
                indication = null,
                onClick = action.onSelect,
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            action.icon,
            contentDescription = null,
            tint = foreground,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.fillMaxWidth()) {
            Text(
                action.label,
                style = MaterialTheme.typography.titleMedium,
                color = foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (action.detail != null) {
                Text(
                    action.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (focused) Tone.onFocusFill.copy(alpha = 0.85f) else Tone.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** As wide as this menu ever gets, on any screen. */
private val MENU_MAX = 480.dp

