package com.tmplayer.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme as M3MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Danger
import com.tmplayer.ui.theme.Tone
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
 * Nothing is selected when it opens: the hold that opened it ends in a release, and anything
 * focused would be run without being chosen. Focus sits on the heading, and Down reaches the list.
 */
@Composable
fun TvMenu(
    title: String,
    actions: List<MenuAction>,
    onDismiss: () -> Unit,
    subtitle: String? = null,
) {
    val heading = remember { FocusRequester() }
    val touch = isTouch()

    if (touch) {
        TouchMenuSheet(title, subtitle, actions, onDismiss)
        return
    }

    // A separate window, for the same reason TvConfirm uses one: drawn inline this would sit
    // behind anything composed after it.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                // This menu is opened by a hold, so OK is still down as it appears; without this
                // the release would choose the first action on the viewer's behalf.
                .ignoreStrayRelease()
                .background(Color.Black.copy(alpha = 0.82f)),
            contentAlignment = Alignment.Center,
        ) {
            val panel = min(maxWidth - PhonePad.Side * 2, MENU_MAX)

            Column(
                Modifier
                    .width(panel)
                    .clip(RoundedCornerShape(Corner.ExtraLarge))
                    .background(Tone.surface)
                    .border(1.dp, Tone.muted.copy(alpha = 0.25f), RoundedCornerShape(Corner.ExtraLarge))
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    Modifier
                        .padding(start = 4.dp, bottom = 8.dp)
                        // Focus has to live somewhere for the D-pad to work at all, so it starts
                        // here, on something that does nothing when pressed.
                        .focusRequester(heading)
                        .focusable(),
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

                actions.forEach { action -> MenuRow(action = action, touch = false) }

                Text(
                    "Press Down to choose, or Back to close this.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Tone.muted,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
            }
        }

        LaunchedEffect(Unit) { runCatching { heading.requestFocus() } }
    }
}

/**
 * The same menu as the sheet a phone expects.
 *
 * [ModalBottomSheet] brings the drag handle, the working scrim, the swipe-down dismiss and the
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
    // No container colour and no drag handle of our own: the sheet's defaults are the scheme's,
    // and the handle it draws is the one every other sheet on the phone draws, which is how a
    // thumb already knows this thing can be pulled down.
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
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

