package com.tmplayer.desktop.promo

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.tmplayer.data.AuthState
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.UpdateScheduler
import com.tmplayer.data.WatchedRecord
import com.tmplayer.data.WatchedStore
import com.tmplayer.data.SeriesShelf
import com.tmplayer.data.ShelfEntry
import com.tmplayer.desktop.ui.SeriesPage
import com.tmplayer.desktop.ui.rememberSeriesWatch
import com.tmplayer.ui.browse.SeriesViewToggle
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.DesktopWatchCache
import com.tmplayer.desktop.player.AbLoop
import com.tmplayer.desktop.player.Episodes
import com.tmplayer.desktop.player.MediaTrack
import com.tmplayer.desktop.player.OpenPrefs
import com.tmplayer.desktop.player.PlaybackEngine
import com.tmplayer.desktop.player.PlaybackStatus
import com.tmplayer.desktop.player.PlayerMedia
import com.tmplayer.desktop.player.PlayerScreen
import com.tmplayer.desktop.player.TrackType
import com.tmplayer.desktop.ui.ChatAvatar
import com.tmplayer.desktop.ui.ChatList
import com.tmplayer.desktop.ui.DesktopExtras
import com.tmplayer.desktop.ui.PageHeader
import com.tmplayer.desktop.ui.PosterSizeStep
import com.tmplayer.desktop.ui.SearchField
import com.tmplayer.desktop.ui.ShellState
import com.tmplayer.desktop.ui.Sidebar
import com.tmplayer.desktop.ui.SignInScreen
import com.tmplayer.desktop.ui.VideoGrid
import com.tmplayer.desktop.ui.rememberKeyboardNav
import com.tmplayer.desktop.ui.ListSurface
import com.tmplayer.i18n.Languages
import com.tmplayer.i18n.Translator
import com.tmplayer.ui.onboarding.OnboardingPage
import com.tmplayer.ui.onboarding.OnboardingTour
import com.tmplayer.ui.onboarding.TourState
import com.tmplayer.ui.onboarding.onboardingImageName
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkImage
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.UriMediaData
import java.io.File
import java.nio.file.Files

/**
 * The desktop's promo fixture, the counterpart of the Android `app/src/promo/`: the real sign in,
 * chat list, chat grid and player, drawn off screen over made-up chats and the same demo pictures
 * the phone and TV fixture ships, so nothing on screen belongs to anybody. Each is drawn light and
 * dark at 1280x800 dp and a density of 1.5, which is a 1920x1200 picture.
 *
 * As a test it only checks that every screen draws. With TMPLAYER_PROMO=<dir> it also writes the
 * PNGs there:
 *
 *     TMPLAYER_PROMO=/tmp/promo ./gradlew :desktop:test --tests '*PromoShots*' -PdesktopVersion=1.22.1
 *
 * (the version is the one the sidebar shows beside the logo; without it, a shot says 1.0.0-dev).
 *
 * They become the README and site screenshots (`desktop-<shot>[-light].webp`) and the desktop
 * tour's pictures (`ui/src/jvmMain/resources/onboarding/`), each converted to WebP at quality 88 as
 * CLAUDE.md says. The sign in QR code encodes a made-up token that signs nobody in.
 */
class PromoShots {

    private val dir = Files.createTempDirectory("tm-promo").toFile()
    private val settings = SettingsStore(SettingsStore.openDataStore(dir.resolve(SettingsStore.FILE_NAME)))
    private val prefs = DesktopPrefs(dir.resolve("desktop.properties"))
    private val shell = ShellState(
        settings,
        object : DownloadRunner {
            override fun download(request: DownloadRequest) = Unit
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        },
        WatchedStore(WatchedStore.openDataStore(dir.resolve(WatchedStore.FILE_NAME))),
        services = {
            DesktopExtras(
                prefs = prefs,
                updates = UpdateScheduler(settings.updatePrefs, check = { _, _ -> false }, onSkip = {}),
                watchCache = DesktopWatchCache(settings, { dir.resolve("cache") }),
            )
        },
    )

