package com.tmplayer.ui.browse

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FeaturedPick
import com.tmplayer.data.FormFactor
import com.tmplayer.data.HomeFeatured
import com.tmplayer.data.MediaItem
import com.tmplayer.data.Profile
import com.tmplayer.data.SeriesShelf
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.ThemeChoice
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.components.TvConfirm
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.settings.SettingsPage
import com.tmplayer.ui.settings.SettingsScreen
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The pictures for `design/tv-focus/` (user, 2026-11-25): the television's top bar over Home with
 * its hero, the chips, Settings' rows and a dialog, each with the remote on one control, at 960 x
 * 540 px (mdpi) and at 1080p (xhdpi). Frames go to
 * `build/device-test/review/tv-focus-<label>-<scene>-<width>.png`, the label from the
 * `TVFOCUS_LABEL` environment variable (default "after"); `TVFOCUS_HERO_DP` is the carousel's height
 * (default the shipped 232, the old 328 for the "before" pictures). It asserts only that each
 * picture was written: the numbers are held by [TvOneIndicatorRenderTest] and [TvHeroHeightTest].
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvFocusPicturesTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
    }

    private val label get() = System.getenv("TVFOCUS_LABEL") ?: "after"
    private val heroDp get() = (System.getenv("TVFOCUS_HERO_DP")?.toFloatOrNull() ?: 232f).dp
    private val density get() = rule.activity.resources.displayMetrics.density

    private fun settingsFor(choice: ThemeChoice) {
        runBlocking { SettingsStore(RuntimeEnvironment.getApplication()).setThemeChoice(choice) }
    }

    private fun show(content: @Composable () -> Unit) {
        FormFactor.override(true)
        Motion.reduced = true
        settingsFor(ThemeChoice.Dark)
        rule.setContent { TMPlayerTheme { Box(Modifier.fillMaxSize().background(Tone.background)) { content() } } }
        val viewRoot = rule.activity.window.decorView.parent
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "ensureTouchMode",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "windowFocusChanged",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
        )
        rule.waitForIdle()
    }

    private fun picture(scene: String) {
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val width = rule.activity.resources.displayMetrics.widthPixels
        val file = File("../build/device-test/review/tv-focus-$label-$scene-$width.png").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("$scene written", file.length() > 0)
    }

    private fun video(id: Long, name: String) = MediaItem(
        chatId = 7, messageId = id, fileId = id.toInt(), title = name, sizeBytes = 900_000_000 + id * 90_000_000,
        durationSec = 2_400, mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null, date = (1000 - id).toInt(), fileName = name,
    )

    private fun picks(): List<FeaturedPick> = HomeFeatured.pick(
        SeriesShelf.arrange(
            listOf(
                video(1, "Night.Train.2019.1080p.WEB-DL.mkv"),
                video(2, "Paper.Moons.2018.1080p.BluRay.mkv"),
                video(3, "Low.Tide.2022.720p.WEB.mkv"),
            ),
        ),
    )

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun homeAt1080p() = home()

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-mdpi")
    fun homeAt960x540() = home()

    /** The top bar standing on the hero, the first row under it, the remote on the bar's Home tab. */
    private fun home() {
        val nav = TouchNav(
            account = null, favoriteCount = 0, downloadCount = 1, updateVersion = null,
            onHome = {}, onChats = {}, onSearch = {}, onSettings = {},
            onFavourites = {}, onDownloads = {}, onHistory = {}, onUpdate = {},
            profile = Profile(id = "a", folder = null, name = "Maya Lee"),
        )
        val all = picks()
        val tab = FocusRequester()
        show {
            Column(Modifier.fillMaxSize()) {
                TvFeaturedCarousel(
                    picks = all,
                    height = heroDp,
                    start = 48.dp,
                    end = 48.dp,
                    watch = SeriesWatch(point = { null }, finished = { false }),
                    chatTitle = { "Weekend videos" },
                    active = false,
                    onPlay = { _, _ -> },
                    onDetails = {},
                    header = {
                        TvTopBar(
                            nav = nav, current = TvBarTab.Home, inProfile = false, searchOpen = false,
                            onHome = {}, onChats = {}, onSearch = {}, onRefresh = null,
                            currentFocus = tab, contentFocus = remember { FocusRequester() }, overArt = true,
                        )
                    },
                    spot = remember { mutableIntStateOf(0) },
                )
                Text("Continue watching", color = Tone.text, modifier = Modifier.padding(start = 48.dp, top = 12.dp, bottom = 8.dp))
                Row(Modifier.padding(start = 48.dp)) {
                    for (n in 0 until 4) {
                        VideoTile(title = "Video $n", onClick = {}, modifier = Modifier.width(208.dp).padding(end = 12.dp)) {
                            Box(Modifier.fillMaxSize().background(Color(0xFF1B2433)))
                        }
                    }
                }
            }
        }
        tab.requestFocus()
        picture("home-bar")
        rule.onNode(hasText("Play") and hasClickAction()).requestFocus()
        picture("home-play")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun chipsAt1080p() = chips()

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-mdpi")
    fun chipsAt960x540() = chips()

    private fun chips() {
        show {
            Column(Modifier.padding(48.dp)) {
                OrderChips(
                    chips = listOf(
                        OrderChip("new", "Newest", TmIcons.Search, selected = true) {},
                        OrderChip("name", "Name", TmIcons.Search, selected = false) {},
                        OrderChip("big", "Largest", TmIcons.Search, selected = false) {},
                    ),
                    description = "Order",
                )
            }
        }
        rule.onNode(hasText("Name") and hasClickAction()).requestFocus()
        picture("chips")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun settingsAt1080p() = settings()

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-mdpi")
    fun settingsAt960x540() = settings()

    private fun settings() {
        show { SettingsScreen(chats = emptyList(), onLoggedOut = {}, initialPage = SettingsPage.Appearance, accountFolders = emptyList()) }
        rule.onNode(hasText("Language") and androidx.compose.ui.test.isFocusable()).requestFocus()
        picture("settings")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun dialogAt1080p() = dialog()

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-mdpi")
    fun dialogAt960x540() = dialog()

    private fun dialog() {
        show {
            TvConfirm(
                title = "Remove this list?",
                message = "The videos stay where they are.",
                confirmLabel = "Remove",
                onConfirm = {},
                onDismiss = {},
                destructive = false,
            )
        }
        rule.onNode(hasText("Remove") and hasClickAction()).requestFocus()
        picture("dialog")
    }
}
