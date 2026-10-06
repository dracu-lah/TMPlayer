package com.tmplayer.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.focusRing

/**
 * Posters start on with the first run's tour, before any page of it: Skip passes the Posters
 * page, and a skipped tour takes the defaults. The Posters page then says what is sent and to
 * whom, and is where a viewer turns it off. A tour asked for again from Settings changes nothing.
 */
@Composable
fun PostersDefaultOn(firstRun: Boolean) {
    val online = OnlineMetadata.current ?: return
    LaunchedEffect(firstRun) {
        if (firstRun && !DefaultOn.applied) {
            DefaultOn.applied = true
            online.setEnabled(true)
        }
    }
}

/**
 * The Posters page's switch: posters, cast, ratings and trailers from TMDB, TVmaze and AniList,
 * showing what is set. Nothing at all where the build has no online metadata.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun PostersSwitch(firstRun: Boolean, modifier: Modifier = Modifier) {
    val online = OnlineMetadata.current ?: return
    val s = LocalStrings.current
    val settings by online.settings.collectAsState()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Corner.Medium)
    val remote = !isTouch()
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) Tone.focusFill else if (remote) Tone.surfaceHigh else Color.Transparent)
            .focusRing(focused, shape)
            .toggleable(
                value = settings.enabled,
                interactionSource = interactions,
                indication = if (remote) null else androidx.compose.foundation.LocalIndication.current,
                role = Role.Switch,
                onValueChange = { online.setEnabled(it) },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                s.onboardingPostersSwitch,
                style = MaterialTheme.typography.titleMedium,
                color = if (focused) Tone.onFocusFill else Tone.text,
            )
            Text(
                s.onboardingPostersSwitchDetail,
                style = MaterialTheme.typography.bodySmall,
                color = if (focused) Tone.onFocusFill.copy(alpha = 0.85f) else Tone.muted,
            )
        }
        Spacer(Modifier.width(16.dp))
        // The row is the control; the switch only shows its state.
        Switch(checked = settings.enabled, onCheckedChange = null)
    }
}

/** Once per process: going back and forth through the tour must not undo the viewer's "off". */
private object DefaultOn {
    var applied = false
}
