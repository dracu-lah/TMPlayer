package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.MediaItem
import com.tmplayer.data.Release
import com.tmplayer.data.ReleaseAsset
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.WatchedStore
import com.tmplayer.data.UpdateScheduler
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.DesktopWatchCache
import androidx.compose.foundation.layout.padding
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.TmMaterialTheme
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface as SkSurface
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Draws the chat list and a chat's grid off screen with made-up chats and videos, as a check that
 * the shared Material 3 pieces compose on the JVM. With TMPLAYER_SHOTS=<dir> in the
 * environment the frames are written there as PNGs for a person to look at.
 */
class BrowseRenderTest {

    private val settings = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-render").resolve(SettingsStore.FILE_NAME).toFile()),
    )
    private val dir = Files.createTempDirectory("tm-render-extras").toFile()
    private val prefs = DesktopPrefs(dir.resolve("desktop.properties"))
    private val shell = ShellState(
        settings,
        object : DownloadRunner {
            override fun download(request: DownloadRequest) = Unit
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        },
        WatchedStore(WatchedStore.openDataStore(Files.createTempDirectory("tm-render-watched").resolve(WatchedStore.FILE_NAME).toFile())),
        services = {
            DesktopExtras(
                prefs = prefs,
                updates = UpdateScheduler(settings.updatePrefs, check = { _, _ -> false }, onSkip = {}),
                watchCache = DesktopWatchCache(settings, { dir.resolve("cache") }),
            )
        },
    )

    @Test
    fun chatList() {
        val chats = listOf(
            chat(1, "Saved Messages", ChatKind.Saved, 0, 0xFF2AABEE.toInt()),
            chat(2, "Film Club", ChatKind.Channel, 12, 0xFFE5484D.toInt()),
            chat(3, "Documentaries in 4K", ChatKind.Channel, 0, 0xFF46A758.toInt()),
            chat(4, "Weekend series", ChatKind.Group, 3, 0xFFF5A524.toInt()),
            chat(5, "Anna", ChatKind.Direct, 0, 0xFF8E4EC6.toInt()),
            chat(6, "Old lectures", ChatKind.Channel, 0, 0xFF0090FF.toInt()),
        )
        val png = render {
            Column(Modifier.fillMaxSize()) {
                PageHeader("Chats", "Everything, newest first")
                Column(Modifier.fillMaxSize()) {
                    chats.forEachIndexed { at, chat -> ChatRow(chat, favourite = at == 1, onOpen = {}, onStar = {}) }
                }
            }
        }
        save("chats.png", png)
    }

    @Test
    fun sidebarGroups() = kotlinx.coroutines.runBlocking {
        val folders = listOf(
            com.tmplayer.data.ChatFolderSummary(3, "Films"),
            com.tmplayer.data.ChatFolderSummary(7, "Family"),
        )
        val chats = listOf(
            chat(2, "Film Club", ChatKind.Channel, 12, 0xFFE5484D.toInt()),
            chat(4, "Weekend series", ChatKind.Group, 3, 0xFFF5A524.toInt()),
        )
        // On Chats with the Unread chip, so Chats is open because the viewer is in it; Watch is
        // open by default and Folders is the group left closed.
        shell.showChats(com.tmplayer.ui.browse.BrowseSection.of(com.tmplayer.ui.browse.BrowseTab.Chats))
        shell.chatFilter = com.tmplayer.ui.browse.ChatFilter.Unread
        for (dark in listOf(true, false)) {
            val png = render(update = NavUpdate("Update", "2.0.1"), dark = dark, folders = folders) {
                Column(Modifier.fillMaxSize()) {
                    PageHeader(com.tmplayer.ui.browse.BrowseTab.Chats.heading, com.tmplayer.ui.browse.BrowseTab.Chats.blurb)
                    ChoiceChips(
                        listOf(Choice("sort", shell.chatSort.label, shell.chatSort.icon, selected = false) {}) +
                            com.tmplayer.ui.browse.ChatFilter.entries.map {
                                Choice(it.name, it.label, it.icon, selected = it == shell.chatFilter) {}
                            },
                    )
                    chats.forEach { ChatRow(it, favourite = false, onOpen = {}, onStar = {}) }
                }
            }
            save(if (dark) "sidebar-dark.png" else "sidebar-light.png", png)
        }
        // Folding Watch as well leaves only the group the viewer is in, and the open state is
        // what the sidebar now holds.
        com.tmplayer.ui.browse.NavGroupState.toggle(com.tmplayer.ui.browse.NavGroup.Watch)
        save("sidebar-watch-folded.png", render(dark = true, folders = folders) {})
        com.tmplayer.ui.browse.NavGroupState.toggle(com.tmplayer.ui.browse.NavGroup.Watch)
        shell.chatFilter = com.tmplayer.ui.browse.ChatFilter.All
    }

    @Test
    fun mediaGrid() {
        val titles = listOf(
            "The Long Road S01E01", "The Long Road S01E02", "The Long Road S01E03",
            "Night Train (2019) 1080p", "Mountains, a film", "Lecture 7: Compilers",
            "Harbour Lights S02E05", "Harbour Lights S02E06",
        )
        val colours = listOf(0xFF2AABEE, 0xFFE5484D, 0xFF46A758, 0xFFF5A524, 0xFF8E4EC6, 0xFF0090FF, 0xFF12A594, 0xFFD6409F)
        val items = titles.mapIndexed { at, title ->
            MediaItem(
                chatId = 2, messageId = at + 1L, fileId = 0, title = title,
                sizeBytes = (300L + at * 170L) * 1024 * 1024, durationSec = 1500 + at * 431,
                mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = jpeg(colours[at].toInt()),
                date = 0, fileName = "$title.mkv", onDevice = at == 2,
            )
        }
        val png = render {
            Column(Modifier.fillMaxSize()) {
                PageHeader("Film Club", "8 videos")
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(shell.posterWidth),
                    contentPadding = PaddingValues(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    items(items, key = { it.id }) { MediaTile(shell, it, "Film Club") }
                }
            }
        }
        save("grid.png", png)
    }

    @Test
    fun home() {
        val colours = listOf(0xFF2AABEE, 0xFFE5484D, 0xFF46A758, 0xFFF5A524, 0xFF8E4EC6, 0xFF0090FF, 0xFF12A594, 0xFFD6409F)
        var id = 1L
        fun video(chatId: Long, title: String, date: Int) = MediaItem(
            chatId = chatId, messageId = id++, fileId = 0, title = title,
            sizeBytes = (300L + id * 37L) * 1024 * 1024, durationSec = 1500 + (id * 131 % 2000).toInt(),
            mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = jpeg(colours[(id % colours.size).toInt()].toInt()),
            date = date, fileName = "$title.mkv",
        )
        val chats = listOf(
            chat(2, "Film Club", ChatKind.Channel, 0, 0xFFE5484D.toInt()),
            chat(4, "Weekend series", ChatKind.Group, 0, 0xFFF5A524.toInt()),
            chat(6, "Old lectures", ChatKind.Channel, 0, 0xFF0090FF.toInt()),
        )
        val film = listOf("Night Train (2019) 1080p", "Mountains, a film", "The Quiet Year", "Paper Moons", "Low Tide", "Salt and Iron", "North Window")
            .mapIndexed { at, title -> video(2, title, 900 - at) }
        val series = (1..6).map { video(4, "Harbour.Lights.S02E%02d".format(it), 800 + it) } +
            (1..3).map { video(4, "The.Long.Road.S01E%02d".format(it), 700 + it) } + video(4, "Garden party", 650)
        val lectures = (1..9).map { video(6, "Lecture $it: Compilers", 500 - it) }
        val resume = listOf(film[1], series[2]).mapIndexed { at, item ->
            com.tmplayer.data.ResumeRecord(
                chatId = item.chatId, messageId = item.messageId, fileId = 1, title = item.title,
                chatTitle = chats.first { it.id == item.chatId }.title, sizeBytes = item.sizeBytes,
                durationSec = item.durationSec, positionMs = (at + 1) * 400_000L, durationMs = item.durationSec * 1000L,
                updatedAt = 10L - at,
            )
        }
        val art = listOf(film[1], series[2]).associateBy { com.tmplayer.data.SettingsStore.progressKey(it.chatId, it.messageId) }
        val rows = com.tmplayer.data.HomeRows.build(
            continueWatching = resume,
            favourites = com.tmplayer.data.HomeRows.favouriteChats(chats, setOf(2L, 4L)),
            loaded = mapOf(2L to film, 4L to series),
            recent = lectures,
        )
        shell.go(Destination.Home)
        for (dark in listOf(true, false)) {
            val png = render(dark = dark) {
                HomeRowsView(shell, rows, chats, art, onRowShown = {}, onArtWanted = {}, onRefresh = {})
            }
            save(if (dark) "home-dark.png" else "home-light.png", png)
        }
        shell.go(Destination.Chats)
    }

    /** A chat's posters with real release names, for the detail pane and the search shots. */
    private fun releaseItems(chatId: Long = 2, canBeSaved: Boolean = true): List<MediaItem> {
        val names = listOf(
            "Harbour.Lights.S02E05.1080p.WEB-DL.DDP5.1.x265-DEMO.mkv",
            "Night.Train.2019.2160p.BluRay.DV.HDR10.TrueHD.Atmos.7.1.HEVC.mkv",
            "Mountains (2024) Dual Audio 720p WEBRip AAC2.0 x264 ESub.mp4",
            "Lecture 7 Compilers.mp4",
            "Harbour.Lights.S02E06.1080p.WEB-DL.DDP5.1.x265-DEMO.mkv",
            "The.Quiet.Year.2023.1080p.WEB-DL.AAC2.0.H.264.mkv",
        )
        val colours = listOf(0xFF12A594, 0xFF2AABEE, 0xFF46A758, 0xFF8E4EC6, 0xFFE5484D, 0xFFF5A524)
        return names.mapIndexed { at, name ->
            MediaItem(
                chatId = chatId, messageId = 100L + at, fileId = 0, title = name,
                sizeBytes = (900L + at * 310L) * 1024 * 1024, durationSec = 2600 + at * 300,
                mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = jpeg(colours[at].toInt()),
                date = 1_790_000_000 - at * 86_400, fileName = name,
                caption = if (at == 0) "Season two, episode five. The storm reaches the harbour and Mara has to choose." else "",
                width = if (at == 0) 1920 else 0, height = if (at == 0) 1080 else 0,
                canBeSaved = canBeSaved,
            )
        }
    }

    /** The detail pane over a chat's grid, as a right click opens it; light and dark. */
    @Test
    fun detailPane() = kotlinx.coroutines.runBlocking {
        val items = releaseItems()
        val chats = listOf(chat(2, "Weekend series", ChatKind.Group, 0, 0xFFF5A524.toInt()))
        shell.noteChatTitles(chats)
        // 34:10 into the first, so the pane offers "Resume 34:10" and Start over.
        settings.saveResumePosition(2, 100, 2_050_000, 2_600_000, "")
        try {
            for (dark in listOf(true, false)) {
                shell.openDetail(DetailRequest(items[0], "Weekend series", selectThis = {}))
                save(if (dark) "desktop-detail-grid-dark.png" else "desktop-detail-grid-light.png", render(dark = dark) {
                    Box(Modifier.fillMaxSize()) {
                        VideoGrid(shell, items, "Weekend series")
                        DetailPaneHost(shell)
                    }
                })
            }
            // A video never started, with the facts of a 4K release.
            shell.openDetail(DetailRequest(items[1], "Weekend series"))
            save("desktop-detail-fresh.png", render { Box(Modifier.fillMaxSize()) { VideoGrid(shell, items, "Weekend series"); DetailPaneHost(shell) } })
            // A chat that restricts saving content: no download, select, hand off or link.
            val locked = releaseItems(canBeSaved = false)
            shell.openDetail(DetailRequest(locked[2], "Weekend series", selectThis = null))
            save("desktop-detail-protected.png", render { Box(Modifier.fillMaxSize()) { VideoGrid(shell, locked, "Weekend series"); DetailPaneHost(shell) } })
        } finally {
            shell.closeDetail()
            settings.clearResumePosition(2, 100)
        }
    }

    /** Home with a starred chat's poster opened: the pane offers to go to that chat. */
    @Test
    fun detailPaneFromHome() {
        val chats = listOf(chat(2, "Weekend series", ChatKind.Group, 0, 0xFFF5A524.toInt()))
        shell.noteChatTitles(chats)
        val items = releaseItems()
        val rows = com.tmplayer.data.HomeRows.build(
            continueWatching = emptyList(),
            favourites = com.tmplayer.data.HomeRows.favouriteChats(chats, setOf(2L)),
            loaded = mapOf(2L to items),
            recent = emptyList(),
        )
        shell.go(Destination.Home)
        shell.openDetail(DetailRequest(items[5], "Weekend series", outsideChat = true))
        try {
            for (dark in listOf(true, false)) {
                save(if (dark) "desktop-detail-home-dark.png" else "desktop-detail-home-light.png", render(dark = dark) {
                    Box(Modifier.fillMaxSize()) {
                        HomeRowsView(shell, rows, chats, emptyMap(), onRowShown = {}, onArtWanted = {}, onRefresh = {})
                        DetailPaneHost(shell)
                    }
                })
            }
        } finally {
            shell.closeDetail()
            shell.go(Destination.Chats)
        }
    }

    /** "Videos in all chats": matching chats, then the videos a fake searchMessages found. */
    @Test
    fun allChatsSearch() = kotlinx.coroutines.runBlocking {
        val chats = listOf(
            chat(2, "Harbour Lights fans", ChatKind.Group, 0, 0xFFF5A524.toInt()),
            chat(3, "Film Club", ChatKind.Channel, 0, 0xFFE5484D.toInt()),
            chat(7, "Harbour photos", ChatKind.Channel, 0, 0xFF0090FF.toInt()),
        )
        shell.noteChatTitles(chats)
        val found = releaseItems(chatId = 3).filter { it.fileName.startsWith("Harbour") } +
            releaseItems(chatId = 2).take(1).map {
                val name = it.fileName.replace("S02E05", "S02E04")
                it.copy(messageId = 900, fileName = name, title = name, date = it.date - 7 * 86_400)
            }
        val source = com.tmplayer.ui.browse.VideoSearchSource { query, _ ->
            com.tmplayer.data.AllChatsPage(
                found.filter { com.tmplayer.data.Fuzzy.score(it.fileName, query) > 0 },
                com.tmplayer.data.AllChatsCursor(videoDone = true, documentDone = true),
            )
        }
        val page = source.page("harbour", com.tmplayer.data.AllChatsCursor())
        val results = com.tmplayer.ui.browse.VideoSearchState(
            query = "harbour",
            videos = com.tmplayer.data.AllChatsSearch.merge(emptyList(), page.items),
            endReached = page.cursor.done,
        )
        fun shot(videos: com.tmplayer.ui.browse.VideoSearchState, dark: Boolean = true) = render(dark = dark) {
            Column(Modifier.fillMaxSize()) {
                PageHeader("All chats", null)
                com.tmplayer.ui.browse.SearchScopeToggle(
                    com.tmplayer.data.SearchScope.AllVideos,
                    {},
                    Modifier.padding(start = 24.dp, bottom = 8.dp),
                )
                AllChatsResults(shell, "harbour", chats, setOf(2L), videos, onStar = {}, onLoadMore = {}, onRetry = {})
            }
        }
        save("desktop-search-all-dark.png", shot(results))
        save("desktop-search-all-light.png", shot(results, dark = false))
        save("desktop-search-all-searching.png", shot(com.tmplayer.ui.browse.VideoSearchState(query = "harbour", loading = true)))
        save("desktop-search-all-none.png", shot(com.tmplayer.ui.browse.VideoSearchState(query = "harbour", endReached = true)))
    }

    @Test
    fun watchedTicksAndSizeNote() = kotlinx.coroutines.runBlocking {
        val items = (1..6).map { at ->
            MediaItem(
                chatId = 2, messageId = at.toLong(), fileId = 0, title = "Harbour Lights S02E0$at",
                sizeBytes = 400L * 1024 * 1024, durationSec = 2400, mimeType = "video/mp4", thumbnailFileId = 0,
                miniThumbnail = jpeg(0xFF12A594.toInt()), date = 0, fileName = "Harbour Lights S02E0$at.mkv",
            )
        }
        val now = System.currentTimeMillis()
        shell.watched.markWatched(com.tmplayer.data.WatchedRecord.of(items[0], "Weekend series", now - 3 * 60_000, manual = false))
        shell.watched.markWatched(com.tmplayer.data.WatchedRecord.of(items[1], "Weekend series", now - 26 * 3_600_000L, manual = true))
        // A second viewing part way through: the tick stays, the bar shows where it is.
        settings.saveResumePosition(2, 2, 600_000, 2_400_000, "")
        val grid = render { VideoGrid(shell, items, "Weekend series", hiddenBySize = 3, hiddenSelfDestructing = 1) }
        save("watched-grid.png", grid)
        shell.showHistory(com.tmplayer.ui.browse.HistoryTab.Watched)
        val page = render { HistoryPage(shell) }
        save("watched-page.png", page)
        shell.showHistory(com.tmplayer.ui.browse.HistoryTab.Continue)
        save("history-continue.png", render { HistoryPage(shell) })
    }

    @Test
    fun emptyErrorAndRecentStates() {
        fun page(state: com.tmplayer.ui.components.UiState<Unit>, recent: List<String> = emptyList()) = render {
            Column(Modifier.fillMaxSize()) {
                PageHeader("Film Club", null)
                RecentSearchRow(recent, onPick = {}, onClear = {})
                StateBox(state, onRetry = {}, onAction = {}, slowAfterMs = 100) {}
            }
        }
        val hidden = com.tmplayer.data.SizeFilter.hiddenLabel(12)
        save(
            "state-empty-hidden.png",
            page(
                com.tmplayer.ui.components.UiState.Empty(
                    "${com.tmplayer.ui.browse.noVideosWithin(com.tmplayer.data.SizeFilter.DEFAULT_MIN, com.tmplayer.data.SizeFilter.DEFAULT_MAX)}\n\n$hidden.",
                    com.tmplayer.ui.components.StateAction.ShowHidden,
                ),
            ),
        )
        save(
            "state-empty-more.png",
            page(
                com.tmplayer.ui.components.UiState.Empty(
                    com.tmplayer.ui.browse.STILL_MORE_TO_SEARCH,
                    com.tmplayer.ui.components.StateAction.KeepLooking,
                ),
            ),
        )
        save(
            "state-first-load-slow.png",
            page(com.tmplayer.ui.components.UiState.Loading("Loading your chats…", tip = com.tmplayer.ui.browse.FIRST_LOAD_TIP)),
        )
        save(
            "state-recent-searches.png",
            page(com.tmplayer.ui.components.UiState.Loading("Finding videos…"), listOf("coast walk", "shelf part 2", "birthday", "recipe")),
        )
        val items = (1..4).map { at ->
            MediaItem(
                chatId = 2, messageId = at.toLong(), fileId = 0, title = "Harbour Lights S02E0$at",
                sizeBytes = 400L * 1024 * 1024, durationSec = 2400, mimeType = "video/mp4", thumbnailFileId = 0,
                miniThumbnail = jpeg(0xFF12A594.toInt()), date = 0, fileName = "Harbour Lights S02E0$at.mkv",
            )
        }
        save("state-hidden-note.png", render { VideoGrid(shell, items, "Weekend series", hiddenBySize = 12) })
    }

    @Test
    fun downloadsPage() = kotlinx.coroutines.runBlocking {
        val folder = dir.resolve("Downloads/TMPlayer").apply { mkdirs() }
        fun item(id: Long, title: String, size: Long) = MediaItem(
            chatId = 2, messageId = id, fileId = id.toInt(), title = title, sizeBytes = size, durationSec = 0,
            mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null, date = 0,
        )
        val kept = folder.resolve("Night Train (2019) 1080p.mkv").apply { writeBytes(ByteArray(2048)) }
        settings.noteDownload(item(11, "Night Train (2019) 1080p", 2048), "Film Club", kept.absolutePath)
        settings.noteDownload(item(12, "The Long Road S01E01", 4096), "Film Club", folder.resolve("gone.mkv").absolutePath)
        settings.rememberCachedVideo(item(13, "Harbour Lights S02E05", 900L * 1024 * 1024), "Weekend series")
        // Drawn on, so the shot shows the "Remove after watching" switch in its live state.
        settings.setRemoveAfterWatching(true)
        com.tmplayer.data.OfflineDownloads.note(
            com.tmplayer.data.OfflineDownloads.Progress(
                request = DownloadRequest.from(item(14, "Mountains, a film", 1_500L * 1024 * 1024), "Documentaries"),
                downloadedBytes = 600L * 1024 * 1024,
                totalBytes = 1_500L * 1024 * 1024,
                stage = com.tmplayer.data.OfflineDownloads.Stage.Running,
                bytesPerSecond = 3_000_000,
            ),
        )
        try {
            val png = render { DownloadsPage(shell, folder) }
            save("downloads.png", png)
        } finally {
            com.tmplayer.data.OfflineDownloads.forget(14)
        }
    }

    @Test
    fun settingsAndUpdatePopup() {
        val release = Release(
            version = "2.0.1",
            pageUrl = "https://github.com/dracu-lah/TMPlayer/releases/tag/v2.0.1",
            notes = "Downloads leave the cache and live in a folder you choose.",
            assets = mapOf("windows-x64-msi" to ReleaseAsset("https://x/TMPlayer-2.0.1-windows-x64.msi", size = 155_000_000)),
        )
        val png = render(update = NavUpdate("Update", "2.0.1")) {
            Box(Modifier.fillMaxSize()) {
                SettingsPage(shell, "2.0.0")
                UpdatePopup(
                    release = release,
                    skipped = false,
                    installed = "2.0.0",
                    kind = com.tmplayer.desktop.InstallKind.WindowsMsi,
                    canUpdate = true,
                    progress = com.tmplayer.desktop.UpdateProgress.Downloading(0.42f),
                    onUpdate = {},
                    onOpenPage = {},
                    onLater = {},
                    onSkip = {},
                    onRestart = {},
                    onClose = {},
                )
            }
        }
        save("settings.png", png)
    }

    @Test
    fun aboutPage() {
        save("about.png", render { AboutPage("2.0.0", onBack = {}) })
    }

    @Test
    fun updatePopupOffers() {
        val release = Release("2.0.1", "page", notes = "Downloads leave the cache.")
        val png = render(update = NavUpdate("Update", "2.0.1")) {
            UpdatePopup(
                release = release,
                skipped = false,
                installed = "2.0.0",
                kind = com.tmplayer.desktop.InstallKind.Flatpak,
                canUpdate = false,
                progress = com.tmplayer.desktop.UpdateProgress.Idle,
                onUpdate = {},
                onOpenPage = {},
                onLater = {},
                onSkip = {},
                onRestart = {},
                onClose = {},
            )
        }
        save("update-popup.png", png)
    }

    /**
     * Posters and overviews (CP32) over [MetaFixture]'s canned TMDB: the detail pane with a film's
     * poster and overview, a show's page with its poster, Home with posters, the Settings group in
     * each key state, About's attribution, and the line that offers lookups while they are off.
     */
    @Test
    fun onlineMetadata() = kotlinx.coroutines.runBlocking {
        val online = MetaFixture.install("on")
        try {
            fun item(id: Long, chatId: Long, name: String, colour: Long, date: Int) = MediaItem(
                chatId = chatId, messageId = id, fileId = 0, title = name, sizeBytes = 885L * 1024 * 1024,
                durationSec = 596 + (id % 10).toInt() * 300, mimeType = "video/x-matroska", thumbnailFileId = 0,
                miniThumbnail = jpeg(colour.toInt()), date = date, fileName = name, width = 1920, height = 1080,
            )
            val bunny = item(500, 2, "Big.Buck.Bunny.2008.1080p.BluRay.x264.mkv", 0xFF46A758, 1_790_000_000)
            val episodes = (1..6).map { n -> item(600L + n, 4, "Harbour.Lights.S02E%02d.1080p.WEB-DL.mkv".format(n), 0xFF2AABEE, 1_790_000_000 + n) }
            val clips = listOf("Lecture 7 Compilers.mp4", "coast-walk-day-2-1080p.mp4").mapIndexed { at, n -> item(700L + at, 2, n, 0xFFF5A524, 1_780_000_000) }
            // Warm the answers and the pictures, so the frames below show them rather than the wait.
            for (it in listOf(bunny, episodes[4], clips[0], clips[1])) {
                val q = com.tmplayer.online.MetaQuery.of(it.fileName)!!
                online.lookup(q)
                online.lookup(q.showOnly())
            }
            for ((url, width) in listOf(
                "https://image.tmdb.org/t/p/w780/render-bbb-wide.webp" to 800,
                "https://image.tmdb.org/t/p/w780/render-bbb-wide.webp" to 480,
                "https://image.tmdb.org/t/p/w342/render-bbb.webp" to 240,
                "https://image.tmdb.org/t/p/w342/render-harbour.webp" to 240,
                "https://image.tmdb.org/t/p/w342/render-harbour.webp" to 480,
            )) com.tmplayer.data.Thumbnails.online(url, width) { online.image(url) }
            // The detail page's extras (facts, cast, trailer), warmed the same way.
            for (it in listOf(bunny, episodes[4])) {
                val info = (online.lookup(com.tmplayer.online.MetaQuery.of(it.fileName)!!.showOnly()) as? com.tmplayer.online.MetaResult.Found)?.info
                assertTrue("no match for ${it.fileName}", info != null && online.extras(info) != null)
            }

            val chats = listOf(
                chat(2, "Film Club", ChatKind.Channel, 0, 0xFFE5484D.toInt()),
                chat(4, "Weekend series", ChatKind.Group, 0, 0xFFF5A524.toInt()),
            )
            shell.noteChatTitles(chats)
            for (dark in listOf(true, false)) {
                shell.openDetail(DetailRequest(bunny, "Film Club"))
                save(if (dark) "desktop-meta-detail-dark.png" else "desktop-meta-detail-light.png", render(dark = dark) {
                    Box(Modifier.fillMaxSize()) {
                        VideoGrid(shell, listOf(bunny) + clips, "Film Club")
                        DetailPaneHost(shell)
                    }
                })
                shell.closeDetail()
            }
            // An episode: the show's poster, the episode's own name and words.
            shell.openDetail(DetailRequest(episodes[4], "Weekend series"))
            save("desktop-meta-detail-episode.png", render { Box(Modifier.fillMaxSize()) { VideoGrid(shell, episodes, "Weekend series"); DetailPaneHost(shell) } })
            shell.closeDetail()

            // Tall enough for the facts line, the trailer and the cast under the synopsis.
            shell.openDetail(DetailRequest(bunny, "Film Club"))
            save("desktop-meta-detail-extras.png", render(height = 1500) { Box(Modifier.fillMaxSize()) { VideoGrid(shell, listOf(bunny) + clips, "Film Club"); DetailPaneHost(shell) } })
            shell.closeDetail()

            val show = (com.tmplayer.data.SeriesShelf.arrange(episodes).first() as com.tmplayer.data.ShelfEntry.Show).series
            save("desktop-meta-series.png", render { SeriesPage(shell, show, rememberSeriesWatch(shell), onClose = {}) })
            save("desktop-meta-series-cast.png", render(height = 1500) { SeriesPage(shell, show, rememberSeriesWatch(shell), onClose = {}) })

            val rows = com.tmplayer.data.HomeRows.build(
                continueWatching = listOf(bunny).map {
                    com.tmplayer.data.ResumeRecord(
                        chatId = it.chatId, messageId = it.messageId, fileId = 1, title = it.title, chatTitle = "Film Club",
                        sizeBytes = it.sizeBytes, durationSec = it.durationSec, positionMs = 200_000L,
                        durationMs = it.durationSec * 1000L, updatedAt = 10L,
                    )
                },
                favourites = com.tmplayer.data.HomeRows.favouriteChats(chats, setOf(2L, 4L)),
                loaded = mapOf(2L to listOf(bunny) + clips, 4L to episodes),
                recent = clips,
            )
            shell.go(Destination.Home)
            val art = mapOf(SettingsStore.progressKey(bunny.chatId, bunny.messageId) to bunny)
            for (dark in listOf(true, false)) {
                save(if (dark) "desktop-meta-home-dark.png" else "desktop-meta-home-light.png", render(dark = dark) {
                    HomeRowsView(shell, rows, chats, art, onRowShown = {}, onArtWanted = {}, onRefresh = {})
                })
            }
            shell.go(Destination.Chats)

            for (state in listOf("on", "off", "none", "refused")) {
                MetaFixture.install(state)
                save("desktop-meta-settings-$state.png", render {
                    androidx.compose.foundation.layout.Column(Modifier.padding(24.dp)) { OnlineMetadataGroup() }
                })
            }
            MetaFixture.install("none")
            save("desktop-meta-key-dialog.png", render { MetadataKeyDialog(onClose = {}) })

            MetaFixture.install("on")
            assertTrue(com.tmplayer.ui.about.About.groups("2.0.0").any { it.tmdbLogo && it.links.any { l -> l.url == com.tmplayer.ui.about.About.TVMAZE } })
            save("desktop-meta-about.png", render(height = 1900) { AboutPage("2.0.0", onBack = {}) })

            // Off: the pane offers to turn lookups on, and shows the video's own picture.
            MetaFixture.install("off")
            shell.openDetail(DetailRequest(bunny, "Film Club"))
            save("desktop-meta-detail-off.png", render { Box(Modifier.fillMaxSize()) { VideoGrid(shell, listOf(bunny), "Film Club"); DetailPaneHost(shell) } })
            shell.closeDetail()
        } finally {
            com.tmplayer.online.OnlineMetadata.current = null
            shell.go(Destination.Chats)
        }
    }

    private fun render(
        update: NavUpdate? = null,
        dark: Boolean = true,
        folders: List<com.tmplayer.data.ChatFolderSummary> = emptyList(),
        height: Int = HEIGHT,
        page: @androidx.compose.runtime.Composable () -> Unit,
    ): ByteArray =
        ImageComposeScene(WIDTH, height, Density(1f)) {
            TmMaterialTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = Tone.background) {
                    Row(Modifier.fillMaxSize()) {
                        Sidebar(shell, update, folders)
                        VerticalDivider(color = Tone.outline)
                        Box(Modifier.weight(1f).fillMaxHeight()) { page() }
                    }
                }
            }
        }.use { scene ->
            scene.render(0)
            // A second frame after the thumbnails have decoded off the composition thread.
            Thread.sleep(400)
            scene.render(500_000_000)
            scene.render(1_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }

    @Test
    fun downloadLineCarriesFreeSpace() {
        org.junit.Assert.assertEquals("Download (12.0 GB free)", withFree("Download", 12L * 1024 * 1024 * 1024))
        org.junit.Assert.assertEquals("Download", withFree("Download", 0))
    }

    private fun save(name: String, png: ByteArray) {
        assertTrue(png.size > 1000)
        val dir = System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        File(dir).apply { mkdirs() }.resolve(name).writeBytes(png)
    }

    private fun chat(id: Long, title: String, kind: ChatKind, unread: Int, argb: Int) = ChatSummary(
        id = id, title = title, miniThumbnail = jpeg(argb), photoFileId = 0, kind = kind,
        unreadCount = unread, isPinned = id == 1L,
    )

    /** A tiny JPEG, a gradient in [argb], standing in for Telegram's minithumbnail. */
    private fun jpeg(argb: Int): ByteArray {
        val surface = SkSurface.makeRasterN32Premul(40, 24)
        surface.canvas.drawRect(Rect.makeWH(40f, 24f), Paint().apply { color = argb })
        surface.canvas.drawRect(Rect.makeXYWH(0f, 14f, 40f, 10f), Paint().apply { color = Color.makeRGB(16, 20, 24) })
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 80)!!.bytes
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 800
    }
}
