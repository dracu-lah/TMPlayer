package com.tmplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.components.LIBRARY_TILE_ICON
import com.tmplayer.ui.components.LIBRARY_TILE_LABEL
import com.tmplayer.ui.components.LIBRARY_TILE_NOTE
import com.tmplayer.ui.components.LIBRARY_TILE_TAG
import com.tmplayer.ui.components.LibraryTiles
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * The account popover's three library tiles (Favourites, History, Watch later), drawn as the
 * popover draws them (320 dp card, 4 dp inset) with counts and without, at font 1.0, 1.3 and 2.0,
 * at rest, under the pointer and under the keyboard's focus.
 *
 * The invariants: the three icons stand on one line and the three names on one line whether or not
 * a tile has a count (a tile with a count must not push its icon off the line of a tile with none);
 * inside a tile the icon, name and counts share one centre line; in the stacked rows (font past
 * 1.5) the icon is centred on its text block and the names and counts share one left edge.
 *
 * Pictures: set TMPLAYER_SHOTS to a folder and every case is saved there.
 */
@OptIn(ExperimentalTestApi::class)
class LibraryTilesAlignTest {

    private class Counts(val name: String, val starred: Int, val watching: Int, val watched: Int)

    private val countSets = listOf(
        Counts("all-12-3-1234", 12, 3, 1234),
        Counts("none", 0, 0, 0),
        Counts("starred-only-1", 1, 0, 0),
        Counts("watching-only-1", 0, 1, 0),
        Counts("watched-only-7", 0, 0, 7),
        Counts("long-count", 2_147_483_647, 123_456_789, 987_654_321),
    )

    @Composable
    private fun Card(counts: Counts, first: FocusRequester, fontScale: Float) {
        CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
            TmMaterialTheme(dark = true) {
                Box(Modifier.background(Tone.background).padding(16.dp)) {
                    Column(Modifier.width(320.dp).background(Tone.surface).padding(vertical = 8.dp).testTag("card")) {
                        LibraryTiles(
                            starred = counts.starred,
                            watching = counts.watching,
                            watched = counts.watched,
                            onFavourites = {},
                            onHistory = {},
                            onWatchLater = {},
                            modifier = Modifier.padding(horizontal = 4.dp),
                            firstFocus = first,
                        )
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.draw(counts: Counts, fontScale: Float): FocusRequester {
        val first = FocusRequester()
        setContent { Card(counts, first, fontScale) }
        waitForIdle()
        return first
    }

    private fun ComposeUiTest.bounds(tag: String): Rect = onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun ComposeUiTest.has(tag: String) = onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun part(tile: String, part: String) = "$LIBRARY_TILE_TAG-$tile-$part"

    private val tiles = listOf("favourites", "history", "watch-later")

    private fun same(what: String, values: List<Float>) {
        assertTrue("$what differ: $values", values.all { abs(it - values.first()) <= 1f })
    }

    private fun ComposeUiTest.check(counts: Counts, fontScale: Float) {
        val stacked = fontScale > 1.5f
        val icons = tiles.map { bounds(part(it, LIBRARY_TILE_ICON)) }
        val labels = tiles.map { bounds(part(it, LIBRARY_TILE_LABEL)) }
        val where = "${counts.name} x$fontScale"
        if (!stacked) {
            same("$where: the icons' tops", icons.map { it.top })
            same("$where: the names' tops", labels.map { it.top })
        }
        tiles.forEachIndexed { i, tile ->
            val notes = (0..1).filter { has(part(tile, "$LIBRARY_TILE_NOTE$it")) }.map { bounds(part(tile, "$LIBRARY_TILE_NOTE$it")) }
            if (stacked) {
                val edge = labels[i].left
                for (note in notes) assertTrue("$where $tile: a count starts at ${note.left}, the name at $edge", abs(note.left - edge) <= 1f)
                val top = labels[i].top
                val bottom = (notes.lastOrNull() ?: labels[i]).bottom
                assertTrue(
                    "$where $tile: the icon's centre ${icons[i].center.y} is not the text block's ${(top + bottom) / 2}",
                    abs(icons[i].center.y - (top + bottom) / 2) <= 1f,
                )
            } else {
                for (note in notes) assertTrue("$where $tile: a count is off the name's centre line", abs(note.center.x - labels[i].center.x) <= 1f)
                assertTrue("$where $tile: the icon is off the name's centre line", abs(icons[i].center.x - labels[i].center.x) <= 1f)
                // Counts sit directly under the name, one line after the other.
                var above = labels[i].bottom
                for (note in notes) {
                    assertTrue("$where $tile: the counts are not under the name", note.top >= above - 1f)
                    above = note.bottom
                }
            }
        }
        if (stacked) {
            same("$where: the three icons' left edges", icons.map { it.left })
            same("$where: the three names' left edges", labels.map { it.left })
        }
    }

    private fun ComposeUiTest.shot(name: String) {
        val out = System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        val file = File(out, name)
        file.parentFile?.mkdirs()
        javax.imageio.ImageIO.write(onNodeWithTag("card").captureToImage().toAwtImage(), "png", file)
    }

    private fun states(counts: Counts, fontScale: Float) = runComposeUiTest {
        val first = draw(counts, fontScale)
        val key = "x$fontScale-${counts.name}"
        // The picture first, so a failing case still leaves its picture to look at.
        shot("$key-rest.png")
        onNodeWithTag("$LIBRARY_TILE_TAG-history").performMouseInput { moveTo(center) }
        waitForIdle()
        shot("$key-hover.png")
        onNodeWithTag("$LIBRARY_TILE_TAG-history").performMouseInput { moveTo(Offset(-50f, -50f)) }
        runOnUiThread { first.requestFocus() }
        waitForIdle()
        shot("$key-focus.png")
        check(counts, fontScale)
    }

    @Test
    fun `font 1_0 keeps the three tiles level with and without counts`() {
        for (counts in countSets) states(counts, 1f)
    }

    @Test
    fun `font 1_3 keeps the three tiles level with and without counts`() {
        for (counts in countSets) states(counts, 1.3f)
    }

    @Test
    fun `font 2_0 stacks the rows on one left edge, the icon centred on its text`() {
        for (counts in countSets) states(counts, 2f)
    }
}