    private val chats = listOf(
        ChatSummary(101, "Weekend Clips", demo("coast"), 0, ChatKind.Group, unreadCount = 4, isPinned = true),
        ChatSummary(102, "Home Projects", demo("workshop"), 0, ChatKind.Channel, unreadCount = 12),
        ChatSummary(103, "Recipe Notes", demo("kitchen"), 0, ChatKind.Group),
        ChatSummary(104, "Travel Diary", demo("forest"), 0, ChatKind.Channel, unreadCount = 2),
        ChatSummary(105, "Design Study", demo("tutorial"), 0, ChatKind.Direct),
        ChatSummary(106, "Family Archive", demo("birthday"), 0, ChatKind.Group),
        ChatSummary(107, "Garden Year", demo("forest"), 0, ChatKind.Channel),
        ChatSummary(108, "Weeknight Dinners", demo("kitchen"), 0, ChatKind.Channel, unreadCount = 1),
        ChatSummary(109, "Bike Repairs", demo("workshop"), 0, ChatKind.Group),
        ChatSummary(110, "Sketchbook", demo("tutorial"), 0, ChatKind.Direct),
    )

    private val videos: List<MediaItem> = run {
        fun item(id: Long, title: String, picture: String, sizeMb: Long, duration: Int, file: String) = MediaItem(
            chatId = 101, messageId = id, fileId = 0, title = title, sizeBytes = sizeMb * 1024 * 1024,
            durationSec = duration, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = demo(picture),
            date = 0, fileName = file,
        )
        val set = listOf(
            item(1, "Coast walk, day 2", "coast", 428, 1_482, "coast-walk-day-2-1080p.mp4"),
            item(2, "Chickpea salad recipe", "kitchen", 186, 724, "chickpea-salad-1080p.mp4"),
            item(3, "Build a small shelf, part 1", "workshop", 612, 2_115, "small-shelf-part-1-1080p.mkv"),
            item(4, "Birthday highlights", "birthday", 344, 1_104, "birthday-highlights-1080p.mp4"),
            item(5, "Shape basics tutorial", "tutorial", 238, 968, "shape-basics-1080p.webm"),
            item(6, "Forest trail morning", "forest", 391, 1_376, "forest-trail-1080p.mp4"),
        )
        // Twice through, so the grid runs past the bottom of the window as a real chat's does.
        set + set.map { it.copy(messageId = it.messageId + set.size, title = it.title.replace("day 2", "day 3").replace("part 1", "part 2")) }
    }

    @Test
    fun signIn() = both("signin") {
        SignInScreen(AuthState.Qr("tg://login?token=TMPlayerPromoFixtureSignsNobodyIn"))
    }

    @Test
    fun chats() = both("chats") {
        withSidebar {
            val list = rememberLazyListState()
            val nav = rememberKeyboardNav(remember(list) { ListSurface(list) })
            val section = shell.chatSection
            Column(Modifier.fillMaxSize()) {
                PageHeader(
                    title = section.heading,
                    subtitle = section.blurb,
                    actions = {
                        SearchField("", {}, "Search chats", shell.searchFocus, Modifier.width(320.dp))
                        IconButton(onClick = {}) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                    },
                )
                ChatList(chats, favourites = setOf(102L, 104L), listState = list, nav = nav, onOpen = {}, onStar = {})
            }
        }
    }

    @Test
    fun grid() = both("grid") {
        withSidebar {
            Column(Modifier.fillMaxSize()) {
                PageHeader(
                    title = "Weekend Clips",
                    subtitle = "${videos.size} videos",
                    leading = {
                        IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to chats") }
                        ChatAvatar(chats.first().miniThumbnail, 0, "Weekend Clips", 40.dp)
                    },
                    actions = {
                        SearchField("", {}, "Search this chat", shell.searchFocus, Modifier.width(300.dp))
                        PosterSizeStep(shell)
                        IconButton(onClick = {}) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                    },
                )
                VideoGrid(shell, videos, "Weekend Clips")
            }
        }
    }

