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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
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

/** The parts of a tile, tagged "libraryTile-favourites-icon", "-label", "-note0", "-note1", for tests. */
const val LIBRARY_TILE_ICON = "icon"
const val LIBRARY_TILE_LABEL = "label"
const val LIBRARY_TILE_NOTE = "note"

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
 * and Right move between the three. [firstFocus] goes on the first tile. The three are always one
 * height (the tallest tile's), their icons and names on shared lines whether or not a tile has a
 * count line: the counts hang under the name and only grow the tile downward.
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
    // At a very large font a third of the card is no room for "Favourites": it broke in the middle of the
    // word. The three then stand as full width rows, the icon at the start and the words beside it.
    val stacked = androidx.compose.ui.platform.LocalDensity.current.fontScale > STACK_FROM_SCALE
    val starredNotes = listOfNotNull(starred.takeIf { it > 0 }?.let { s.navTileStarred(number.number(it)) })
    val historyNotes = listOfNotNull(
        watching.takeIf { it > 0 }?.let { s.navTileWatching(number.number(it)) },
        watched.takeIf { it > 0 }?.let { s.navTileWatched(number.number(it)) },
    )
    val body: @Composable (Modifier) -> Unit = { each ->
        Tile(
            icon = TmIcons.StarOutline, label = s.browseTabFavourites, notes = starredNotes, onClick = onFavourites, tag = "favourites",
            modifier = each.then(if (firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier), stacked = stacked,
        )
        Tile(icon = TmIcons.History, label = s.browseTabHistory, notes = historyNotes, onClick = onHistory, tag = "history", modifier = each, stacked = stacked)
        Tile(icon = TmIcons.Clock, label = s.browseTabWatchLater, notes = emptyList(), onClick = onWatchLater, tag = "watch-later", modifier = each, stacked = stacked)
    }
    if (stacked) {
        Column(
            modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag(LIBRARY_TILES_TAG),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { body(Modifier.fillMaxWidth()) }
    } else {
        Row(
            // The tallest tile sets the row's height and the others stretch to it (below), so the three
            // stand level whether or not any has a count line.
            modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).height(IntrinsicSize.Min).testTag(LIBRARY_TILES_TAG),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) { body(Modifier.weight(1f)) }
    }
}

/** The font scale past which the tiles stand one under another. */
private const val STACK_FROM_SCALE = 1.5f

@Composable
private fun Tile(
    icon: ImageVector,
    label: String,
    notes: List<String>,
    onClick: () -> Unit,
    tag: String,
    modifier: Modifier,
    stacked: Boolean = false,
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
    if (stacked) {
        Row(
            modifier
                .heightIn(min = 56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(fill)
                .clickable(interactionSource = interactions, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = said }
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag("$LIBRARY_TILE_TAG-$tag"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = Tone.text, modifier = Modifier.size(24.dp).testTag("$LIBRARY_TILE_TAG-$tag-$LIBRARY_TILE_ICON"))
            androidx.compose.foundation.layout.Spacer(Modifier.size(16.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelLarge, color = Tone.text, modifier = Modifier.testTag("$LIBRARY_TILE_TAG-$tag-$LIBRARY_TILE_LABEL"))
                notes.forEachIndexed { i, note ->
                    Text(note, style = MaterialTheme.typography.labelMedium, color = Tone.muted, modifier = Modifier.testTag("$LIBRARY_TILE_TAG-$tag-$LIBRARY_TILE_NOTE$i"))
                }
            }
        }
        return
    }
    Column(
        modifier
            .fillMaxHeight()
            .heightIn(min = TILE_MIN_HEIGHT.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(fill)
            .clickable(interactionSource = interactions, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = said }
            .padding(horizontal = 4.dp, vertical = 12.dp)
            .testTag("$LIBRARY_TILE_TAG-$tag"),
        horizontalAlignment = Alignment.CenterHorizontally,
        // The icon starts at the same height in all three tiles, whatever counts they carry (user,
        // 2026-11-25: centring each tile's group put the three icons at three heights, a tile with
        // two count lines lifting its icon over a tile with none). The name hangs under the icon and
        // the counts under the name, so a count only ever grows the tile downward. The least height
        // is the icon and one name line, so with no count there is no empty foot.
        verticalArrangement = Arrangement.Top,
    ) {
        Icon(icon, contentDescription = null, tint = Tone.text, modifier = Modifier.size(24.dp).testTag("$LIBRARY_TILE_TAG-$tag-$LIBRARY_TILE_ICON"))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = Tone.text,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp).testTag("$LIBRARY_TILE_TAG-$tag-$LIBRARY_TILE_LABEL"),
        )
        notes.forEachIndexed { i, note ->
            Text(
                note,
                style = MaterialTheme.typography.labelSmall,
                color = Tone.muted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("$LIBRARY_TILE_TAG-$tag-$LIBRARY_TILE_NOTE$i"),
            )
        }
    }
}

/** A tile's least height: its padding, the icon and one line of name, so with no count it has no empty foot. */
private const val TILE_MIN_HEIGHT = 68
