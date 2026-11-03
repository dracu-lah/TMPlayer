package com.tmplayer.cast

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.theme.TMPlayerTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.Duration

/**
 * The Cast picker with a Google Cast TV and a DLNA one in the same list, and with no network to
 * find a TV on, drawn to `build/device-test/review/cast-picker-*.png` for a look. A phone upright
 * and sideways; a television has no casting.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CastPickerRenderTest {

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private class Fake(rows: List<CastDeviceInfo>, off: Boolean = false) : CastSupport {
        override val available = true
        val list = MutableStateFlow(rows)
        override val devices: StateFlow<List<CastDeviceInfo>> get() = list
        override val session: StateFlow<CastSessionState> = MutableStateFlow(CastSessionState.Idle)
        override val remote: StateFlow<RemoteStatus> = MutableStateFlow(RemoteStatus())
        override val offLan: StateFlow<Boolean> = MutableStateFlow(off)
        override fun startDiscovery() = Unit
        override fun stopDiscovery() = Unit
        override fun connect(id: String) = Unit
        override fun endSession() = Unit
        override fun load(request: CastLoadRequest, onResult: (Boolean) -> Unit) = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun seek(positionMs: Long) = Unit
        override fun stopMedia() = Unit
        override fun setVolume(level: Double) = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun setActiveTracks(ids: List<Long>) = Unit
        override fun setRate(rate: Double) = Unit
    }

    private fun idle(ms: Long = 1000) {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        var left = ms
        while (left > 0) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            left -= 20
        }
    }

    private fun shot(support: CastSupport, name: String) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            TMPlayerTheme {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF203040))) {
                    CastPickerSheet(support, onDismiss = {})
                }
            }
        }
        idle()
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        root.draw(canvas)
        val window = requireNotNull(ShadowDialog.getLatestDialog()?.window) { "no dialog up" }
        canvas.drawColor(0x99000000.toInt())
        window.decorView.draw(canvas)
        val file = File("../build/device-test/review/$name").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }

    private val rows = listOf(
        CastDeviceInfo("gc:r1", "Bedroom", "Chromecast"),
        CastDeviceInfo("dlna:udn:a", "Living room TV", "Samsung UE55", kind = CastKind.Dlna),
        CastDeviceInfo("dlna:udn:b", "Den", "", kind = CastKind.Dlna),
    )

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun phoneUpright() = shot(Fake(rows), "cast-picker-phone.png")

    @Test
    @Config(sdk = [35], qualifiers = "w891dp-h411dp-land-night-xhdpi")
    fun phoneSideways() = shot(Fake(rows), "cast-picker-phone-land.png")

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun noNetwork() = shot(Fake(emptyList(), off = true), "cast-picker-offline.png")

    @Test
    fun theDetailLineMarksDlnaAndKeepsACastModelPlain() {
        assertEquals("Chromecast", deviceDetail(rows[0], "DLNA"))
        assertEquals("Samsung UE55 · DLNA", deviceDetail(rows[1], "DLNA"))
        assertEquals("DLNA", deviceDetail(rows[2], "DLNA"))
        assertNull(deviceDetail(CastDeviceInfo("x", "TV", ""), "DLNA"))
    }
}