    /**
     * Three made-up shows named the ways real uploads are (a scene release, the fansub dash form,
     * and a caption carrying the episode), ahead of the ordinary videos.
     */
    private val seriesVideos: List<MediaItem> = run {
        val pictures = listOf("coast", "forest", "workshop", "kitchen", "tutorial", "birthday")
        var id = 200L
        fun episode(file: String, sizeMb: Long, minutes: Int, caption: String = "") = MediaItem(
            chatId = 101, messageId = id, fileId = 0, title = file, sizeBytes = sizeMb * 1024 * 1024,
            durationSec = minutes * 60, mimeType = "video/x-matroska", thumbnailFileId = 0,
            miniThumbnail = demo(pictures[(id++ % pictures.size).toInt()]), date = id.toInt(), fileName = file, caption = caption,
        )
        val harbour = (1..6).map { episode("Harbour.Notes.S01E%02d.1080p.WEB-DL.mkv".format(it), 820, 44) } +
            (1..4).map { episode("Harbour.Notes.S02E%02d.1080p.WEB-DL.mkv".format(it), 860, 47) }
        val garden = (1..5).map { episode("[Demo] Sky Garden - %02d (1080p).mkv".format(it), 340, 24) }
        val kitchen = (1..3).map { episode("kitchen_journal_720p_part$it.mp4", 210, 18, caption = "Kitchen Journal Ep $it\nNew every Friday") }
        (harbour + garden + kitchen).reversed() + videos.take(6)
    }

    /** Season one of Harbour Notes watched to E03 and stopped in E04; Sky Garden finished. */
    private fun seedWatching() = runBlocking {
        seriesVideos.filter { Regex("""S01E0[1-3]""").containsMatchIn(it.fileName) || it.fileName.contains("Sky Garden") }
            .forEach {
                shell.watched.markWatched(
                    WatchedRecord(it.chatId, it.messageId, 0, it.title, "Weekend Clips", it.sizeBytes, it.durationSec, 1L, manual = true),
                )
            }
        seriesVideos.first { it.fileName.contains("S01E04") }.let {
            settings.saveResumePosition(it.chatId, it.messageId, 19 * 60_000L, 44 * 60_000L)
        }
    }

    @Composable
    private fun chatPage(subtitle: String, body: @Composable () -> Unit) {
        withSidebar {
            Column(Modifier.fillMaxSize()) {
                PageHeader(
                    title = "Weekend Clips",
                    subtitle = subtitle,
                    leading = {
                        IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to chats") }
                        ChatAvatar(chats.first().miniThumbnail, 0, "Weekend Clips", 40.dp)
                    },
                    actions = {
                        SearchField("", {}, "Search this chat", shell.searchFocus, Modifier.width(300.dp))
                        PosterSizeStep(shell)
                        IconButton(onClick = {}) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                    },
                )
                body()
            }
        }
    }

    /** The chat with its shows folded into posters, and the switch above them. */
    @Test
    fun series() = both("series", before = ::seedWatching) {
        chatPage("${seriesVideos.size} videos") {
            VideoGrid(
                shell, seriesVideos, "Weekend Clips",
                shelf = SeriesShelf.arrange(seriesVideos),
                viewSwitch = { SeriesViewToggle(true, {}) },
            )
        }
    }

    /** The same chat with "All files" chosen. */
    @Test
    fun allFiles() = both("series-all-files", before = ::seedWatching) {
        chatPage("${seriesVideos.size} videos") {
            VideoGrid(shell, seriesVideos, "Weekend Clips", viewSwitch = { SeriesViewToggle(false, {}) })
        }
    }

    /** A show opened: the season dropdown, the play button and the episode list. */
    @Test
    fun seriesOpen() = both("series-open", before = ::seedWatching) {
        val show = (SeriesShelf.arrange(seriesVideos).first { it is ShelfEntry.Show && it.series.key == "harbour notes" } as ShelfEntry.Show).series
        chatPage("${seriesVideos.size} videos") { SeriesPage(shell, show, rememberSeriesWatch(shell), onClose = {}) }
    }

