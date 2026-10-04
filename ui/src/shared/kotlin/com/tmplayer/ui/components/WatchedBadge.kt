package com.tmplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.theme.Tone

/**
 * The tick a video's artwork wears once it is on the "Watched" list, finished or marked by hand.
 *
 * A disc in the app's colour with a thin white edge, so it reads over any thumbnail, dark or
 * light, without a plate behind it. Shared by the phone, the television and the desktop so one
 * finished video looks the same wherever it is listed. Placement is the caller's: pass the
 * alignment and padding in [modifier].
 *
 * [size] is the disc's diameter: about 22 dp on a phone's dense grid, 28 dp on a full card.
 */
@Composable
fun WatchedBadge(
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Tone.accent)
            .border(1.5.dp, Color.White.copy(alpha = 0.9f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Check,
            contentDescription = "Watched",
            tint = Tone.onAccent,
            modifier = Modifier.size(size * 0.64f),
        )
    }
}
