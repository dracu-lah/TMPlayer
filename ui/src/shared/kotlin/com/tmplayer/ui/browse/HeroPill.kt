package com.tmplayer.ui.browse

import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.Tone

/**
 * Home's one bright action (the first-run card's button): a pill in [Tone.hero], near white on dark and near black on
 * light, [HERO_PILL_HEIGHT] tall and at most [HERO_PILL_MAX] wide. Under the remote or the
 * keyboard, focus swaps the fill to [Tone.focusFill]: no scale and no ring (user, 2026-11-01,
 * the carousel's Play and Details pills read calmer as plain fill swaps than with HX42's scale
 * and ring on top).
 */
@Composable
fun HeroPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    stretch: Boolean = false,
    focus: FocusRequester? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = TmIcons.Play,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val fill by animateColorAsState(
        when {
            focused -> Tone.focusFill
            hovered -> Tone.hero.copy(alpha = 0.88f)
            else -> Tone.hero
        },
        Motion.standard(Motion.Fast),
        label = "heroPillFill",
    )
    val ink by animateColorAsState(
        if (focused) Tone.onFocusFill else Tone.onHero,
        Motion.standard(Motion.Fast),
        label = "heroPillInk",
    )
    Row(
        modifier
            .then(if (stretch) Modifier.widthIn(max = HERO_PILL_MAX).fillMaxWidth() else Modifier.widthIn(min = 160.dp, max = HERO_PILL_MAX))
            .heightIn(min = HERO_PILL_HEIGHT)
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .clip(CircleShape)
            .background(fill)
            .clickable(interactionSource = interactions, indication = if (isTouch()) androidx.compose.foundation.LocalIndication.current else null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(26.dp).padding(end = 2.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = if (icon != null) 6.dp else 0.dp),
        )
    }
}

/** As tall as a thumb wants and a remote can find. */
val HERO_PILL_HEIGHT = 52.dp

/** On a tablet or a phone on its side, a full-width pill would be a bar; this is its widest. */
private val HERO_PILL_MAX = 420.dp