    /** The season dropdown open over the episodes. */
    @Test
    fun seriesDropdown() = both("series-dropdown", before = ::seedWatching, darkOnly = true) {
        val show = (SeriesShelf.arrange(seriesVideos).first { it is ShelfEntry.Show && it.series.key == "harbour notes" } as ShelfEntry.Show).series
        chatPage("${seriesVideos.size} videos") { SeriesPage(shell, show, rememberSeriesWatch(shell), onClose = {}, startOpen = true) }
    }

    @Test
    fun player() = both("player", before = {
        // Controls that never hide, so the shot has them up however long the frame takes.
        runBlocking { settings.updateTouchPrefs { it.copy(controlsTimeoutMs = 0L) } }
    }) {
        val frame = remember { SkImage.makeFromEncoded(demo("coast")).toComposeImageBitmap() }
        Box(Modifier.fillMaxSize()) {
            // libmpv draws the picture under the window's Compose layer; the fixture puts a still there.
            Image(frame, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            PlayerScreen(
                // Named as a person would name the file, which is what the player's title shows.
                media = PromoMedia(videos.first().copy(fileName = "Coast walk, day 2.mp4")),
                startFromBeginning = false,
                onBack = {},
                fullscreen = false,
                onToggleFullscreen = {},
                settings = settings,
                prefs = prefs,
                engineFactory = { StillEngine() },
                backdrop = androidx.compose.ui.graphics.Color.Transparent,
            )
        }
    }

    @org.junit.After
    fun forgetOnline() {
        com.tmplayer.online.OnlineSubtitles.current = null
    }

    /** The subtitle menu with "Search online" in it, in a build that has the OpenSubtitles key. */
    @Test
    fun playerSubtitleMenu() = both("player-subtitles-online", darkOnly = true, before = {
        runBlocking { settings.updateTouchPrefs { it.copy(controlsTimeoutMs = 0L) } }
        com.tmplayer.desktop.ui.OnlineFixture.install("signed_in")
    }) {
        val frame = remember { SkImage.makeFromEncoded(demo("coast")).toComposeImageBitmap() }
        Box(Modifier.fillMaxSize()) {
            Image(frame, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            PlayerScreen(
                media = PromoMedia(videos.first().copy(fileName = "Coast walk, day 2.mp4")),
                startFromBeginning = false,
                onBack = {},
                fullscreen = false,
                onToggleFullscreen = {},
                settings = settings,
                prefs = prefs,
                engineFactory = { StillEngine() },
                backdrop = androidx.compose.ui.graphics.Color.Transparent,
                menuOpen = "Subtitles",
            )
        }
    }

    /** The tour as the desktop shows it before sign in, page by page; not shipped anywhere, only checked. */
    @Test
    fun tour() {
        for (page in OnboardingPage.entries) {
            both("tour-${page.name.lowercase()}") {
                OnboardingTour(settings, onDone = {}, firstRun = true, tour = remember { TourState(true, start = page.ordinal) })
            }
        }
    }

    /** The same in the en-XA pseudo-locale: longer, accented text, to see what a translation will do to it. */
    @Test
    fun tourPseudo() {
        Translator.use(Languages.PSEUDO)
        try {
            for (page in OnboardingPage.entries) {
                both("tour-xa-${page.name.lowercase()}", darkOnly = true) {
                    OnboardingTour(settings, onDone = {}, firstRun = true, tour = remember { TourState(true, start = page.ordinal) })
                }
            }
        } finally {
            Translator.use(Languages.ENGLISH)
        }
    }

    @Composable
    private fun withSidebar(page: @Composable () -> Unit) {
        Row(Modifier.fillMaxSize()) {
            Sidebar(shell, null, emptyList())
            VerticalDivider(color = Tone.outline)
            Box(Modifier.weight(1f).fillMaxHeight()) { page() }
        }
    }

    private fun both(name: String, before: () -> Unit = {}, darkOnly: Boolean = false, content: @Composable () -> Unit) {
        before()
        for (dark in if (darkOnly) listOf(true) else listOf(true, false)) {
            val png = render(dark, content)
            assertTrue("$name drew nothing", png.size > 10_000)
            val out = System.getenv("TMPLAYER_PROMO")?.takeIf { it.isNotBlank() } ?: continue
            File(out).apply { mkdirs() }.resolve(if (dark) "$name.png" else "$name-light.png").writeBytes(png)
        }
    }

    private fun render(dark: Boolean, content: @Composable () -> Unit): ByteArray =
        ImageComposeScene(WIDTH, HEIGHT, Density(DENSITY)) {
            TmMaterialTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = Tone.background) { content() }
            }
        }.use { scene ->
            scene.render(0)
            // Thumbnails and the QR code are made off the composition thread; give them a moment.
            Thread.sleep(800)
            scene.render(500_000_000)
            Thread.sleep(200)
            scene.render(1_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }

    /** A demo picture from the Android promo build, so both fixtures show the same made-up media. */
    private fun demo(name: String): ByteArray = File(DEMO_DIR, "demo_$name.webp").readBytes()

    /** Plays nothing: opens straight into a paused-free, mid-video state for the controls to show. */
    private class StillEngine : PlaybackEngine {
        private val status = MutableStateFlow(PlaybackStatus())
        override val state: StateFlow<PlaybackStatus> = status
        override val tracks: StateFlow<List<MediaTrack>> = MutableStateFlow(emptyList())
        override suspend fun open(data: MediaData, startAtMs: Long, prefs: OpenPrefs) {
            status.value = PlaybackStatus(
                opened = true, playing = true, durationMs = 1_482_000, positionMs = 568_000, bufferedMs = 760_000,
                videoWidth = 1920, videoHeight = 1080, volume = 80,
            )
        }
        override fun play() = status.update { it.copy(playing = true) }
        override fun pause() = status.update { it.copy(playing = false) }
        override fun stop() = Unit
        override fun togglePlay() = status.update { it.copy(playing = !it.playing) }
        override fun seekTo(positionMs: Long) = Unit
        override fun seekBy(deltaMs: Long) = Unit
        override fun frameStep(forward: Boolean) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun setVolume(percent: Int) = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun selectTrack(type: TrackType, id: Int?) = Unit
        override fun setScale(scale: com.tmplayer.player.VideoScale) = Unit
        override fun setDownmix(stereo: Boolean) = Unit
        override fun setVolumeBoost(on: Boolean) = Unit
        override fun setSubtitleStyle(style: com.tmplayer.player.SubtitleStyle) = Unit
        override fun setSubtitleDelay(ms: Long) = Unit
        override fun setAudioDelay(ms: Long) = Unit
        override fun addSubtitle(path: String): Boolean = false
        override suspend fun screenshot(file: File, withSubtitles: Boolean): Boolean = false
        override fun setAbLoop(loop: AbLoop?) = Unit
        override fun details(): List<Pair<String, String>> = emptyList()
        override fun close() = Unit
    }

    private class PromoMedia(override val item: MediaItem) : PlayerMedia {
        override val chatTitle = "Weekend Clips"
        override suspend fun open(): MediaData = UriMediaData("promo.mp4")
        override suspend fun episodes(order: com.tmplayer.data.EpisodeOrder) = Episodes()
        override fun episode(other: MediaItem): PlayerMedia = PromoMedia(other)
        override val downloaded: StateFlow<Float?> = MutableStateFlow(null)
        override suspend fun tdlibVersion(): String? = null
        override fun release() = Unit
        override val fromTelegram: Boolean get() = true
    }

    private companion object {
        const val WIDTH = 1920
        const val HEIGHT = 1200
        const val DENSITY = 1.5f

        /** Gradle runs the tests from the module directory. */
        val DEMO_DIR = File("../app/src/promo/res/drawable-nodpi")

        init {
            // The tour reads these names; keep the fixture's output and the classpath in step.
            check(onboardingImageName(OnboardingPage.HowItWorks, dark = true) == "chats-dark.webp")
        }
    }
}
