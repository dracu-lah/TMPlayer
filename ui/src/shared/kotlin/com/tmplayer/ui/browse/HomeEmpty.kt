package com.tmplayer.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.components.EmptyArt
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone

/**
 * Home with nothing in it yet: one card on [Tone.surface] that says how to get a first video in.
 * The empty pages' picture ([EmptyArt]), the heading, [body] (where to forward a video), and when there is
 * somewhere to send the viewer, a bright pill ([HeroPill]), [button], which takes
 * the remote's focus through [focus]. Under a rule, the line on starring chats, with its own chip.
 *
 * The phone, the television and the desktop all draw this one, so a first run looks the same on
 * each; the caller centres it and lets it scroll.
 */
@Composable
fun FirstVideoCard(
    title: String,
    body: String,
    more: String,
    button: String?,
    onButton: () -> Unit,
    modifier: Modifier = Modifier,
    focus: FocusRequester? = null,
) {
    val touch = isTouch()
    Column(
        modifier
            .widthIn(max = if (touch) 480.dp else 600.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.ExtraLarge))
            .background(Tone.surface)
            .padding(horizontal = if (touch) 20.dp else 32.dp, vertical = if (touch) 24.dp else 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (touch) 12.dp else 16.dp),
    ) {
        EmptyArt(TmIcons.Bookmark, size = if (touch) 72.dp else 88.dp)
        Text(
            title,
            style = (if (touch) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium)
                .copy(fontWeight = FontWeight.Bold),
            color = Tone.text,
            textAlign = TextAlign.Center,
        )
        Text(
            body,
            style = if (touch) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleMedium,
            color = Tone.muted,
            textAlign = TextAlign.Center,
        )
        if (button != null) {
            HeroPill(
                label = button,
                onClick = onButton,
                focus = focus,
                icon = null,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        HorizontalDivider(color = Tone.outline, modifier = Modifier.padding(top = 8.dp))
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconChip(TmIcons.StarOutline)
            Text(
                more,
                style = if (touch) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                color = Tone.muted,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The quiet card under Home's rows when no chat is starred: a star in an icon chip and how to
 * add one. Never focusable: the remote has nothing to do here, and the way to star a chat is in
 * the chat list, which the words name.
 */
@Composable
fun FavouritesHintCard(text: String, start: Dp, end: Dp, modifier: Modifier = Modifier) {
    val touch = isTouch()
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = start, end = end)
            .clip(RoundedCornerShape(Corner.Large))
            .background(Tone.surface)
            .padding(horizontal = 16.dp, vertical = if (touch) 14.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (touch) 14.dp else 18.dp),
    ) {
        IconChip(TmIcons.StarOutline, size = if (touch) 36.dp else 44.dp)
        Text(
            text,
            style = if (touch) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
            color = Tone.muted,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Home with every row turned off in Settings ([com.tmplayer.data.HomeLayout.allOff]): says so,
 * with the way to Home's settings, which takes the remote's focus through [focus]. The caller
 * centres it, as [FirstVideoCard].
 */
@Composable
fun HomeOffCard(onSettings: () -> Unit, modifier: Modifier = Modifier, focus: FocusRequester? = null) {
    val s = com.tmplayer.ui.i18n.LocalStrings.current
    val touch = isTouch()
    Column(
        modifier
            .widthIn(max = if (touch) 480.dp else 600.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.ExtraLarge))
            .background(Tone.surface)
            .padding(horizontal = if (touch) 20.dp else 32.dp, vertical = if (touch) 24.dp else 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (touch) 12.dp else 16.dp),
    ) {
        EmptyArt(TmIcons.Home, size = if (touch) 72.dp else 88.dp)
        Text(
            s.homeOffTitle,
            style = (if (touch) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium)
                .copy(fontWeight = FontWeight.Bold),
            color = Tone.text,
            textAlign = TextAlign.Center,
        )
        Text(
            s.homeOffBody,
            style = if (touch) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleMedium,
            color = Tone.muted,
            textAlign = TextAlign.Center,
        )
        HeroPill(label = s.homeOpenSettings, onClick = onSettings, focus = focus, icon = TmIcons.Settings, modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * In Recently added's place when it reads chosen chats and none is there to read
 * ([com.tmplayer.data.HomeRow.Recent.noneChosen]): what is wrong and the way to Home's settings,
 * whose button takes the remote's focus through [focus] when this is Home's first row.
 */
@Composable
fun HomeNoneChosenCard(onSettings: () -> Unit, start: Dp, end: Dp, modifier: Modifier = Modifier, focus: FocusRequester? = null) {
    val s = com.tmplayer.ui.i18n.LocalStrings.current
    val touch = isTouch()
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = start, end = end)
            .clip(RoundedCornerShape(Corner.Large))
            .background(Tone.surface)
            .padding(horizontal = 16.dp, vertical = if (touch) 14.dp else 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (touch) 14.dp else 18.dp)) {
            IconChip(TmIcons.Home, size = if (touch) 36.dp else 44.dp)
            Text(
                s.homeNoneChosen,
                style = if (touch) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                color = Tone.muted,
                modifier = Modifier.weight(1f),
            )
        }
        HeroPill(label = s.homeOpenSettings, onClick = onSettings, focus = focus, icon = TmIcons.Settings)
    }
}

/** The settings pages' icon chip: the accent at 12 per cent behind the icon in the accent. */
@Composable
private fun IconChip(icon: androidx.compose.ui.graphics.vector.ImageVector, size: Dp = 36.dp, round: Boolean = false) {
    Box(
        Modifier
            .size(size)
            .clip(if (round) CircleShape else RoundedCornerShape(Corner.Medium))
            .background(Tone.accent.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Tone.accent, modifier = Modifier.size(size * 0.56f))
    }
}
