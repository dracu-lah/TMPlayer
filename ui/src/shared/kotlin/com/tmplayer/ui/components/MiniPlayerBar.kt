package com.tmplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.player.MiniPlayerState
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.focusRing

/**
 * The mini player (U8): the video that was left with Back, docked above the bottom navigation.
 * A title, play or pause, an X, and a thin line for how far through it is. A tap anywhere else
 * expands the player at the same place, and a swipe down dismisses it. See [MiniPlayerState] for
 * when it shows; this only draws it.
 *
 * Phone and desktop only. Every control is at least 48 dp, and the bar holds no picture, so a
 * protected video shows nothing it should not.
 */
@Composable
fun MiniPlayerBar(
    title: String,
    playing: Boolean,
    progress: Float,
    onExpand: () -> Unit,
    onTogglePlay: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val shape = RoundedCornerShape(16.dp)
    val swipe = with(LocalDensity.current) { MiniPlayerState.DISMISS_SWIPE_DP.dp.toPx() }
    val expandLabel = s.playerMiniExpand
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Tone.surfaceHigh)
            .border(1.dp, Tone.outline, shape)
            // One swipe down of enough length, however slowly: not a fling, which a list's would be.
            .pointerInput(onDismiss) {
                var drag = 0f
                detectVerticalDragGestures(
                    onDragStart = { drag = 0f },
                    onDragEnd = { drag = 0f },
                    onDragCancel = { drag = 0f },
                ) { change, amount ->
                    drag += amount
                    if (drag >= swipe) {
                        change.consume()
                        drag = Float.NEGATIVE_INFINITY
                        onDismiss()
                    }
                }
            },
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val expandInteractions = remember { MutableInteractionSource() }
            val expandFocused by expandInteractions.collectIsFocusedAsState()
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
                    // A keyboard or remote on the title shows where it is (the bar's border is no mark).
                    .focusRing(expandFocused, RoundedCornerShape(12.dp))
                    .clickable(
                        interactionSource = expandInteractions,
                        indication = androidx.compose.foundation.LocalIndication.current,
                        role = Role.Button,
                        onClickLabel = expandLabel,
                        onClick = onExpand,
                    )
                    .semantics { contentDescription = "$expandLabel: $title" }
                    .padding(start = 16.dp, end = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    title,
                    color = Tone.text,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BarButton(
                icon = if (playing) TmIcons.PauseSolid else TmIcons.PlaySolid,
                label = if (playing) s.playerPause else s.playerPlay,
                onClick = onTogglePlay,
            )
            BarButton(icon = TmIcons.Close, label = s.playerMiniClose, onClick = onDismiss)
        }
        ProgressLine(progress, inset = 0.dp)
    }
}

@Composable
private fun BarButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .focusRing(focused, CircleShape)
            .pressScale(interactions)
            .clickable(interactionSource = interactions, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Tone.text, modifier = Modifier.size(24.dp))
    }
}
