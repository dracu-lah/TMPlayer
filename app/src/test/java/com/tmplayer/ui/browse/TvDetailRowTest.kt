package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.tmplayer.data.DetailAction
import com.tmplayer.data.FormFactor
import com.tmplayer.data.MediaItem
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * WP-14: every hold-only video action has a visible button on the television's details page, one OK
 * away. The page already draws Play and every other action (Mark as watched, Add to list, Download,
 * Share, ...) as pills in one row, so the check is that the remote walks that row with Right and
 * reaches each one by name, and that OK on one runs its own action.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class TvDetailRowTest {

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

    private val clip = MediaItem(
        chatId = 2, messageId = 500, fileId = 0, title = "Clip.2020.mkv", sizeBytes = 100L * 1024 * 1024,
        durationSec = 120, mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null, date = 1_790_000_000,
        fileName = "Clip.2020.mkv", width = 1920, height = 1080,
    )

    private val ran = mutableListOf<DetailAction>()

    private fun row(action: DetailAction, label: String) =
        DetailRow(action, label, com.tmplayer.ui.icons.TmIcons.Check, onSelect = { ran += action })

    private fun named(label: String) = hasClickAction() and (hasText(label) or hasContentDescription(label))

    private fun focusedAny(): String = rule.onAllNodes(androidx.compose.ui.test.isFocusable()).fetchSemanticsNodes()
        .filter { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Focused) == true }
        .joinToString { "${it.boundsInRoot} ${it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)}" }

    private fun focusedLabel(): String? = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        .firstOrNull { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Focused) == true }
        ?.config?.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString { it.text }

    private fun press(key: Key) {
        rule.onAllNodes(isRoot())[0].performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test
    fun `Right walks from Play along Mark as watched, Add to a list and Download, and OK runs each`() {
        FormFactor.override(true)
        Motion.reduced = true
        val rows = listOf(
            row(DetailAction.Play, "Play"),
            row(DetailAction.MarkWatched, "Watched"),
            row(DetailAction.AddToList, "List"),
            row(DetailAction.Download, "Download"),
        )
        rule.setContent {
            TMPlayerTheme {
                CompositionLocalProvider(LocalSeriesWatch provides SeriesWatch.None) {
                    Box(Modifier.fillMaxSize().background(Tone.background)) {
                        MediaDetailScreen(clip, "Clips", null, false, rows, onClose = {})
                    }
                }
            }
        }
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
        // The page holds its first frame up to 200 ms for the caches (rememberFirstPaint): past it the actions are there.
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()

        rule.onNode(named("Play")).requestFocus()
        rule.onNode(named("Play")).assertIsFocused()
        // Play and the other actions are one wrapping row of pills: Right moves along it and, where the
        // column is narrow and it wraps, Down reaches the next line. Walk it, press OK on each name.
        val wanted = mutableMapOf("Watched" to DetailAction.MarkWatched, "List" to DetailAction.AddToList, "Download" to DetailAction.Download)
        var presses = 0
        while (wanted.isNotEmpty() && presses < 6) {
            val before = focusedLabel()
            press(Key.DirectionRight)
            presses++
            if (focusedLabel() == before) {
                press(Key.DirectionDown)
                presses++
            }
            val now = focusedLabel()
            val action = wanted.remove(now) ?: continue
            press(Key.DirectionCenter)
            assertEquals("OK on $now ran its action", action, ran.last())
        }
        assertEquals("every action was reached within $presses presses: ${focusedAny()}", emptyMap<String, DetailAction>(), wanted)
        assertEquals(3, ran.size)
    }
}
