package com.tmplayer.ui.browse

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * A remote's press along a row of tiles on a television moves only pictures: the ring, the grow
 * and the caption's colour are drawn from the focus where they are painted, so the tile left and
 * the tile reached compose nothing again. On a 1 GB stick each tile composed again cost a frame
 * of its own, which is what made every press along Home's rows two to five frames long.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-night-xhdpi")
class TileFocusRecomposeTest {

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
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

    @Test
    fun focusMoveComposesNoCaption() {
        FormFactor.override(true)
        val first = FocusRequester()
        val second = FocusRequester()
        val captions = IntArray(2)
        val arts = IntArray(2)
        val focusedAt = IntArray(2)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            TMPlayerTheme {
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    Row {
                        for (i in 0..1) {
                            VideoTile(
                                title = "Tile $i",
                                onClick = {},
                                onFocused = { focusedAt[i]++ },
                                modifier = Modifier.width(240.dp).focusRequester(if (i == 0) first else second),
                                meta = { ink ->
                                    SideEffect { captions[i]++ }
                                    TileCaption("Caption $i", androidx.tv.material3.MaterialTheme.typography.labelMedium, ink)
                                },
                            ) {
                                SideEffect { arts[i]++ }
                            }
                        }
                    }
                }
            }
        }
        idle(300)
        first.requestFocus()
        idle(300)
        assertEquals("the first tile took focus", 1, focusedAt[0])
        val captionsBefore = captions.toList()
        val artsBefore = arts.toList()

        second.requestFocus()
        idle(300)
        assertEquals("the second tile took focus", 1, focusedAt[1])
        first.requestFocus()
        idle(300)
        assertEquals("the first tile took focus again", 2, focusedAt[0])

        assertEquals("no caption composed again by a focus move", captionsBefore, captions.toList())
        assertEquals("no art composed again by a focus move", artsBefore, arts.toList())
    }
}
