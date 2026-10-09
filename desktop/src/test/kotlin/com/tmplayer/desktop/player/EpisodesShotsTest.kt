package com.tmplayer.desktop.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.use
import com.tmplayer.data.EpisodeNeighbours
import com.tmplayer.data.EpisodeOrder
import com.tmplayer.data.MediaItem
import com.tmplayer.data.WatchPoint
import com.tmplayer.ui.browse.SeriesWatch
import com.tmplayer.ui.player.EpisodesActions
import com.tmplayer.ui.player.EpisodesState
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The player's episode list (CP40) drawn: the bar with its Episodes button, the list over the
 * picture with the episode playing tinted and a watched tick, a second season's list, a window
 * too narrow for the two columns, right to left, and the Skip intro pill. With
 * TMPLAYER_SHOTS=<dir> the frames are written there as `player-episodes-<name>.png`.
 */
class EpisodesShotsTest {

    private fun episode(season: Int, number: Int, name: String) = MediaItem(
        chatId = 1, messageId = (season * 100 + number).toLong(), fileId = season * 100 + number,
        title = "Harbour Notes S%02dE%02d %s".format(season, number, name), sizeBytes = 1_400_000_000,
        durationSec = 2634, mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null,
        date = season * 100 + number, fileName = "Harbour.Notes.S%02dE%02d.%s.1080p.mkv".format(season, number, name.replace(' ', '.')),
    )

    private val items = listOf(
        episode(1, 1, "Low Tide"), episode(1, 2, "The Lighthouse"), episode(1, 3, "Fog"),
        episode(1, 4, "Night Ferry"), episode(1, 5, "Salt"), episode(1, 6, "Gulls"),
        episode(2, 1, "Return"), episode(2, 2, "Breakwater"), episode(2, 3, "Storm Warning"),
    )

    /** S01E01 and S01E02 watched, S01E03 half way. */
    private val watch = SeriesWatch(
        point = { if (it.messageId == 103L) WatchPoint(1_300_000, 2_634_000) else null },
        finished = { it.messageId == 101L || it.messageId == 102L },
    )

    private val actions = EpisodesActions({}, {}, {}, {}, {}, {})

    private fun state(playing: MediaItem, introEnd: Long? = 92_000): EpisodesState {
        val steps = EpisodeNeighbours.around(playing, items)
        return EpisodesState(
            series = steps.series!!,
            playing = playing,
            previous = steps.previous,
            previousLabel = steps.previousTag?.let { "Previous: ${it.label}" },
            next = steps.next,
            nextLabel = steps.nextTag?.let { "Next: ${it.label}" },
            previousCode = steps.previousTag?.code,
            nextCode = steps.nextTag?.code,
            autoplay = true,
            order = EpisodeOrder.Number,
            introEndMs = introEnd,
        )
    }

    @Test
    fun `the series comes with the steps, both seasons in it`() {
        val steps = EpisodeNeighbours.around(items[3], items)
        assertEquals(2, steps.series?.seasons?.size)
        assertEquals("S01E05", steps.nextTag?.code)
    }

    @Test
    fun shots() {
        shot("bar") { bar() }
        shot("sheet") { sheet(state(items[3])) }
        shot("sheet-season-2") { sheet(state(items[7])) }
        shot("sheet-narrow", width = 600, height = 800) { sheet(state(items[3], introEnd = null)) }
        shot("sheet-rtl") {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { sheet(state(items[3])) }
        }
        shot("skip-intro") {
            Box(Modifier.fillMaxSize().background(Color(0xFF203040))) { SkipIntroPill(lifted = false, onSkip = {}) }
        }
    }

    @Composable
    private fun sheet(state: EpisodesState) {
        Box(Modifier.fillMaxSize().background(Color(0xFF203040))) {
            EpisodesSheet(state, watch, actions, position = { 61_000 }, onClose = {})
        }
    }

    @Composable
    private fun bar() {
        val steps = EpisodeNeighbours.around(items[3], items)
        Box(Modifier.fillMaxSize().background(Color(0xFF203040))) {
            PlayerOverlay(
                visible = true,
                status = PlaybackStatus(opened = true, durationMs = 2_634_000, positionMs = 61_000, bufferedMs = 900_000, playing = true),
                title = "Harbour Notes",
                subtitle = "S01E04  ·  Night Ferry",
                episodes = steps,
                tracks = emptyList(),
                downloaded = null,
                showRemaining = false,
                fullscreen = false,
                menu = null,
                ignoreClicks = false,
                miniPlayerAvailable = true,
                alwaysOnTopAvailable = true,
                fromTelegram = true,
                onHoverControls = {},
                onBack = {},
                onTogglePlay = {},
                onSeekBy = {},
                onSeekTo = {},
                onEpisode = {},
                onToggleRemaining = {},
                onVolume = {},
                onToggleMute = {},
                onToggleFullscreen = {},
                onMiniPlayer = {},
                onOpenMenu = {},
                onCloseMenu = {},
                onMenuAction = {},
                onEpisodes = {},
            )
            SkipIntroPill(lifted = true, onSkip = {})
        }
    }

    private fun shot(name: String, width: Int = 1280, height: Int = 720, content: @Composable () -> Unit) {
        ImageComposeScene(width, height, Density(1f)) {
            PlayerTheme { content() }
        }.use { scene ->
            scene.render(0)
            Thread.sleep(300)
            scene.render(500_000_000)
            Thread.sleep(200)
            val png = scene.render(1_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
            assertTrue(png.size > 1000)
            System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() }?.let {
                File(it).apply { mkdirs() }.resolve("player-episodes-$name.png").writeBytes(png)
            }
        }
    }
}
