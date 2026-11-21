package com.tmplayer.cast

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormFactor
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Cast picker by keys alone (FQ8): a phone or tablet driven with a D-pad or a keyboard's arrows,
 * and the television's panel the same picker draws there. From the first TV, Down walks the merged
 * list (a Google Cast TV, then a DLNA one) to Close; OK on the DLNA TV connects to exactly that one,
 * and once it is up the picker closes and the keys are back on the button it was opened from.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CastPickerFocusTest {

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

    private class Fake : CastSupport {
        override val available = true
        val connects = ArrayList<String>()
        override val devices: StateFlow<List<CastDeviceInfo>> = MutableStateFlow(
            MergedCastSupport.mergeDevices(
                listOf(CastDeviceInfo("r1", "Bedroom", "Chromecast")),
                listOf(CastDeviceInfo("udn:a", "Living room TV", "Samsung", kind = CastKind.Dlna)),
            ),
        )
        val state = MutableStateFlow<CastSessionState>(CastSessionState.Idle)
        override val session: StateFlow<CastSessionState> get() = state
        override val remote: StateFlow<RemoteStatus> = MutableStateFlow(RemoteStatus())
        override fun startDiscovery() = Unit
        override fun stopDiscovery() = Unit
        override fun connect(id: String) {
            connects += id
            state.value = CastSessionState.Connected("Living room TV", "Samsung", null, CastKind.Dlna)
        }
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

    private val support = Fake()

    private fun open(tv: Boolean) {
        FormFactor.override(tv)
        Motion.reduced = true
        rule.setContent {
            TMPlayerTheme {
                var picking by remember { mutableStateOf(false) }
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    Box(Modifier.padding(24.dp).clickable { picking = true }) { Text(OPENER) }
                }
                if (picking) CastPickerSheet(support, onDismiss = { picking = false })
            }
        }
        remoteMode()
    }

    /** Every window out of touch mode, the newest one holding focus, as under a remote. */
    private fun remoteMode() {
        val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        val roots = org.robolectric.util.ReflectionHelpers.getField<List<Any>>(global, "mRoots").toList()
        for (viewRoot in roots) {
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
                viewRoot, "ensureTouchMode",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
            )
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
                viewRoot, "windowFocusChanged",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, roots.last() === viewRoot),
            )
        }
        rule.waitForIdle()
    }

    private fun focused(label: String): Boolean =
        rule.onAllNodes(isFocused() and hasText(label, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun press(key: Key) {
        rule.onAllNodes(isFocused() and hasClickAction() and !hasText(OPENER)).onFirst().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun walk(key: Key, label: String, limit: Int = 12) {
        repeat(limit) {
            if (focused(label)) return
            press(key)
        }
        throw AssertionError("$label never took the keys")
    }

    private fun byKeys() {
        rule.onNodeWithText(OPENER).requestFocus()
        rule.onNodeWithText(OPENER).performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        remoteMode()
        rule.onNodeWithText("Bedroom").requestFocus()
        rule.onNodeWithText("Bedroom").assertIsFocused()
        walk(Key.DirectionDown, "Living room TV")
        walk(Key.DirectionDown, L.commonClose)
        walk(Key.DirectionUp, "Living room TV")
        press(Key.DirectionCenter)
        assertEquals(listOf("dlna:udn:a"), support.connects)
        assertTrue("the picker closed once the TV was up", rule.onAllNodes(hasText("Bedroom")).fetchSemanticsNodes().isEmpty())
        remoteMode()
        rule.onNodeWithText(OPENER).assertIsFocused()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `a phone with a D-pad walks the merged list and connects`() {
        open(tv = false)
        byKeys()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `the remote walks the television panel and connects`() {
        open(tv = true)
        byKeys()
    }

    private companion object {
        const val OPENER = "Open the picker"
    }
}
