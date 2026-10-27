package com.tmplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.UpdateScheduler
import com.tmplayer.data.WatchedStore
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.DesktopWatchCache
import com.tmplayer.ui.browse.BrowseSection
import com.tmplayer.ui.browse.BrowseTab
import com.tmplayer.ui.browse.HistoryTab
import com.tmplayer.ui.components.SelectableIcon
import com.tmplayer.ui.components.StateAction
import com.tmplayer.ui.components.UiState
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The selected places filling in with their drop, and the empty pages' picture drawing itself in,
 * frame by frame on the desktop's own renderer: every filled twin at bar size and at twice it, in
 * both themes and right to left, the floating bar moving from Chats to Home, and the empty states
 * of a listing, History and an error. With TMPLAYER_SHOTS=<dir> the frames are written there as
 * `fill-*.png` and `empty-*.png`.
 */
class SelectedAndEmptyShotsTest {

    private val settings = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-fill").resolve(SettingsStore.FILE_NAME).toFile()),
    )
    private val dir = Files.createTempDirectory("tm-fill-extras").toFile()
    private val shell = ShellState(
        settings,
        object : DownloadRunner {
            override fun download(request: DownloadRequest) = Unit
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        },
        WatchedStore(WatchedStore.openDataStore(Files.createTempDirectory("tm-fill-watched").resolve(WatchedStore.FILE_NAME).toFile())),
        services = {
            DesktopExtras(
                prefs = DesktopPrefs(dir.resolve("desktop.properties")),
                updates = UpdateScheduler(settings.updatePrefs, check = { _, _ -> false }, onSkip = {}),
                watchCache = DesktopWatchCache(settings, { dir.resolve("cache") }),
            )
        },
    )

    private val twins = listOf(
        TmIcons.Home, TmIcons.Chats, TmIcons.Settings, TmIcons.Search, TmIcons.StarOutline, TmIcons.Bookmark,
        TmIcons.Folder, TmIcons.Clock, TmIcons.Play, TmIcons.Person, TmIcons.Archive, TmIcons.Channel,
    )

    /** Each twin outlined, filling (the frames of the drop), and filled, at 24 and 48 dp. */
    @Test
    fun everyTwinFillsIn() {
        for (dark in listOf(true, false)) {
            for (rtl in listOf(false, true)) {
                var chosen by mutableStateOf(false)
                val name = "fill-strip-${if (dark) "dark" else "light"}${if (rtl) "-rtl" else ""}"
                ImageComposeScene(1520, 230, Density(2f)) {
                    TmMaterialTheme(dark = dark) {
                        CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                            Surface(Modifier.fillMaxSize(), color = Tone.background) {
                                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    for (size in listOf(24, 48)) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            for (icon in twins) {
                                                SelectableIcon(icon, chosen, tint = if (chosen) Tone.accent else Tone.text, size = size.dp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }.use { scene ->
                    scene.render(0)
                    save("$name-0-outline.png", scene.render(16_000_000).png())
                    chosen = true
                    val start = 100_000_000L
                    scene.render(start)
                    for (ms in listOf(60, 120, 180, 260, 400)) {
                        save("$name-${ms}ms.png", scene.render(start + ms * 1_000_000L).png())
                    }
                    save("$name-filled.png", scene.render(start + 1_500_000_000L).png())
                    chosen = false
                    scene.render(start + 1_600_000_000L)
                    save("$name-left-80ms.png", scene.render(start + 1_680_000_000L).png())
                    save("$name-left.png", scene.render(start + 2_500_000_000L).png())
                }
            }
        }
    }

    /** The floating bar as Home is chosen from Chats: the highlight glides, Home fills. */
    @Test
    fun barFillsTheChosenPlace() {
        for (dark in listOf(true, false)) {
            val theme = if (dark) "dark" else "light"
            shell.showChats(BrowseSection.of(BrowseTab.Chats))
            ImageComposeScene(900, 200, Density(1.5f)) {
                TmMaterialTheme(dark = dark) {
                    Surface(Modifier.fillMaxSize(), color = Tone.background) {
                        NavFrame(shell, null, null) { Box(Modifier.fillMaxSize()) }
                    }
                }
            }.use { scene ->
                scene.render(0)
                save("fill-bar-$theme-chats.png", scene.render(1_000_000_000).png())
                shell.go(Destination.Home)
                val start = 1_100_000_000L
                scene.render(start)
                for (ms in listOf(80, 160, 260)) save("fill-bar-$theme-home-${ms}ms.png", scene.render(start + ms * 1_000_000L).png())
                save("fill-bar-$theme-home.png", scene.render(start + 1_500_000_000L).png())
            }
        }
    }

    /** Settings' "Icons only in the menu": the bar's places as round icon buttons, Settings chosen. */
    @Test
    fun compactBar() {
        kotlinx.coroutines.runBlocking { settings.setCompactNav(true) }
        try {
            for (dark in listOf(true, false)) {
                val theme = if (dark) "dark" else "light"
                shell.go(Destination.Settings)
                ImageComposeScene(900, 200, Density(1.5f)) {
                    TmMaterialTheme(dark = dark) {
                        Surface(Modifier.fillMaxSize(), color = Tone.background) {
                            NavFrame(shell, null, null) { Box(Modifier.fillMaxSize()) }
                        }
                    }
                }.use { scene ->
                    scene.render(0)
                    save("compact-bar-$theme.png", scene.render(1_500_000_000).png())
                }
            }
        } finally {
            kotlinx.coroutines.runBlocking { settings.setCompactNav(false) }
        }
    }

    /**
     * History's selection bar over a page under the floating bar: lifted by [footClearance], so
     * its buttons stand clear of the bar, which is drawn over the page (2026-10-27: the bar sat at
     * 16 dp and Remove was under it). The `before` shot keeps the old 16 dp for comparison.
     */
    @Test
    fun historySelectionBarClearsTheFloatingBar() {
        shell.go(Destination.History)
        val ids = listOf("a", "b", "c")
        for (lifted in listOf(false, true)) {
            var barBottom = 0f
            val selection = GridSelection(history = true) { ids.getOrNull(it) }
            selection.selectAll(ids.take(2))
            val density = Density(1.5f)
            ImageComposeScene(1440, 630, density) {
                TmMaterialTheme(dark = true) {
                    Surface(Modifier.fillMaxSize(), color = Tone.background) {
                        NavFrame(shell, null, null) {
                            Box(Modifier.fillMaxSize()) {
                                HistorySelectionBar(
                                    selection, ids, {},
                                    Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                                        .padding(bottom = if (lifted) footClearance() else 16.dp)
                                        .onGloballyPositioned { barBottom = it.boundsInRoot().bottom },
                                )
                            }
                        }
                    }
                }
            }.use { scene ->
                scene.render(0)
                save("history-selection-${if (lifted) "after" else "before"}.png", scene.render(1_500_000_000).png())
            }
            if (lifted) {
                // The floating bar's top edge: NavBarClearance is the bar, its margin and an 8 dp gap.
                val navTop = 630 - with(density) { (NavBarClearance - 8.dp).toPx() }
                assertTrue("selection bar ends at $barBottom, under the floating bar from $navTop", barBottom <= navTop)
            }
        }
    }

    /** The empty pages: half way through arriving, and at rest. */
    @Test
    fun emptyStatesDrawTheirPicture() {
        val pages: List<Pair<String, @Composable () -> Unit>> = listOf(
            "no-videos" to { StateBox(UiState.Empty("No playable videos in this chat.")) {} },
            "no-match" to { StateBox(UiState.Empty("Nothing in this chat matches “lecture”.", icon = TmIcons.SearchRemove)) {} },
            "hidden" to { StateBox(UiState.Empty("No videos here between 100 MB and 2 GB. Change the minimum and maximum size in Settings.", icon = TmIcons.FilterHorizontal)) {} },
            "error" to { StateBox(UiState.Error("Telegram did not answer."), onRetry = {}) {} },
            "watched" to { EmptyNote(HistoryTab.Watched.emptyIcon, "Nothing watched yet. Videos you finish, or mark as watched, appear here.") },
            "unread" to { EmptyNote(com.tmplayer.ui.browse.ChatFilter.Unread.emptyIcon, "Nothing unread. You're up to date.") },
            // The ways on: the same words across every chat, and back to the chats.
            "no-match-search-all" to { StateBox(UiState.Empty("Nothing in this chat matches “lecture”.", StateAction.SearchAllChats, icon = TmIcons.SearchRemove), onAction = {}) {} },
            "watched-go-to-chats" to { EmptyNote(HistoryTab.Watched.emptyIcon, "Nothing watched yet. Videos you finish, or mark as watched, appear here.", "Go to Chats") {} },
            "archives" to {
                ArchiveList(
                    listOf(
                        com.tmplayer.data.ArchiveFile(1, 3, 3, "Lectures 2024.zip", 734_003_200, 0, com.tmplayer.data.ArchiveKind.Zip),
                        com.tmplayer.data.ArchiveFile(1, 2, 2, "Course.part2.rar", 2_000_000_000, 0, com.tmplayer.data.ArchiveKind.Rar, volume = true),
                    ),
                    noVideos = true,
                    onExplain = {},
                )
            },
        )
        for ((dark, black) in listOf(true to false, false to false, true to true)) {
            val theme = if (black) "black" else if (dark) "dark" else "light"
            for ((name, page) in pages) {
                ImageComposeScene(720, 420, Density(1.5f)) {
                    TmMaterialTheme(dark = dark, black = black) {
                        Surface(Modifier.fillMaxSize().background(Tone.background), color = Tone.background) { page() }
                    }
                }.use { scene ->
                    scene.render(0)
                    if (name == "no-videos") {
                        for (ms in listOf(150, 350, 600, 950)) save("empty-$name-$theme-${ms}ms.png", scene.render(ms * 1_000_000L).png())
                    } else {
                        save("empty-$name-$theme-400ms.png", scene.render(400_000_000).png())
                    }
                    save("empty-$name-$theme.png", scene.render(3_000_000_000).png())
                }
            }
        }
    }

    private fun org.jetbrains.skia.Image.png(): ByteArray = encodeToData(EncodedImageFormat.PNG)!!.bytes

    private fun save(name: String, png: ByteArray) {
        assertTrue(png.size > 500)
        val out = System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        File(out).apply { mkdirs() }.resolve(name).writeBytes(png)
    }
}
