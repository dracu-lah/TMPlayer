package com.tmplayer.ui.browse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The phone's floating chrome, composed for real and drawn to PNGs under
 * `build/device-test/review/`: the bar lit on Home, the bar on a page behind the picture (Settings among them)
 * with its pill of buttons, and a chat's page with its round Back and sticky bar, upright and on
 * its side. Each page's say over the bar is checked as the host reads it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TouchChromeRenderTest {

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private val nav = TouchNav(
        account = null,
        favoriteCount = 3,
        downloadCount = 1,
        updateVersion = null,
        onHome = {}, onChats = {}, onSearch = {}, onSettings = {}, onFavourites = {},
        onDownloads = {}, onHistory = {}, onUpdate = {},
    )

    private fun render(out: String, chrome: TouchChrome, pageKey: String, page: @androidx.compose.runtime.Composable () -> Unit) {
        FormFactor.override(false)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            TMPlayerTheme {
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    TouchNavHost(nav, chrome, pageKey) {
                        CompositionLocalProvider(LocalTouchPageKey provides pageKey) { page() }
                    }
                }
            }
        }
        repeat(20) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file = File("../build/device-test/review/$out").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }

    private fun rows() = @androidx.compose.runtime.Composable {
        LazyColumn(Modifier.fillMaxSize()) {
            items(40) { Text("Row $it", Modifier.padding(16.dp)) }
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun chatsLightsItsButton() {
        val chrome = TouchChrome()
        render("chrome-chats.png", chrome, "chats") {
            TouchPage(TouchPageSpec(current = TouchDestination.Chats))
            rows()()
        }
        // Chats is on the bar itself, so its own button is lit and the picture is not.
        assertEquals(TouchDestination.Chats, chrome.specs["chats"]?.current)
        assertFalse(chrome.specs["chats"]!!.inProfile)
        assertEquals("chats", chrome.pageKey)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h891dp-port-night-xhdpi")
    fun germanCaptionsFitTheirButtons() {
        // A long German caption steps down to fit its button rather than being cut.
        com.tmplayer.i18n.Translator.use("de")
        try {
            render("chrome-bar-de.png", TouchChrome(), "settings") {
                TouchPage(TouchPageSpec(current = TouchDestination.Chats))
                rows()()
            }
        } finally {
            com.tmplayer.i18n.Translator.use(com.tmplayer.i18n.Languages.ENGLISH)
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-notnight-xhdpi")
    fun profilePageRingsThePictureAndCarriesItsPill() {
        val chrome = TouchChrome()
        render("chrome-profile-page.png", chrome, "downloads") {
            TouchPage(
                TouchPageSpec(current = null, inProfile = true, actions = {
                    com.tmplayer.ui.components.FloatingBarIcon(TmIcons.Checklist, "Select", {})
                }),
            )
            rows()()
        }
        val spec = chrome.specs["downloads"]
        assertNotNull(spec)
        assertNull(spec!!.current)
        assertTrue(spec.inProfile)
        assertNotNull(spec.actions)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun chatPageHasNoBarButItsOwnChrome() {
        val chrome = TouchChrome()
        render("chrome-chat.png", chrome, "media") { chatPage() }
        // A chat says nothing to the bar, so the bar is not there.
        assertNull(chrome.specs["media"])
    }

    @Test
    @Config(sdk = [35], qualifiers = "w891dp-h411dp-land-night-xhdpi")
    fun chatPageSideways() {
        render("chrome-chat-land.png", TouchChrome(), "media") { chatPage() }
    }

    @androidx.compose.runtime.Composable
    private fun chatPage() {
        TouchMediaScaffold(
            chatTitle = "Films",
            chatPhotoFileId = 0,
            chatMiniThumbnail = null,
            isFavorite = true,
            query = "",
            onQuery = {},
            onSubmit = {},
            onBack = {},
            onToggleFavorite = {},
            onRefresh = {},
            onSort = {},
        ) { rows()() }
    }
}
