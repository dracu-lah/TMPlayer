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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
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

    // A separate window, for the same reason TvConfirm uses one: drawn inline this would sit
    // behind anything composed after it.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                // This menu is opened by a hold, so OK is still down as it appears; without this
                // the release would choose the first action on the viewer's behalf.
                .ignoreStrayRelease()
                .background(Color.Black.copy(alpha = 0.82f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .width(480.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(SurfaceDark)
                    .border(1.dp, TextMuted.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Focus has to live somewhere for the D-pad to work at all, so it starts here,
                // on something that does nothing when pressed.
                Column(
                    Modifier
                        .padding(start = 4.dp, bottom = 8.dp)
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

                actions.forEach { action -> MenuRow(action = action) }

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

@Composable
private fun MenuRow(action: MenuAction, modifier: Modifier = Modifier) {
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
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(
                interactionSource = interactions,
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
                    color = if (focused) Color.White.copy(alpha = 0.85f) else TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val Danger = Color(0xFFE5484D)
