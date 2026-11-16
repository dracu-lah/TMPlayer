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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
 * Home's empty page, worded for the viewer: the first run's card (forward a video to Saved
 * Messages) for an account that has not chosen its chats, and, once chats are chosen
 * ([AddedMode.Picked]), "Nothing to show from your chosen chats yet" with the way to
 * [AddChatsRequest] (ON-15): the forwarding advice would contradict the choice just made. The
 * phone, the television and the desktop all draw this one.
 */
@Composable
fun HomeEmptyCard(onOpenSaved: () -> Unit, modifier: Modifier = Modifier, focus: FocusRequester? = null) {
    val s = com.tmplayer.ui.i18n.LocalStrings.current
    val picked = com.tmplayer.data.AddedChats.choice.collectAsState().value.mode == com.tmplayer.data.AddedMode.Picked
    if (picked) {
        FirstVideoCard(
            title = s.homeNothingPickedTitle,
            body = s.homeNothingPickedBody,
            more = s.homeFirstMore,
            button = s.pickerChooseChats,
            onButton = { AddChatsRequest.ask() },
            modifier = modifier,
            focus = focus,
        )
    } else {
        FirstVideoCard(
            title = s.homeFirstTitle,
            body = s.homeFirstBody,
            more = s.homeFirstMore,
            button = s.homeFirstOpenSaved,
            onButton = onOpenSaved,
            modifier = modifier,
            focus = focus,
        )
    }
}

/**
 * The quiet card under Home's rows when no chat is starred: a star in an icon chip and how to
 * add one. Without [onChoose] (the television) it is never focusable: the remote has nothing to
 * do here, and the way to star a chat is in the chat list, which the words name. With [onChoose]
 * (the phone) a button takes the viewer to the chats to star them, so the card is not an
 * instruction to find a gesture (Raju 4.4).
 */
@Composable
fun FavouritesHintCard(
    text: String,
    start: Dp,
    end: Dp,
    modifier: Modifier = Modifier,
    buttonLabel: String? = null,
    onChoose: (() -> Unit)? = null,
) {
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
            IconChip(TmIcons.StarOutline, size = if (touch) 36.dp else 44.dp)
            Text(
                text,
                style = if (touch) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                color = Tone.muted,
                modifier = Modifier.weight(1f),
            )
        }
        if (onChoose != null && buttonLabel != null) {
            HeroPill(label = buttonLabel, onClick = onChoose, icon = TmIcons.StarOutline)
        }
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
 * In Recently added's place when it reads the added chats and none is there to read
 * ([com.tmplayer.data.HomeRow.Recent.noneChosen]): what is wrong and the way to the Add chats
 * picker ([AddChatsRequest]), whose button takes the remote's focus through [focus] when this is
 * Home's first row.
 */
@Composable
fun HomeNoneChosenCard(start: Dp, end: Dp, modifier: Modifier = Modifier, focus: FocusRequester? = null, onAddChats: () -> Unit = { AddChatsRequest.ask() }) {
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
            IconChip(TmIcons.ChatsMany, size = if (touch) 36.dp else 44.dp)
            Text(
                s.homeNoneAddedChosen,
                style = if (touch) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                color = Tone.muted,
                modifier = Modifier.weight(1f),
            )
        }
        HeroPill(label = s.pickerChooseChats, onClick = onAddChats, focus = focus, icon = TmIcons.PersonAdd)
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

/**
 * The one question about keeping the viewer's place on all their devices (FQ12), asked once as the
 * last item of Home: what is kept and where, "Yes, keep it" and "Not now". It never comes back
 * after it was shown: Settings, Account holds the same switch. On a phone the buttons stack and
 * are as wide as the card; on a television and the desktop they stand side by side, and the
 * remote reaches them like any other button (Down from the last row, Left and Right between them).
 */
@Composable
fun KeepPlaceCard(onYes: () -> Unit, onNotNow: () -> Unit, start: Dp, end: Dp, modifier: Modifier = Modifier, focus: FocusRequester? = null) {
    val s = com.tmplayer.ui.i18n.LocalStrings.current
    val touch = isTouch()
    // The card goes the moment a button is pressed; the remote or the keyboard must not be on it
    // then, or focus is lost with it. It moves up to the row above first (or is let go of).
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    fun leave() {
        if (!focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Up)) focusManager.clearFocus()
    }
    val yes = { leave(); onYes() }
    val notNow = { leave(); onNotNow() }
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = start, end = end)
            .clip(RoundedCornerShape(Corner.Large))
            .background(Tone.surface)
            .padding(horizontal = 16.dp, vertical = if (touch) 16.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (touch) 14.dp else 18.dp)) {
            IconChip(TmIcons.History, size = if (touch) 36.dp else 44.dp)
            Text(
                s.syncplaceCardTitle,
                style = (if (touch) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge).copy(fontWeight = FontWeight.SemiBold),
                color = Tone.text,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            s.syncplaceCardBody,
            style = if (touch) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
            color = Tone.muted,
        )
        Text(
            s.syncplaceCardHint,
            style = if (touch) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            color = Tone.muted,
        )
        // Stacked and as wide as the card where it is narrow (a phone upright); side by side where
        // there is room (a television, the desktop, a phone on its side).
        androidx.compose.foundation.layout.BoxWithConstraints {
            if (maxWidth < 480.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeroPill(label = s.syncplaceCardYes, onClick = yes, stretch = true, focus = focus, icon = TmIcons.Check)
                    HeroPill(label = s.syncplaceCardNo, onClick = notNow, stretch = true, icon = null, calm = true)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HeroPill(label = s.syncplaceCardYes, onClick = yes, focus = focus, icon = TmIcons.Check)
                    HeroPill(label = s.syncplaceCardNo, onClick = notNow, icon = null, calm = true)
                }
            }
        }
    }
}
