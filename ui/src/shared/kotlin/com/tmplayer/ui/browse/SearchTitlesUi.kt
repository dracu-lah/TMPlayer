package com.tmplayer.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.data.ChatBrief
import com.tmplayer.data.TitleHit
import com.tmplayer.i18n.L
import com.tmplayer.online.PosterChoice
import com.tmplayer.online.WhereToWatch
import com.tmplayer.ui.components.TmAlertDialog
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone

// Any film or show by its name (HX41), as the three platforms draw it: a title with the copies
// the viewer has, or "Not in your chats", and the Where to watch sheet. Taps, focus and keys are
// the caller's, as for the result cards (SearchResultsUi.kt).

/** What a title says of the viewer's copies: "3 copies in 2 chats", "Might be one of your videos", "Not in your chats". */
fun titleHitStatus(hit: TitleHit): String = when {
    hit.inChats -> L.searchTitleCopies(hit.fileCount, hit.chatCount)
    !hit.notInChats -> L.searchTitleMaybe
    else -> L.searchNotInChats
}

/** "Night Train, 2019": a title as a button or a sheet names it. */
fun titleName(title: PosterChoice): String = listOfNotNull(title.title, title.year?.toString()).joinToString(", ")

/**
 * One title over the cards: its TMDB poster, the name, the facts that tell two titles of one name
 * apart ([posterChoiceFacts]), and what the viewer has of it, with the chats as avatars.
 * [highlighted] is the keyboard's or the remote's selection; [trailing] the phone's buttons.
 */
@Composable
fun SearchTitleCard(
    hit: TitleHit,
    chats: Map<Long, ChatBrief>,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    posterWidth: Dp = 48.dp,
    trailing: (@Composable () -> Unit)? = null,
) {
    val status = remember(hit) { titleHitStatus(hit) }
    val chatIds = remember(hit) { hit.groups.flatMap { it.chatIds }.distinct() }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.Medium))
            .background(if (highlighted) Tone.focusFill.copy(alpha = 0.18f) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PosterChoiceThumb(hit.title, posterWidth)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(hit.title.title, style = MaterialTheme.typography.titleSmall, color = Tone.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val facts = posterChoiceFacts(hit.title)
            if (facts.isNotEmpty()) {
                Text(facts, style = MaterialTheme.typography.bodySmall, color = Tone.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (chatIds.isNotEmpty()) AvatarStack(chatIds, chats, Modifier.weight(1f, fill = false))
                if (hit.notInChats) Icon(TmIcons.SearchRemove, contentDescription = null, tint = Tone.muted, modifier = Modifier.size(14.dp))
                Text(
                    status,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (hit.inChats) Tone.text else Tone.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("title-status"),
                )
            }
        }
        trailing?.invoke()
    }
}

/** The region a Where to watch list is for, in the viewer's language: "United Kingdom" for GB. */
fun regionName(code: String): String {
    val locale = java.util.Locale.getDefault()
    // By its tag rather than Locale.Builder: iOS's Locale is a type alias, which cannot name a nested class.
    return runCatching { java.util.Locale.forLanguageTag("und-$code").getDisplayCountry(locale) }
        .getOrNull()?.takeIf { it.isNotBlank() } ?: code
}

/**
 * What Where to watch says: the services by kind (stream, free, rent, buy), names only, with
 * JustWatch named as TMDB asks; or that nothing is listed in the viewer's region; or why it could
 * not be asked. Null [answer] is the question on its way.
 */
@Composable
fun WhereToWatchLines(answer: WhereToWatch?, modifier: Modifier = Modifier, textColor: Color = Tone.text) {
    val s = LocalStrings.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (answer) {
            null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(s.searchWatchLooking, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
            }
            is WhereToWatch.Found -> {
                val offers = answer.offers
                listOf(s.searchWatchStream to offers.stream, s.searchWatchFree to offers.free, s.searchWatchRent to offers.rent, s.searchWatchBuy to offers.buy)
                    .filter { it.second.isNotEmpty() }
                    .forEach { (kind, names) ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(kind, style = MaterialTheme.typography.labelLarge, color = Tone.muted)
                            Text(names.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = textColor)
                        }
                    }
                Text(regionName(offers.region), style = MaterialTheme.typography.bodySmall, color = Tone.muted)
                Text(s.searchWatchSource, style = MaterialTheme.typography.bodySmall, color = Tone.muted)
            }
            is WhereToWatch.NoneHere -> {
                Text(s.searchWatchNoneHere(regionName(answer.region)), style = MaterialTheme.typography.bodyMedium, color = textColor)
                Text(s.searchWatchSource, style = MaterialTheme.typography.bodySmall, color = Tone.muted)
            }
            WhereToWatch.Off, WhereToWatch.NoKey -> Text(s.searchWatchNeedsLookups, style = MaterialTheme.typography.bodyMedium, color = textColor)
            WhereToWatch.Unavailable -> Text(s.searchWatchUnavailable, style = MaterialTheme.typography.bodyMedium, color = textColor)
        }
    }
}

/**
 * Where to watch as a dialog, for the phone and the desktop (the television draws it in its own
 * panel): the title, the lists, and the honest ways on from "Not in your chats": search the chats
 * not added this once (HX62, never adding them), or try again when TMDB could not be reached.
 */
@Composable
fun WhereToWatchDialog(
    title: PosterChoice,
    answer: WhereToWatch?,
    canSearchOthers: Boolean,
    onSearchOthers: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalStrings.current
    TmAlertDialog(
        onDismissRequest = onClose,
        title = { Text(s.searchWhereToWatch) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(titleName(title), style = MaterialTheme.typography.titleMedium, color = Tone.text)
                WhereToWatchLines(answer, Modifier.testTag("where-to-watch"))
            }
        },
        confirmButton = { TextButton(onClick = onClose, modifier = Modifier.testTag("where-to-watch-close")) { Text(s.commonClose) } },
        dismissButton = when {
            answer == WhereToWatch.Unavailable -> {
                { TextButton(onClick = onRetry) { Text(s.commonTryAgain) } }
            }
            canSearchOthers -> {
                {
                    TextButton(onClick = { onClose(); onSearchOthers() }, modifier = Modifier.testTag("where-to-watch-search-others")) {
                        Icon(TmIcons.Search, contentDescription = null, modifier = Modifier.size(18.dp).padding(end = 4.dp))
                        Text(s.searchOtherChats)
                    }
                }
            }
            else -> null
        },
    )
}
