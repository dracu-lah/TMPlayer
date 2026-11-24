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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.Tone

/** The tag of the row of three tiles, and the prefix of each tile's ("libraryTile-favourites"), for tests. */
const val LIBRARY_TILES_TAG = "libraryTiles"
const val LIBRARY_TILE_TAG = "libraryTile"

/**
 * Your library as ONE row of three equal tiles (user, 2026-11-25): Favourites, History and Watch
 * later, each an icon over its name, so the account menu does not spend three tall rows on them.
 * The counts the old "Your viewing" strip gave ride on the tiles they belong to: how many are
 * starred under Favourites, and how many are part way through and how many are watched under
 * History (whose page has the Watched tab). A count of nothing is not drawn.
 *
 * Every tile has the same width, the same label size (a fixed size, never shrunk to fit; a long
 * translation wraps to a second line) and the same height, a 48 dp target at the least. A
 * tile fills under a press, the pointer or the keyboard's focus; with a remote or a keyboard Left
 * and Right move between the three. [firstFocus] goes on the first tile.
 */
@Composable
fun LibraryTiles(
    starred: Int,
    watching: Int,
    watched: Int,
    onFavourites: () -> Unit,
    onHistory: () -> Unit,
    onWatchLater: () -> Unit,
    modifier: Modifier = Modifier,
    firstFocus: FocusRequester? = null,
) {
    val s = LocalStrings.current
    val number = s.formatter
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag(LIBRARY_TILES_TAG),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Tile(
            icon = TmIcons.StarOutline,
            label = s.browseTabFavourites,
            notes = listOfNotNull(starred.takeIf { it > 0 }?.let { s.navTileStarred(number.number(it)) }),
            onClick = onFavourites,
            tag = "favourites",
            modifier = Modifier.weight(1f).then(if (firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier),
        )
        Tile(
            icon = TmIcons.History,
            label = s.browseTabHistory,
            notes = listOfNotNull(
                watching.takeIf { it > 0 }?.let { s.navTileWatching(number.number(it)) },
                watched.takeIf { it > 0 }?.let { s.navTileWatched(number.number(it)) },
            ),
            onClick = onHistory,
            tag = "history",
            modifier = Modifier.weight(1f),
        )
        Tile(
            icon = TmIcons.Clock,
            label = s.browseTabWatchLater,
            notes = emptyList(),
            onClick = onWatchLater,
            tag = "watch-later",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Tile(
    icon: ImageVector,
    label: String,
    notes: List<String>,
    onClick: () -> Unit,
    tag: String,
    modifier: Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val focused by interactions.collectIsFocusedAsState()
    val fill by animateColorAsState(
        if (pressed || hovered || focused) Tone.surfaceHigh else Tone.surfaceHigh.copy(alpha = 0.45f),
        Motion.standard(Motion.Fast),
        label = "libraryTile",
    )
    val said = (listOf(label) + notes).joinToString(", ")
    Column(
        modifier
            .heightIn(min = TILE_MIN_HEIGHT.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(fill)
            .clickable(interactionSource = interactions, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = said }
            .padding(horizontal = 4.dp, vertical = 10.dp)
            .testTag("$LIBRARY_TILE_TAG-$tag"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Icon(icon, contentDescription = null, tint = Tone.text, modifier = Modifier.size(24.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = Tone.text,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        notes.forEach { note ->
            Text(
                note,
                style = MaterialTheme.typography.labelSmall,
                color = Tone.muted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A tile's least height: icon, name, and a count line or two. */
private const val TILE_MIN_HEIGHT = 84
