package com.tmplayer.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.browse.ChatFilter
import com.tmplayer.ui.browse.HistoryTab
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * The selected glyphs' drop and the empty pages' picture on Android's own renderer, where the fill's
 * knock-out layer and the drawn-on strokes go through a different canvas from the desktop's: the
 * twins outlined, part way through filling and filled, and the empty states of a phone and a
 * television at rest and mid-arrival. PNGs under `build/device-test/review/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EmptyAndFillRenderTest {

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private fun activity(tv: Boolean, content: @Composable () -> Unit): ComponentActivity {
        FormFactor.override(tv)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            TMPlayerTheme {
                Box(Modifier.fillMaxSize().background(Tone.background)) { content() }
            }
        }
        return activity
    }

    private fun idle(ms: Long) {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        var left = ms
        while (left > 0) {
            val step = minOf(left, 20L)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            left -= step
        }
    }

    private fun shot(activity: ComponentActivity, name: String) {
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file = File("../build/device-test/review/$name").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }

    private val twins = listOf(
        TmIcons.Home, TmIcons.Chats, TmIcons.Settings, TmIcons.Search, TmIcons.StarOutline, TmIcons.Bookmark,
        TmIcons.Folder, TmIcons.Clock, TmIcons.Play, TmIcons.Person, TmIcons.Archive, TmIcons.Channel,
    )

    @Test
    @Config(sdk = [35], qualifiers = "w600dp-h300dp-land-night-xhdpi")
    fun twinsFillWithADrop() {
        var chosen by mutableStateOf(false)
        val activity = activity(tv = false) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (size in listOf(24, 30)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (icon in twins) SelectableIcon(icon, chosen, tint = if (chosen) Tone.accent else Tone.text, size = size.dp)
                    }
                }
            }
        }
        idle(500)
        shot(activity, "fill-android-outline.png")
        chosen = true
        idle(160)
        shot(activity, "fill-android-160ms.png")
        idle(1000)
        shot(activity, "fill-android-filled.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-notnight-xhdpi")
    fun phoneEmptyStates() {
        val activity = activity(tv = false) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { BigEmpty("Nothing watched yet. Videos you finish, or mark as watched, appear here.", icon = HistoryTab.Watched.emptyIcon) }
                Box(Modifier.weight(1f)) { BigEmpty("Nothing unread. You're up to date.", icon = ChatFilter.Unread.emptyIcon) }
                Box(Modifier.weight(1f)) { BigError("Telegram did not answer.", onRetry = {}) }
            }
        }
        idle(300)
        shot(activity, "empty-phone-300ms.png")
        idle(2500)
        shot(activity, "empty-phone.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun phoneEmptyStatesDark() {
        val activity = activity(tv = false) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { BigEmpty("You haven't started a video yet. Open a chat and pick one.", icon = HistoryTab.Continue.emptyIcon) }
                Box(Modifier.weight(1f)) { BigEmpty("Nothing in this folder yet. Folders are edited in Telegram, on your phone.", icon = TmIcons.FolderOpen) }
            }
        }
        idle(2500)
        shot(activity, "empty-phone-dark.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-night-mdpi")
    fun televisionEmptyState() {
        val activity = activity(tv = true) {
            BigEmpty("No playable videos in this chat.", icon = TmIcons.VideoOff)
        }
        idle(400)
        shot(activity, "empty-tv-400ms.png")
        idle(2500)
        shot(activity, "empty-tv.png")
    }
}
