package com.tmplayer.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.Tone

/** The tag of the three figures' strip, for tests. */
const val VIEWING_STRIP_TAG = "viewingStrip"

/**
 * "Your viewing" at the top of the profile picture's menu (REELBOX-PARITY: a profile header): how
 * many videos are part way through, watched and starred chats there are, each a way to its list.
 * The same on the phone's popover and the desktop's; the television's account menu says the same
 * in its subtitle and its rows.
 */
@Composable
fun ViewingSummary(
    watching: Int,
    watched: Int,
    starred: Int,
    onWatching: () -> Unit,
    onWatched: () -> Unit,
    onStarred: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * One quiet strip (the phone's account sheet): no heading line, which the strip's own
     * description says to a screen reader, and less room round the three figures.
     */
    compact: Boolean = false,
) {
    val s = LocalStrings.current
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = if (compact) 2.dp else 6.dp)
            .then(if (compact) Modifier.semantics { contentDescription = s.profileHeading } else Modifier)
            .testTag(VIEWING_STRIP_TAG),
    ) {
        if (!compact) Text(s.profileHeading, style = MaterialTheme.typography.labelMedium, color = Tone.muted)
        Row(
            Modifier.fillMaxWidth().padding(top = if (compact) 0.dp else 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Count(watching, s.profileWatching, onWatching, Modifier.weight(1f))
            Count(watched, s.profileWatched, onWatched, Modifier.weight(1f))
            Count(starred, s.profileStarred, onStarred, Modifier.weight(1f))
        }
    }
}

/** One figure and its name; it fills under a press, the pointer or focus, as the menu's rows do. */
@Composable
private fun Count(value: Int, label: String, onClick: () -> Unit, modifier: Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val focused by interactions.collectIsFocusedAsState()
    val fill by animateColorAsState(
        if (pressed || hovered || focused) Tone.surfaceHigh else Color.Transparent,
        Motion.standard(Motion.Fast),
        label = "viewingCount",
    )
    val formatted = LocalStrings.current.formatter.number(value)
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(fill)
            .clickable(interactionSource = interactions, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$formatted $label" }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(formatted, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = Tone.text, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Tone.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
