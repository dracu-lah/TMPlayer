package com.tmplayer.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.tmplayer.ui.theme.Danger
import com.tmplayer.ui.theme.Accent
import com.tmplayer.ui.theme.SurfaceDark
import com.tmplayer.ui.theme.SurfaceRaised
import com.tmplayer.ui.theme.TextMuted
import com.tmplayer.ui.theme.TextPrimary

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
 * A television remote has no second button, so every action beyond "open it" has to live behind
 * either a hold of OK or a control taking up room on the screen. A hold is what the launcher and
 * every other TV app already train people to try, and it costs no space at all.
 *
 * Nothing is selected when it opens. The hold that opened it ends in a release, and on some
 * remotes in a second press, and either landing on an action would run something the viewer never
 * chose. Focus sits on the heading instead, and one press of Down puts it on the first action.
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
                    .clip(RoundedCornerShape(20.dp))
                    .background(SurfaceDark)
                    .border(1.dp, TextMuted.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
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
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                actions.forEach { action -> MenuRow(action = action, touch = false) }

                Text(
                    "Press Down to choose, or Back to close this.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
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
 * The panel it replaces floated in the middle of the screen with no visible way to close it, and
 * the tap-outside that was supposed to close it could not fire: the dark scrim was part of the
 * dialog's own content, so a tap on it landed on the content and was swallowed. The only way out
 * was the system back gesture, which is not something a menu should require anyone to know.
 *
 * [ModalBottomSheet] brings the drag handle, the working scrim, the swipe-down dismiss and the
 * bottom-of-the-screen position a thumb can actually reach, none of which had to be written here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TouchMenuSheet(
    title: String,
    subtitle: String?,
    actions: List<MenuAction>,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
    ) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                M3Text(
                    title,
                    style = M3MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    M3Text(
                        subtitle,
                        style = M3MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.padding(top = 4.dp))
            actions.forEach { action ->
                MenuRow(action = action, touch = true)
                Spacer(Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun MenuRow(action: MenuAction, touch: Boolean, modifier: Modifier = Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val background by animateColorAsState(
        targetValue = when {
            focused && action.destructive -> Danger
            focused -> Accent
            else -> SurfaceRaised
        },
        animationSpec = tween(140),
        label = "menuRow",
    )
    val foreground = when {
        focused -> Color.White
        action.destructive -> Danger
        else -> TextPrimary
    }

    Row(
        modifier
            .fillMaxWidth()
            // A row of one short label is only about 44dp tall, which is under what a fingertip
            // is entitled to. The remote does not care either way, so this is a floor, not a size.
            .then(if (touch) Modifier.heightIn(min = 56.dp) else Modifier)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(
                interactionSource = interactions,
                // Nothing on a TV reacts to a press with a ripple, and the focus colour is the
                // whole feedback. A finger gets no focus colour, so it gets the ripple instead.
                indication = if (touch) LocalIndication.current else null,
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
                    color = if (focused) Color.White.copy(alpha = 0.85f) else TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** As wide as this menu ever gets, on any screen. */
private val MENU_MAX = 480.dp

