package com.tmplayer.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tmplayer.data.DetailAction
import com.tmplayer.data.MediaItem
import com.tmplayer.data.NameDiagnosis
import com.tmplayer.data.PosterWhy
import com.tmplayer.i18n.Messages
import com.tmplayer.online.MetaQuery
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.components.rememberCorrection
import com.tmplayer.ui.components.rememberMetaSettings
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
import com.tmplayer.ui.theme.focusRing

/*
 * "Why no poster?" (TS): one quiet line on a title page that has none, saying why in plain words
 * with the one action that fixes it. The reason is worked out in core ([PosterWhy]); this file is
 * the screens' side of it, shared by the phone, the television and the desktop.
 */

/**
 * Why [item] has no poster, or null: it has one, the answer is still coming, or the outcome is not
 * known (offline). [matched] and [settled] are the page's own, so a second lookup is never started.
 */
@Composable
fun rememberPosterWhy(item: MediaItem, matched: Boolean, settled: Boolean): PosterWhy.Why? {
    val online = OnlineMetadata.current ?: return null
    val lookupsOn = rememberMetaSettings(online).value.enabled
    val correction = rememberCorrection(item.id)
    return remember(item.id, matched, settled, lookupsOn, correction) {
        val query = correction?.title?.let { MetaQuery.of(it, null) } ?: item.metaQuery
        PosterWhy.of(
            lookupsOn = lookupsOn,
            markedWrong = correction?.wrongMatch == true,
            picked = correction?.pick != null,
            settled = settled,
            matched = matched,
            name = item.parsedName,
            query = query,
            remembered = query?.let { online.peek(it) },
        )
    }
}

/** The page's Fix match: the "Choose the right poster" action of [rows], or null where it is not offered. */
fun fixMatchOf(rows: List<DetailRow>): (() -> Unit)? =
    rows.firstOrNull { it.action == DetailAction.ChoosePoster }?.onSelect

/** The line's words for [why]. */
internal fun posterWhyText(why: PosterWhy.Why, s: Messages): String = when (why.reason) {
    PosterWhy.Reason.LookupsOff -> s.posterWhyOff
    PosterWhy.Reason.MarkedWrong -> s.posterWhyWrong
    PosterWhy.Reason.NotRecognised -> if (why.diagnosis == NameDiagnosis.TooShort) s.posterWhyNameShort else s.posterWhyNameNone
    PosterWhy.Reason.NothingFound -> s.posterWhyNothing
    PosterWhy.Reason.Unsure -> s.posterWhyUnsure
}

/**
 * The line and its one action: "Turn on lookups" where they are off, else "Fix match" (which
 * opens "Choose the right poster"; left out where the page offers none). A control, so a remote
 * reaches it and a finger finds a 48 dp target; at a large font the action drops under the words.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PosterWhyLine(
    why: PosterWhy.Why,
    onTurnOn: () -> Unit,
    onFix: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val tv = !isTouch()
    val action: (() -> Unit)? = if (why.turnsOnLookups) onTurnOn else onFix
    val label = if (why.turnsOnLookups) s.posterWhyOffAction else s.posterWhyFix
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            posterWhyText(why, s),
            style = if (tv) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
            color = Tone.muted,
        )
        if (action != null) {
            val interactions = remember { MutableInteractionSource() }
            val focused by interactions.collectIsFocusVisibleAsState()
            val shape = RoundedCornerShape(Corner.Small)
            Text(
                label,
                style = if (tv) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (focused) Tone.onFocusFill else Tone.accent,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(shape)
                    .then(if (focused) Modifier.background(Tone.focusFill) else Modifier)
                    .focusRing(focused, shape)
                    .semantics { role = Role.Button }
                    .clickable(interactionSource = interactions, indication = if (tv) null else androidx.compose.foundation.LocalIndication.current, onClick = action)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }
    }
}
