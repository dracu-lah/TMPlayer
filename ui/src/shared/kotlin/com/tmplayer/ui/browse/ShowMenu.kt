package com.tmplayer.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.data.Series
import com.tmplayer.data.SeriesSeason
import com.tmplayer.data.ShowMarks
import com.tmplayer.i18n.Messages
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Floating
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
import com.tmplayer.ui.theme.floatingBorder
import com.tmplayer.ui.theme.focusRing
import kotlinx.coroutines.delay

/**
 * What a show's page and a show's menu can do besides play (HX20), wired by each platform to its
 * stores: what a video's page has had all along, for the show as a whole.
 */
class ShowActions(
    /** On Watch later as a show; null where the platform offers no Watch later. */
    val watchLater: Boolean? = null,
    /** Puts the show on Watch later (true) or takes it off. */
    val onWatchLater: (add: Boolean) -> Unit = {},
    /** Writes a Mark as watched or unwatched the viewer picked ([ShowMenu]); null leaves it out. */
    val onMark: ((ShowMarks.Choice) -> Unit)? = null,
    /** The chat the show was posted in, from a page or menu opened outside it; null leaves it out. */
    val onOpenChat: (() -> Unit)? = null,
    /** That chat's name, for "Go to" on the menu. */
    val chatTitle: String = "",
)

/**
 * A show's menu (HX20): the hold on a show's card, and the Watched button on its page, which
 * opens it at the marks ([marksOnly]). A small card over a scrim, on every platform alike, its first
 * line taking the remote on a television.
 *
 * The menu's lines: Details (the page, from a card), Watch later, Mark as watched (a second step)
 * and Go to the chat. The marks step lists [ShowMarks.choices] for [season] (the season a page
 * shows; null from a card, where only the whole show is offered), each naming how many episodes
 * it changes, so picking one is the confirmation: nothing is marked by opening the card or by a
 * slip of the remote, and Cancel or Back leaves everything as it was.
 */
@Composable
fun ShowMenu(
    series: Series,
    watch: SeriesWatch,
    actions: ShowActions,
    onDismiss: () -> Unit,
    season: SeriesSeason? = null,
    marksOnly: Boolean = false,
    /** Opens the show's page, from a card; null leaves Details out (the page itself). */
    onOpen: (() -> Unit)? = null,
) {
    val s = LocalStrings.current
    var marks by remember(series.key) { mutableStateOf(marksOnly) }
    val choices = remember(series, season, watch) { ShowMarks.choices(series, season, watch::finishedEpisode) }
    val lines: List<MenuLine> = if (marks) {
        choices.map { choice -> MenuLine(markLabel(choice, s), if (choice.watched) TmIcons.Check else TmIcons.Close, s.seriesEpisodesCount(choice.count)) { onDismiss(); actions.onMark?.invoke(choice) } }
    } else {
        buildList {
            onOpen?.let { open -> add(MenuLine(s.detailOpenDetails, TmIcons.Info, s.seriesSummary(series.seasons.size, series.episodeCount)) { onDismiss(); open() }) }
            actions.watchLater?.let { on ->
                add(
                    if (on) {
                        MenuLine(s.detailWatchLaterRemove, TmIcons.Close, null) { onDismiss(); actions.onWatchLater(false) }
                    } else {
                        MenuLine(s.detailWatchLaterAdd, TmIcons.Clock, s.detailWatchLaterAddDetail) { onDismiss(); actions.onWatchLater(true) }
                    },
                )
            }
            if (actions.onMark != null && choices.isNotEmpty()) {
                add(MenuLine(s.seriesMarkTitle, TmIcons.Check, null) { marks = true })
            }
            actions.onOpenChat?.let { chat ->
                add(MenuLine(s.detailOpenChat(actions.chatTitle), TmIcons.ArrowForward, s.detailOpenChatDetail) { onDismiss(); chat() })
            }
        }
    }
    MenuCard(
        title = if (marks) s.seriesMarkTitle else series.title,
        subtitle = if (marks) series.title else null,
        lines = lines,
        empty = if (marks && lines.isEmpty()) s.seriesAllWatched else null,
        onDismiss = onDismiss,
        cancel = s.commonCancel,
    )
}

/** "Mark season 2 as watched", "Mark the whole show as unwatched". */
internal fun markLabel(choice: ShowMarks.Choice, s: Messages): String = when (choice.scope) {
    ShowMarks.Scope.Season -> if (choice.watched) s.seriesMarkSeasonWatched(choice.season ?: 1) else s.seriesMarkSeasonUnwatched(choice.season ?: 1)
    ShowMarks.Scope.Show -> if (choice.watched) s.seriesMarkShowWatched else s.seriesMarkShowUnwatched
}

private class MenuLine(val label: String, val icon: ImageVector, val detail: String?, val onSelect: () -> Unit)

@Composable
private fun MenuCard(
    title: String,
    subtitle: String?,
    lines: List<MenuLine>,
    empty: String?,
    onDismiss: () -> Unit,
    cancel: String,
) {
    val first = remember { FocusRequester() }
    val tv = !isTouch()
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(min = 280.dp, max = 440.dp)
                .clip(Floating.DialogShape)
                .background(FloatingTone.dialog)
                .border(floatingBorder(), Floating.DialogShape)
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Tone.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Tone.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 20.dp))
            }
            empty?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
            Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                lines.forEachIndexed { index, line ->
                    MenuRow(line.label, line.icon, line.detail, line.onSelect, if (index == 0) Modifier.focusRequester(first) else Modifier)
                }
                MenuRow(cancel, null, null, onDismiss, if (lines.isEmpty()) Modifier.focusRequester(first) else Modifier)
            }
        }
    }
    // A remote lands on the first line; the dialog's window takes a frame or two to have focus.
    LaunchedEffect(tv, lines.size) {
        if (!tv) return@LaunchedEffect
        repeat(FOCUS_TRIES) {
            delay(FOCUS_TRY_MS)
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
}

@Composable
private fun MenuRow(label: String, icon: ImageVector?, detail: String?, onClick: () -> Unit, modifier: Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(if (focused) Tone.focusFill else androidx.compose.ui.graphics.Color.Transparent)
            .focusRing(focused, shape)
            .clickable(interactionSource = interactions, indication = androidx.compose.material3.ripple(), role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val ink = if (focused) Tone.onFocusFill else Tone.text
        if (icon != null) Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(22.dp)) else Box(Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (focused) ink else Tone.muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

private const val FOCUS_TRIES = 6
private const val FOCUS_TRY_MS = 60L
