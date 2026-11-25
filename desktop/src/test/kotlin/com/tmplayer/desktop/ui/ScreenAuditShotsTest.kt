package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.use
import com.tmplayer.data.Account
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.UpdateScheduler
import com.tmplayer.data.WatchedStore
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.DesktopWatchCache
import com.tmplayer.desktop.os.WindowChromeMode
import com.tmplayer.ui.browse.chipLabel
import com.tmplayer.ui.components.ToastHost
import com.tmplayer.ui.lock.PinPad
import com.tmplayer.ui.lock.PinPurpose
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface as SkSurface
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The desktop screen sweep (the 2026-11-25 audit): every page, state and dialog drawn with made-up
 * data at the window sizes a desktop has (800x600 up to 2560x1440), at a larger font scale with a
 * long account name, in the dark and the light theme, with the window's own buttons on. With
 * TMPLAYER_SHOTS=<dir> the frames are written there as `<screen>-<w>x<h>[-fs][-light].png`, for a
 * person (or an agent) to look at. TMPLAYER_ONLY=<substring> draws only the screens whose name has
 * it, TMPLAYER_SIZES=1280x800,800x600 only those sizes.
 */
class ScreenAuditShotsTest {

    private val settings = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-audit").resolve(SettingsStore.FILE_NAME).toFile()),
    )
    private val dir = Files.createTempDirectory("tm-audit-extras").toFile()
    internal val shell = ShellState(
        settings,
        object : DownloadRunner {
            override fun download(request: DownloadRequest) = Unit
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        },
        WatchedStore(WatchedStore.openDataStore(Files.createTempDirectory("tm-audit-watched").resolve(WatchedStore.FILE_NAME).toFile())),
        services = {
            DesktopExtras(
                prefs = DesktopPrefs(dir.resolve("desktop.properties")),
                updates = UpdateScheduler(settings.updatePrefs, check = { _, _ -> false }, onSkip = {}),
                watchCache = DesktopWatchCache(settings, { dir.resolve("cache") }),
            )
        },
    )

    @After
    fun reset() {
        com.tmplayer.data.OfflineDownloads.forget(14)
        shell.closeDetail()
        shell.closeChat()
        shell.go(Destination.Chats)
        com.tmplayer.online.OnlineMetadata.current = null
    }

    private val sizes = listOf(800 to 600, 1100 to 700, 1280 to 800, 1920 to 1080, 2560 to 1440)
    private val only = System.getenv("TMPLAYER_ONLY")?.takeIf { it.isNotBlank() }
    private val sizeOnly = System.getenv("TMPLAYER_SIZES")?.takeIf { it.isNotBlank() }?.split(',')

    internal val shortAccount = Account("Mara Lindqvist", "maralind", jpeg(0xFF8E4EC6.toInt()), 0)
    internal val longAccount = Account("Alexandra Montgomery-Featherstonehaugh the Third", "alexandra_montgomery_featherstonehaugh", jpeg(0xFF8E4EC6.toInt()), 0)

    internal fun chrome() = WindowChrome(
        mode = WindowChromeMode.Custom, window = null, onMinimize = {}, onToggleMaximize = {}, onClose = {}, maximized = { false },
    )

    private val colours = listOf(0xFF2AABEE, 0xFFE5484D, 0xFF46A758, 0xFFF5A524, 0xFF8E4EC6, 0xFF0090FF, 0xFF12A594, 0xFFD6409F)

    private fun chat(id: Long, title: String, kind: ChatKind, unread: Int = 0) = ChatSummary(
        id = id, title = title, miniThumbnail = jpeg(colours[(id % colours.size).toInt()].toInt()), photoFileId = 0, kind = kind,
        unreadCount = unread, isPinned = id == 1L,
    )

    internal val chats = listOf(
        chat(1, "Saved Messages", ChatKind.Saved),
        chat(2, "Film Club", ChatKind.Channel, 12),
        chat(3, "Documentaries in 4K with a very long name that keeps going on and on", ChatKind.Channel),
        chat(4, "Weekend series", ChatKind.Group, 3),
        chat(5, "Anna", ChatKind.Direct),
        chat(6, "Old lectures", ChatKind.Channel),
    )

    private fun releaseItems(chatId: Long = 2, canBeSaved: Boolean = true): List<MediaItem> {
        val names = listOf(
            "Harbour.Lights.S02E05.1080p.WEB-DL.DDP5.1.x265-DEMO.mkv",
            "Night.Train.2019.2160p.BluRay.DV.HDR10.TrueHD.Atmos.7.1.HEVC.mkv",
            "Mountains (2024) Dual Audio 720p WEBRip AAC2.0 x264 ESub.mp4",
            "Lecture 7 Compilers.mp4",
            "Harbour.Lights.S02E06.1080p.WEB-DL.DDP5.1.x265-DEMO.mkv",
            "The.Quiet.Year.2023.1080p.WEB-DL.AAC2.0.H.264.mkv",
            "A very long file name that never ends and keeps going past any tile width you could draw it in.mkv",
            "Garden party.mp4",
        )
        return names.mapIndexed { at, name ->
            MediaItem(
                chatId = chatId, messageId = 100L + at, fileId = 0, title = name,
                sizeBytes = (900L + at * 310L) * 1024 * 1024, durationSec = 2600 + at * 300,
                mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = jpeg(colours[at % colours.size].toInt()),
                date = 1_790_000_000 - at * 86_400, fileName = name,
                caption = if (at == 0) "Season two, episode five. The storm reaches the harbour and Mara has to choose." else "",
                width = if (at == 0) 1920 else 0, height = if (at == 0) 1080 else 0,
                canBeSaved = canBeSaved,
            )
        }
    }

    private fun frame(
        screen: AuditScreen,
        w: Int,
        h: Int,
        dark: Boolean,
        fontScale: Float,
        account: Account?,
        update: NavUpdate?,
    ): ByteArray =
        run {
            shell.go(screen.dest)
            screen.before()
            ImageComposeScene(w, h, Density(1f, fontScale)) {
                TmMaterialTheme(dark = dark) {
                    CompositionLocalProvider(LocalWindowChrome provides chrome(), com.tmplayer.ui.components.LocalWindowControlsInset provides com.tmplayer.ui.components.windowControlsInset(3)) {
                        Surface(Modifier.fillMaxSize(), color = Tone.background) {
                            Box(Modifier.fillMaxSize()) {
                                NavFrame(shell, update, account, barShown = screen.bar) {
                                    if (screen.modal) {
                                        HomeRowsView(shell, emptyList(), chats, emptyMap(), onRowShown = {}, onArtWanted = {}, onRefresh = {})
                                    } else {
                                        screen.content()
                                    }
                                }
                                if (screen.modal) ModalPageHost(shell) { screen.content() }
                                WindowControlsFallback(Modifier.align(Alignment.TopEnd))
                            }
                        }
                    }
                }
            }
        }.use { scene ->
            var t = 0L
            repeat(6) {
                scene.render(t)
                Thread.sleep(120)
                t += 120_000_000
            }
            val png = scene.render(t + 1_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
            screen.after()
            shell.go(Destination.Home)
            png
        }

    private fun sweep(screens: List<AuditScreen>) {
        val out = System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        File(out).mkdirs()
        for (screen in screens) {
            if (only != null && only !in screen.name) continue
            if (sizeOnly != null) {
                // Named sizes, e.g. 800x2000fs for a tall frame at the larger font with the long name.
                for (spec in sizeOnly) {
                    val m = Regex("(\\d+)x(\\d+)(fs)?").matchEntire(spec) ?: continue
                    val w = m.groupValues[1].toInt()
                    val h = m.groupValues[2].toInt()
                    val fs = m.groupValues[3].isNotEmpty()
                    val png = frame(screen, w, h, true, if (fs) 1.3f else 1f, if (fs) longAccount else shortAccount, NavUpdate("Update", "2.0.1"))
                    save(out, "${screen.name}-${w}x$h${if (fs) "-fs" else ""}.png", png)
                }
                continue
            }
            for ((w, h) in sizes) {
                if (!screen.all && w != 1280 && w != 800) continue
                save(out, "${screen.name}-${w}x$h.png", frame(screen, w, h, true, 1f, shortAccount, NavUpdate("Update", "2.0.1")))
            }
            // The worst case: a larger font, a long name, a smaller window.
            for ((w, h) in listOf(800 to 600, 1100 to 700)) {
                save(out, "${screen.name}-${w}x$h-fs.png", frame(screen, w, h, true, 1.3f, longAccount, NavUpdate("Update", "2.0.1")))
            }
            if (screen.all) save(out, "${screen.name}-1280x800-light.png", frame(screen, 1280, 800, false, 1f, shortAccount, null))
        }
    }

    private fun save(out: String, name: String, png: ByteArray) {
        assertTrue(png.size > 1000)
        File(out).resolve(name).writeBytes(png)
    }

    private fun jpeg(argb: Int): ByteArray {
        val surface = SkSurface.makeRasterN32Premul(40, 24)
        surface.canvas.drawRect(Rect.makeWH(40f, 24f), Paint().apply { color = argb })
        surface.canvas.drawRect(Rect.makeXYWH(0f, 14f, 40f, 10f), Paint().apply { color = Color.makeRGB(16, 20, 24) })
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 80)!!.bytes
    }

    private val episodes = (1..6).map { n ->
        MediaItem(
            chatId = 4, messageId = 600L + n, fileId = 0, title = "Harbour.Lights.S02E%02d.1080p.WEB-DL.mkv".format(n),
            sizeBytes = 885L * 1024 * 1024, durationSec = 2600, mimeType = "video/x-matroska", thumbnailFileId = 0,
            miniThumbnail = jpeg(0xFF2AABEE.toInt()), date = 1_790_000_000 + n, fileName = "Harbour.Lights.S02E%02d.1080p.WEB-DL.mkv".format(n),
            width = 1920, height = 1080,
        )
    }

    private fun library(): List<com.tmplayer.data.HomeRow> {
        val items = releaseItems()
        val resume = listOf(items[1], episodes[2]).mapIndexed { at, item ->
            com.tmplayer.data.ResumeRecord(
                chatId = item.chatId, messageId = item.messageId, fileId = 1, title = item.title, chatTitle = "Film Club",
                sizeBytes = item.sizeBytes, durationSec = item.durationSec, positionMs = (at + 1) * 400_000L,
                durationMs = item.durationSec * 1000L, updatedAt = 10L - at,
            )
        }
        return com.tmplayer.data.HomeRows.build(
            continueWatching = resume,
            favourites = com.tmplayer.data.HomeRows.favouriteChats(chats, setOf(2L, 4L, 6L)),
            loaded = mapOf(2L to items, 4L to episodes, 6L to items.reversed().map { it.copy(chatId = 6) }),
            recent = items + episodes,
        )
    }

    @Test
    fun pages() = sweep(pageScreens())

    /** Pages: Home, Chats, a chat, a show, History, Downloads, a video's pane, the account's popover. */
    internal fun pageScreens(): List<AuditScreen> = kotlinx.coroutines.runBlocking {
        val items = releaseItems()
        shell.noteChatTitles(chats)
        settings.saveResumePosition(2, 100, 2_050_000, 2_600_000, "")
        val rows = library()
        val art = listOf(items[1], episodes[2]).associateBy { SettingsStore.progressKey(it.chatId, it.messageId) }
        val show = (com.tmplayer.data.SeriesShelf.arrange(episodes).first() as com.tmplayer.data.ShelfEntry.Show).series
        val chips = com.tmplayer.ui.browse.ChatFilter.entries
        val now = System.currentTimeMillis()
        shell.watched.markWatched(com.tmplayer.data.WatchedRecord.of(items[0], "Film Club", now - 3 * 60_000, manual = false))
        shell.watched.markWatched(com.tmplayer.data.WatchedRecord.of(items[2], "Film Club", now - 26 * 3_600_000L, manual = true))
        items.take(3).forEach(com.tmplayer.ui.browse.RecordItems::keep)
        val online = MetaFixture.install("on")
        val folder = dir.resolve("Downloads/TMPlayer").apply { mkdirs() }
        fun dl(id: Long, title: String, size: Long) = MediaItem(
            chatId = 2, messageId = id, fileId = id.toInt(), title = title, sizeBytes = size, durationSec = 0,
            mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null, date = 0,
        )
        val kept = folder.resolve("Night Train (2019) 1080p.mkv").apply { writeBytes(ByteArray(2048)) }
        settings.noteDownload(dl(11, "Night Train (2019) 1080p", 2048), "Film Club", kept.absolutePath)
        settings.noteDownload(dl(12, "The Long Road S01E01 with a name long enough to need more than one line", 4096), "Film Club", folder.resolve("gone.mkv").absolutePath)
        settings.rememberCachedVideo(dl(13, "Harbour Lights S02E05", 900L * 1024 * 1024), "Weekend series")
        com.tmplayer.data.OfflineDownloads.note(
            com.tmplayer.data.OfflineDownloads.Progress(
                request = DownloadRequest.from(dl(14, "Mountains, a film", 1_500L * 1024 * 1024), "Documentaries"),
                downloadedBytes = 600L * 1024 * 1024, totalBytes = 1_500L * 1024 * 1024,
                stage = com.tmplayer.data.OfflineDownloads.Stage.Running, bytesPerSecond = 3_000_000,
            ),
        )
        val chatsPage: @Composable () -> Unit = {
            Column(Modifier.fillMaxSize()) {
                PageHeader(com.tmplayer.ui.browse.BrowseTab.Chats.heading, com.tmplayer.ui.browse.BrowseTab.Chats.blurb)
                ChoiceChips(chips.map { chip -> Choice(chip.name, chip.chipLabel, chip.icon, selected = chip == chips.first()) {} })
                Column(Modifier.fillMaxSize()) { chats.forEach { ChatRow(it, favourite = it.id == 2L, onOpen = {}, onStar = {}) } }
            }
        }
        val grid: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize()) {
                androidx.compose.runtime.CompositionLocalProvider(com.tmplayer.ui.components.LocalBottomChrome provides ChatBarClearance) {
                    val anchor = com.tmplayer.ui.components.rememberPopoverAnchor()
                    VideoGrid(
                        shell, items, "Film Club", topPadding = ChatTopClearance,
                        bottomBar = {
                            ChatBar(
                                state = shell, searching = false, query = "", onQuery = {}, onOpenSearch = {}, onCloseSearch = {},
                                onSearchDown = {}, recent = emptyList(), onClearRecent = {}, favourite = true, onToggleFavourite = {},
                                onSort = {}, sortAnchor = anchor,
                            )
                        },
                    )
                    ChatTopChrome(shell, chats[1])
                }
            }
        }
        shell.go(Destination.Home)
        run {
            listOf(
                AuditScreen("home") { HomeRowsView(shell, rows, chats, art, onRowShown = {}, onArtWanted = {}, onRefresh = {}) },
                AuditScreen("home-allrows-off") {
                    HomeRowsView(
                        shell, emptyList(), chats, emptyMap(), onRowShown = {}, onArtWanted = {}, onRefresh = {},
                        layout = com.tmplayer.data.HomeLayout(carousel = false),
                    )
                },
                AuditScreen("home-stalled") {
                    HomeRowsView(shell, emptyList(), chats, emptyMap(), onRowShown = {}, onArtWanted = {}, onRefresh = {}, stalled = true, rowsReady = false)
                },
                AuditScreen("chats", dest = Destination.Chats, content = chatsPage),
                AuditScreen("favourites-chats", dest = Destination.Favourites, content = chatsPage),
                AuditScreen("chat-grid", dest = Destination.Chats, bar = false, before = { shell.openChat(chats[1]) }, after = { shell.closeChat() }, content = grid),
                AuditScreen("series") { SeriesPage(shell, show, rememberSeriesWatch(shell), onClose = {}) },
                AuditScreen("history-continue", dest = Destination.History, before = { shell.showHistory(com.tmplayer.ui.browse.HistoryTab.Continue) }) { HistoryPage(shell) },
                AuditScreen("history-watched", dest = Destination.History, before = { shell.showHistory(com.tmplayer.ui.browse.HistoryTab.Watched) }) { HistoryPage(shell) },
                AuditScreen("history-later", dest = Destination.History, before = { shell.showHistory(com.tmplayer.ui.browse.HistoryTab.WatchLater) }) { HistoryPage(shell) },
                AuditScreen("downloads-filled", dest = Destination.Downloads, modal = true) { DownloadsPage(shell, folder) },
                AuditScreen("downloads-empty", dest = Destination.Downloads, modal = true) { DownloadsPage(shell, dir.resolve("DownloadsEmpty").apply { mkdirs() }) },
                AuditScreen(
                    "detail-video", dest = Destination.Chats, bar = false,
                    before = { shell.openChat(chats[1]); shell.openDetail(DetailRequest(items[0], "Film Club")) },
                    after = { shell.closeDetail(); shell.closeChat() },
                ) { Box(Modifier.fillMaxSize()) { grid(); DetailPaneHost(shell) } },
                AuditScreen(
                    "detail-protected", dest = Destination.Chats, bar = false,
                    before = { shell.openChat(chats[1]); shell.openDetail(DetailRequest(items[2].copy(canBeSaved = false), "Film Club", selectThis = null)) },
                    after = { shell.closeDetail(); shell.closeChat() },
                ) { Box(Modifier.fillMaxSize()) { grid(); DetailPaneHost(shell) } },
                AuditScreen("account-popover", before = { shell.profileOpen = true }, after = { shell.profileOpen = false }) {
                    HomeRowsView(shell, rows, chats, art, onRowShown = {}, onArtWanted = {}, onRefresh = {})
                },
            )
        }
    }

    @Test
    fun settingsPages() = sweep(settingScreens())

    /** Settings, its list and every page under it, drawn as the panel over Home. */
    internal fun settingScreens(): List<AuditScreen> {
        shell.noteChatTitles(chats)
        val screens = mutableListOf(
            AuditScreen("settings-list", dest = Destination.Settings, modal = true) { SettingsPage(shell, "2.0.0", chatList = chats, account = shortAccount) },
        )
        for (category in SettingsCategory.entries) {
            screens += AuditScreen("settings-${category.name.lowercase()}", dest = Destination.Settings, modal = true) {
                SettingsPage(shell, "2.0.0", chatList = chats, account = shortAccount, initialCategory = category)
            }
        }
        screens += AuditScreen("settings-notices", dest = Destination.Settings, modal = true) {
            SettingsPage(shell, "2.0.0", chatList = chats, account = shortAccount, initialCategory = SettingsCategory.About, initialAbout = AboutView.Notices)
        }
        return screens
    }

    @Test
    fun overlaysAndStates() = sweep(overlayScreens())

    /** Dialogs, popups, cards, empty, error and loading states, sign in and the lock pad. */
    internal fun overlayScreens(): List<AuditScreen> {
        shell.noteChatTitles(chats)
        val release = com.tmplayer.data.Release(
            version = "2.0.1",
            pageUrl = "https://github.com/dracu-lah/TMPlayer/releases/tag/v2.0.1",
            notes = "Downloads leave the cache and live in a folder you choose.",
            assets = mapOf("windows-x64-msi-v2" to com.tmplayer.data.ReleaseAsset("https://x/TMPlayer-2.0.1-windows-x64.msi", size = 155_000_000)),
        )
        val items = releaseItems()
        fun over(top: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit): @Composable () -> Unit = {
            Box(Modifier.fillMaxSize()) {
                HomeRowsView(shell, emptyList(), chats, emptyMap(), onRowShown = {}, onArtWanted = {}, onRefresh = {})
                top()
            }
        }
        fun stateScreen(name: String, state: com.tmplayer.ui.components.UiState<Unit>) = AuditScreen(name) {
            Column(Modifier.fillMaxSize()) {
                PageHeader("Film Club", "8 videos")
                StateBox(state, onRetry = {}, onAction = {}, slowAfterMs = 100) {}
            }
        }
        return run {
            listOf(
                AuditScreen("dlg-confirm") {
                    over {
                        ConfirmDialog(
                            title = "Sign out of Telegram?",
                            message = "You'll be signed out and taken back to the sign-in screen. The cache, your favourites, your watched list and everything you were part-way through go with it.",
                            confirmLabel = "Sign out", onConfirm = {}, onDismiss = {},
                        )
                    }()
                },
                AuditScreen("dlg-signout") { over { SignOutDialog(shell, onDismiss = {}) }() },
                AuditScreen("dlg-crash") { over { CrashQuestion(onSend = {}, onDismiss = {}) }() },
                AuditScreen("dlg-quit") { over { QuitQuestion(count = 3, onKeep = {}, onQuit = {}) }() },
                AuditScreen("dlg-shortcuts") { over { ShellShortcutsDialog(onClose = {}) }() },
                AuditScreen("dlg-feedback") { over { FeedbackPopup(onClose = {}) }() },
                AuditScreen("dlg-whatsnew") { over { WhatsNewPopup(onClose = {}) }() },
                AuditScreen("dlg-language") { over { LanguagePopup(settings, onClose = {}) }() },
                AuditScreen("dlg-support") { over { SupportPopup(onClose = {}) }() },
                AuditScreen("dlg-rename") { over { RenameDialog("Night Train (2019) 1080p", onSave = {}, onDismiss = {}) }() },
                AuditScreen("dlg-update") {
                    over {
                        UpdatePopup(
                            release = release, skipped = false, installed = "2.0.0", kind = com.tmplayer.desktop.InstallKind.WindowsMsi,
                            canUpdate = true, progress = com.tmplayer.desktop.UpdateProgress.Downloading(0.42f),
                            onUpdate = {}, onOpenPage = {}, onLater = {}, onSkip = {}, onRestart = {}, onClose = {},
                        )
                    }()
                },
                AuditScreen("dlg-metadata-key") { over { MetadataKeyDialog(onClose = {}) }() },
                AuditScreen("card-language-notice") {
                    over {
                        Box(Modifier.fillMaxSize()) {
                            LanguageNoticeCard("de", onKeep = {}, onChange = {}, modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp))
                        }
                    }()
                },
                AuditScreen("card-window-failure") { over { WindowFailureCard(onTryAgain = {}, onSettings = {}) }() },
                AuditScreen("banner-offline") {
                    over {
                        ConnectionBanner(ConnectionNotice.Offline, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
                    }()
                },
                AuditScreen("banner-reconnecting") {
                    over {
                        ConnectionBanner(ConnectionNotice.Reconnecting, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
                    }()
                },
                AuditScreen("toast") {
                    val toasts = androidx.compose.material3.SnackbarHostState()
                    androidx.compose.runtime.LaunchedEffect(Unit) { toasts.showSnackbar("Low storage mode is on, so posters and previews are smaller until there is room again.") }
                    Box(Modifier.fillMaxSize()) {
                        HomeRowsView(shell, emptyList(), chats, emptyMap(), onRowShown = {}, onArtWanted = {}, onRefresh = {})
                        ToastHost(toasts, safeBottom = 0.dp)
                    }
                },
                AuditScreen("pin-pad", bar = false) {
                    PinPad(settings = settings, purpose = PinPurpose.Check, title = com.tmplayer.i18n.L.lockEnterForApp, onDone = {}, onCancel = null, brand = true)
                },
                AuditScreen("signin-phone", bar = false) { SignInScreen(com.tmplayer.data.AuthState.Phone()) },
                AuditScreen("signin-qr", bar = false) { SignInScreen(com.tmplayer.data.AuthState.Qr("tg://login?token=abc")) },
                AuditScreen("tour", bar = false) { com.tmplayer.ui.onboarding.TourOnly(onDone = {}, onSkip = {}) },
                AuditScreen("onboarding-support", bar = false) { com.tmplayer.ui.onboarding.SupportStepScreen(onOpenPage = {}, onSkip = {}) },
                stateScreen("state-empty", com.tmplayer.ui.components.UiState.Empty("Nothing here yet.", com.tmplayer.ui.components.StateAction.Refresh)),
                stateScreen(
                    "state-empty-hidden",
                    com.tmplayer.ui.components.UiState.Empty(
                        com.tmplayer.i18n.L.gridSmallHiddenAll(12), com.tmplayer.ui.components.StateAction.ShowSmall,
                        icon = com.tmplayer.ui.icons.TmIcons.FilterHorizontal,
                    ),
                ),
                stateScreen("state-error", com.tmplayer.ui.components.UiState.Error("Telegram did not answer. Try again in a moment.")),
                stateScreen("state-loading", com.tmplayer.ui.components.UiState.Loading("Loading your chats...", tip = com.tmplayer.ui.browse.FIRST_LOAD_TIP)),
            )
        }
    }
}

/** One drawn screen: its name, whether the floating bar stands at its foot, and the page. */
internal class AuditScreen(
    val name: String,
    val dest: Destination = Destination.Home,
    /** Settings and Downloads: the page is drawn as the panel over Home, as the window does. */
    val modal: Boolean = false,
    val bar: Boolean = true,
    val all: Boolean = true,
    val before: () -> Unit = {},
    val after: () -> Unit = {},
    val content: @Composable () -> Unit,
)

