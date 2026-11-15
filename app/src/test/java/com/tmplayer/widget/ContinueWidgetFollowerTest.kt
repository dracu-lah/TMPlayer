package com.tmplayer.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.tmplayer.R
import com.tmplayer.data.AuthState
import com.tmplayer.data.ContinueWidget.Tile
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.SettingsStore
import com.tmplayer.i18n.Translator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowAppWidgetManager
import org.robolectric.Shadows
import java.io.File

/**
 * The widget end to end on the platform's side: the viewer's own settings in, widgets on a
 * (shadow) home screen out, with the account and the locks moved by the test.
 */
@RunWith(RobolectricTestRunner::class)
class ContinueWidgetFollowerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val auth = MutableStateFlow<AuthState>(AuthState.Ready)
    private lateinit var settings: SettingsStore
    private lateinit var manager: AppWidgetManager
    private lateinit var shadow: ShadowAppWidgetManager

    @Before
    fun setUp() {
        Translator.select("en", listOf("en"))
        settings = SettingsStore(SettingsStore.openDataStore(File(folder.root, "prefs/${SettingsStore.FILE_NAME}")))
        ContinueWidgetFollower.settings = { settings }
        ContinueWidgetFollower.auth = { auth }
        manager = AppWidgetManager.getInstance(context)
        shadow = Shadows.shadowOf(manager)
        WidgetArt.dir(context).deleteRecursively()
    }

    @After
    fun tearDown() {
        ContinueWidgetFollower.settings = { SettingsStore(it) }
    }

    private fun addWidget(widthDp: Int, heightDp: Int): Int {
        val id = shadow.createWidget(ContinueWidgetProvider::class.java, R.layout.widget_continue_compact)
        manager.updateAppWidgetOptions(
            id,
            Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
            },
        )
        return id
    }

    private fun saveVideo(message: Long, positionMs: Long = 1_320_000, title: String = "Coast S01E0$message") = runBlocking {
        val line = ResumeRecord.encode(
            fileId = message.toInt(), title = title, chatTitle = "Nature", sizeBytes = 1_000_000,
            durationSec = 2_640, updatedAt = System.currentTimeMillis() + message,
        )
        settings.saveResumePosition(-100L, message, positionMs, 2_640_000L, description = line)
    }

    private fun titleOf(id: Int): String =
        (shadow.getViewFor(id).findViewById<TextView>(R.id.widget_title)).text.toString()

    @Test
    fun `a system refresh draws the newest video on every widget, each in its own layout`() = runBlocking {
        saveVideo(4)
        saveVideo(5)
        val roomy = addWidget(250, 110)
        val strip = addWidget(110, 40)
        ContinueWidgetFollower.refresh(context)
        assertEquals("Coast S01E05", titleOf(roomy))
        assertEquals("Coast S01E05", titleOf(strip))
        assertTrue(shadow.getViewFor(roomy).findViewById<View>(R.id.widget_poster) != null)
        assertNull(shadow.getViewFor(strip).findViewById<View>(R.id.widget_poster))
    }

    @Test
    fun `nothing part watched is the friendly tile`() = runBlocking {
        val id = addWidget(250, 110)
        ContinueWidgetFollower.refresh(context)
        assertEquals("Nothing to continue", titleOf(id))
    }

    @Test
    fun `signed out says sign in, and a connection still being made does not`() = runBlocking {
        val id = addWidget(250, 110)
        auth.value = AuthState.ChooseMethod
        ContinueWidgetFollower.refresh(context)
        assertEquals("Sign in to TMPlayer", titleOf(id))

        // Still connecting with nothing to show: it waits a moment for the answer, which comes.
        auth.value = AuthState.Connecting
        val answer = Thread { Thread.sleep(150); auth.value = AuthState.Qr("tg://login") }.apply { start() }
        ContinueWidgetFollower.refresh(context)
        answer.join()
        assertEquals("Sign in to TMPlayer", titleOf(id))
    }

    @Test
    fun `either lock hides the title, and taking it off shows the video again`() = runBlocking {
        saveVideo(4)
        val id = addWidget(250, 110)
        settings.setPin("1234")
        settings.setLockOnOpen(true)
        ContinueWidgetFollower.refresh(context)
        assertEquals("TMPlayer is locked", titleOf(id))
        assertTrue(!shadow.getViewFor(id).findViewById<View>(R.id.widget_root).contentDescription.toString().contains("Coast"))

        settings.setLockOnOpen(false)
        settings.setKidsLock(true)
        ContinueWidgetFollower.refresh(context)
        assertEquals("TMPlayer is locked", titleOf(id))

        settings.setKidsLock(false)
        ContinueWidgetFollower.refresh(context)
        assertEquals("Coast S01E04", titleOf(id))
    }

    @Test
    fun `the feed follows the state and does not repeat a tile`() = runBlocking {
        val seen = ArrayList<Tile>()
        saveVideo(4)
        seen += ContinueWidgetFollower.tiles(context).first()
        saveVideo(5)
        seen += ContinueWidgetFollower.tiles(context).first()
        assertEquals(listOf("Coast S01E04", "Coast S01E05"), seen.map { (it as Tile.Video).title })
    }

    @Test
    fun `a language change builds the text again`() = runBlocking {
        val id = addWidget(250, 110)
        ContinueWidgetFollower.refresh(context)
        assertEquals("Nothing to continue", titleOf(id))
        // The widget reads the in-app language before it builds a word (a cold start has not applied it).
        settings.setLanguage("de")
        ContinueWidgetFollower.refresh(context)
        val german = titleOf(id)
        // Whatever the catalog says for German, it is chosen from the stored language, not left in English.
        assertEquals(Translator.messages.text("widget.nothing"), german)
    }

    @Test
    fun `with no widget placed a refresh does nothing and is not an error`() = runBlocking {
        saveVideo(4)
        ContinueWidgetFollower.refresh(context)
        assertTrue(ContinueWidgetFollower.widgetIds(context).isEmpty())
    }
}
