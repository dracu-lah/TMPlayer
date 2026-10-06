package com.tmplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.use
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.UpdateScheduler
import com.tmplayer.data.WatchedStore
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.DesktopWatchCache
import com.tmplayer.desktop.WatchedWords
import com.tmplayer.desktop.player.MenuAt
import com.tmplayer.desktop.player.MenuPage
import com.tmplayer.desktop.player.PlaybackStatus
import com.tmplayer.desktop.player.PlayerMenu
import com.tmplayer.desktop.player.PlayerTheme
import com.tmplayer.i18n.Languages
import com.tmplayer.i18n.Translator
import com.tmplayer.ui.i18n.ProvideStrings
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * CP23 on the desktop: the Settings language row and its picker, a language change in a window
 * that is already drawn, the "Now in ..." card, "What's new", "Report a problem", Arabic right to
 * left, and a line in each script the system fonts have to cover. With TMPLAYER_SHOTS=<dir> the
 * frames are written there as `shell-<name>.png`.
 */
class AppShellShotsTest {

    private val settings = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-shell").resolve(SettingsStore.FILE_NAME).toFile()),
    )
    private val dir = Files.createTempDirectory("tm-shell-extras").toFile()
    private val shell = ShellState(
        settings,
        object : DownloadRunner {
            override fun download(request: DownloadRequest) = Unit
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        },
        WatchedStore(WatchedStore.openDataStore(Files.createTempDirectory("tm-shell-watched").resolve(WatchedStore.FILE_NAME).toFile())),
        services = {
            DesktopExtras(
                prefs = DesktopPrefs(dir.resolve("desktop.properties")),
                updates = UpdateScheduler(settings.updatePrefs, check = { _, _ -> false }, onSkip = {}),
                watchCache = DesktopWatchCache(settings, { dir.resolve("cache") }),
            )
        },
    )

    @After
    fun english() = Translator.use(Languages.ENGLISH)

    @Test
    fun settingsPickerAndPopups() {
        shot("settings", height = 2700) { SettingsPage(shell, "1.23.0") }
        shot("language-picker") {
            Box(Modifier.fillMaxSize()) {
                SettingsPage(shell, "1.23.0")
                LanguagePopup(settings, onClose = {})
            }
        }
        shot("whatsnew") {
            Box(Modifier.fillMaxSize()) {
                SettingsPage(shell, "1.23.0")
                WhatsNewPopup(onClose = {})
            }
        }
        shot("feedback") {
            Box(Modifier.fillMaxSize()) {
                SettingsPage(shell, "1.23.0")
                FeedbackPopup(onClose = {})
            }
        }
        shot("now-in") {
            Box(Modifier.fillMaxSize()) {
                SettingsPage(shell, "1.23.0")
                LanguageNoticeCard("de", onKeep = {}, onChange = {}, modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp))
            }
        }
    }

    /** One scene, drawn in English, then switched to en-XA and drawn again with nothing rebuilt. */
    @Test
    fun languageChangesWithoutARestart() {
        scene(HEIGHT) { SettingsPage(shell, "1.23.0") }.use { scene ->
            settle(scene)
            save("live-before", scene.render(2_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            runBlocking { settings.setLanguage(Languages.PSEUDO) }
            Translator.pseudoEnabled = true
            Translator.select(Languages.PSEUDO, emptyList())
            scene.render(2_500_000_000)
            Thread.sleep(300)
            save("live-after", scene.render(3_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        Translator.pseudoEnabled = false
    }

    @Test
    fun arabicRightToLeft() {
        Translator.use("ar")
        shot("rtl-settings") { SettingsPage(shell, "1.23.0") }
        shot("rtl-playermenu", sidebar = false) {
            PlayerTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF203040))) {
                    Box(Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 300.dp)) {
                        PlayerMenu(
                            menu = MenuAt(MenuPage.Main, MenuAt.Anchor.Overflow),
                            status = PlaybackStatus(opened = true, durationMs = 2_634_000, positionMs = 1_260_000, playing = true),
                            tracks = emptyList(),
                            fullscreen = false,
                            ignoreClicks = false,
                            miniPlayerAvailable = true,
                            alwaysOnTopAvailable = true,
                            fromTelegram = true,
                            savable = true,
                            watchedLabel = WatchedWords.markLabel(false),
                            sleepTimer = null,
                            onOpenMenu = {},
                            onClose = {},
                            onAction = {},
                        )
                    }
                }
            }
        }
    }

    /** A line in every script the languages need, in the default font family and nothing bundled. */
    @Test
    fun everyScriptRenders() {
        shot("scripts", sidebar = false) {
            Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                for ((tag, line) in SCRIPTS) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Text(tag, style = MaterialTheme.typography.titleMedium, color = Tone.muted, modifier = Modifier.padding(top = 6.dp))
                        Text(line, fontSize = 30.sp, color = Tone.text)
                    }
                }
            }
        }
    }

    private fun shot(name: String, sidebar: Boolean = true, height: Int = HEIGHT, page: @Composable () -> Unit) {
        scene(height, sidebar, page).use { scene ->
            settle(scene)
            save(name, scene.render(2_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }

    private fun scene(height: Int, sidebar: Boolean = true, page: @Composable () -> Unit) =
        ImageComposeScene(WIDTH, height, Density(1f)) {
            TmMaterialTheme(dark = true) {
                ProvideStrings {
                    Surface(Modifier.fillMaxSize(), color = Tone.background) {
                        if (sidebar) {
                            Row(Modifier.fillMaxSize()) {
                                Sidebar(shell, null, emptyList())
                                VerticalDivider(color = Tone.outline)
                                Box(Modifier.weight(1f).fillMaxHeight()) { page() }
                            }
                        } else {
                            page()
                        }
                    }
                }
            }
        }

    private fun settle(scene: ImageComposeScene) {
        scene.render(0)
        Thread.sleep(400)
        scene.render(500_000_000)
        Thread.sleep(200)
        scene.render(1_000_000_000)
    }

    private fun save(name: String, png: ByteArray) {
        assertTrue(png.size > 1000)
        val out = System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        File(out).apply { mkdirs() }.resolve("shell-$name.png").writeBytes(png)
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 800

        val SCRIPTS = listOf(
            "zh-CN" to "简体中文：继续观看，字幕和音轨",
            "ja" to "日本語：続きから再生、字幕と音声",
            "ko" to "한국어: 이어서 보기, 자막과 오디오",
            "ar" to "العربية: متابعة المشاهدة والترجمة",
            "hi" to "हिन्दी: देखना जारी रखें, उपशीर्षक",
            "ml" to "മലയാളം: കാണുന്നത് തുടരുക, സബ്ടൈറ്റിൽ",
            "ru" to "Русский: продолжить просмотр",
            "vi" to "Tiếng Việt: tiếp tục xem, phụ đề",
        )
    }
}
