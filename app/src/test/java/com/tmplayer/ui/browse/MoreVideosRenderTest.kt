package com.tmplayer.ui.browse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormFactor
import com.tmplayer.data.MediaItem
import com.tmplayer.data.PinnedPost
import com.tmplayer.data.TgLink
import com.tmplayer.ui.DrainMainLooperRule
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
 * The entries CP81 adds over a chat's videos on Android, a phone's and a television's: the pinned
 * post (its own video, and a link to one), the round videos, and the round videos alone in a chat
 * with nothing else to play. PNGs under `build/device-test/review/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MoreVideosRenderTest {

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private val video = MediaItem(
        chatId = 1, messageId = 9, fileId = 3, title = "Welcome, start here", sizeBytes = 48_000_000, durationSec = 240,
        mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 0,
    )

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

    @Composable
    private fun entries() {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PinnedEntry(PinnedPost(9, video, null, "Welcome, start here"), onPlay = {})
            PinnedEntry(PinnedPost(10, null, TgLink.Post("nature_clips", null, 4), "Start here"), onPlay = {})
            RoundEntry(6, noVideos = false, onOpen = {})
            RoundEntry(3, noVideos = true, onOpen = {})
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-notnight-xhdpi")
    fun phoneEntries() {
        val activity = activity(tv = false) { entries() }
        idle(1200)
        shot(activity, "more-videos-phone.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-night-mdpi")
    fun tvEntries() {
        val activity = activity(tv = true) { entries() }
        idle(1200)
        shot(activity, "more-videos-tv.png")
    }
}
