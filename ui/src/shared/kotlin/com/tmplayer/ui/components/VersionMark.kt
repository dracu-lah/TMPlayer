package com.tmplayer.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.data.VersionInfo
import com.tmplayer.data.VersionLine
import com.tmplayer.data.VersionState
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.focusRingOnTv

/** The tag of the compact version row's own target, for tests. */
const val VERSION_MARK_TAG = "versionMark"

/** The row that holds the mark and centres it (a test measures the group against it). */
const val VERSION_MARK_ROW_TAG = "versionMarkRow"

/** The tag of the amber update chip beside the mark, for tests. */
const val VERSION_MARK_UPDATE_TAG = "versionMarkUpdate"

/** The tag of the mark's picture, for tests. */
const val VERSION_MARK_ICON_TAG = "versionMarkIcon"

/** The mark's picture: small enough to sit on a text line, big enough to be the app's tile. */
private val MARK_SIZE = 18.dp

/**
 * The app's own tile at [size], the launcher icon's drawing, with the tile's corner in proportion.
 * Decorative: the name it stands beside says what it is. Settings' title and the version row use it.
 */
@Composable
fun AppTile(size: Dp, modifier: Modifier = Modifier) {
    Image(
        AppLogo.Mark,
        contentDescription = null,
        modifier = modifier.size(size).clip(RoundedCornerShape(size * TILE_CORNER)),
    )
}

/**
 * The version, said once and small (user, 2026-11-25): the app's own tile and "v1.28.7" as one group,
 * centred, on one line, with a small "Update available" beside them only while one is waiting (no "Up
 * to date", no "Checking", never where updates come from). It replaces the long "TMPlayer 1.28.7 (1280799), Updates come from
 * where you installed TMPlayer" sentence everywhere the app has a menu or a list to end; the build,
 * the update source and the copy-for-support line stay in About ([VersionFooter]).
 *
 * One shared component for the phone's account sheet, the tablet's card, Settings on every device,
 * the television's menu and the desktop's popover.
 *
 * - [onClick] makes the version a 48 dp button (the phone's goes to About; the television's copies
 *   the support line and passes [message] to say so). Null draws it read only, not a stop for a
 *   remote.
 * - While the state is [VersionState.Available] and [onUpdate] is given, the state's words are a
 *   button of their own in amber, so the call to action is never lost; on a television ([tv]) the
 *   update is its own menu row, so the state there is words only.
 * - [tv] draws the television's row look (fill and ring); [focusRequester] places focus on it.
 * - The row wraps under a large font and mirrors in RTL ([FlowRow]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VersionMark(
    info: VersionInfo,
    modifier: Modifier = Modifier,
    state: VersionState = rememberVersionState(),
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    onUpdate: (() -> Unit)? = null,
    tv: Boolean = false,
    focusRequester: FocusRequester? = null,
    /** Replaces the version for a moment ("Version details copied"). */
    message: String? = null,
) {
    val s = LocalStrings.current
    val compact = VersionLine.compact(info.version)
    // The compact row is the app's tile and the number, centred (user, 2026-11-25): no "Up to date", no
    // "Checking", no "Updates come from where you installed TMPlayer" (that is About's sentence, [VersionFooter]).
    // Only news shows beside it: a small "Update available".
    val stateText = if (state is VersionState.Available) s.updateStateAvailablePlain else null
    val split = onUpdate != null && state is VersionState.Available && !tv
    val shape = RoundedCornerShape(Corner.Medium)
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val ink = if (tv && focused) Tone.onFocusFill else Tone.muted

    val said = buildString {
        append(BRAND_NAME).append(' ').append(compact)
        if (stateText != null) append(", ").append(stateText)
    }
    var target = Modifier
        .heightIn(min = if (onClick != null) 48.dp else 32.dp)
        .then(if (tv && onClick != null) Modifier.clip(shape).background(if (focused) Tone.focusFill else Tone.surfaceHigh).focusRingOnTv(focused, shape) else Modifier)
        .then(if (focusRequester != null && onClick != null) Modifier.focusRequester(focusRequester) else Modifier)
    target = if (onClick != null) {
        target.then(
            if (tv) {
                Modifier.clickable(interactionSource = interactions, indication = null, role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
            } else {
                Modifier.clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
            },
        )
    } else {
        target
    }
    target = target
        .padding(horizontal = if (tv) 18.dp else 12.dp, vertical = 6.dp)
        .semantics(mergeDescendants = true) { contentDescription = message ?: said }
        .liveMessage()
        .testTag(VERSION_MARK_TAG)

    FlowRow(
        modifier.fillMaxWidth().testTag(VERSION_MARK_ROW_TAG),
        // The tile, the number and the chip as one group, centred in whatever holds the row.
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.Center,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Row(target, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppTile(MARK_SIZE, Modifier.testTag(VERSION_MARK_ICON_TAG))
            Text(
                message ?: buildString {
                    append(compact)
                    if (stateText != null && !split) append("  ·  ").append(stateText)
                },
                style = MaterialTheme.typography.labelLarge,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (split && message == null && stateText != null) {
            Text(
                stateText,
                style = MaterialTheme.typography.labelLarge,
                color = Tone.caution,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClick = onUpdate!!)
                    .padding(horizontal = 12.dp, vertical = 14.dp)
                    .testTag(VERSION_MARK_UPDATE_TAG),
            )
        }
    }
}
