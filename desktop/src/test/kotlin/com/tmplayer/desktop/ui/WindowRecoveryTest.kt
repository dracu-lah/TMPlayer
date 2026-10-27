package com.tmplayer.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import com.tmplayer.i18n.L
import com.tmplayer.ui.i18n.ProvideStrings
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window's last stop for a screen that throws (HX10): the failure is reported once, the window
 * is built again with the error card, Try again and Settings bring the shell back, and nothing can
 * loop. Compose's own handler (a dialog, then the app quits) is only reached when the card's window
 * fails too, after too many failures in a minute, or when memory runs out.
 */
class WindowRecoveryTest {

    private val reported = mutableListOf<Throwable>()
    private val gaveUp = mutableListOf<Throwable>()
    private var clock = 0L

    private fun recovery() = WindowRecovery(
        now = { clock },
        later = { it() },
        report = { reported += it },
        giveUp = { _, error -> gaveUp += error },
    )

    @Test
    fun aFailureIsReportedOnceAndTheWindowIsBuiltAgainWithTheCard() {
        val recovery = recovery()
        val error = IllegalStateException("Home threw while measuring")
        recovery.contain(0, null, error)
        assertSame(error, recovery.failure)
        assertEquals(1, recovery.generation)
        assertEquals(listOf<Throwable>(error), reported)
        // The dead window's later frames fail too ("layout state is not idle"): the same failure.
        recovery.contain(0, null, IllegalStateException("layout state is not idle before measure starts"))
        assertEquals(1, reported.size)
        assertEquals(1, recovery.generation)
        assertTrue(gaveUp.isEmpty())
    }

    @Test
    fun tryAgainPutsTheShellBackInTheSameWindow() {
        val recovery = recovery()
        recovery.contain(0, null, RuntimeException("x"))
        recovery.tryAgain()
        assertNull(recovery.failure)
        assertEquals(1, recovery.generation)
        // A later failure in that window is contained again.
        clock += 10_000
        recovery.contain(1, null, RuntimeException("y"))
        assertEquals(2, recovery.generation)
        assertTrue(gaveUp.isEmpty())
    }

    @Test
    fun aFailureInTheCardsOwnWindowGivesUpRatherThanLooping() {
        val recovery = recovery()
        recovery.contain(0, null, RuntimeException("first"))
        val second = RuntimeException("the card failed")
        recovery.contain(1, null, second)
        assertEquals(listOf<Throwable>(second), gaveUp)
        assertEquals(1, recovery.generation)
    }

    @Test
    fun tooManyFailuresInAMinuteGiveUp() {
        val recovery = recovery()
        repeat(WindowRecovery.MAX_FAILURES) { round ->
            recovery.contain(recovery.generation, null, RuntimeException("round $round"))
            recovery.tryAgain()
            clock += 1_000
        }
        assertTrue(gaveUp.isEmpty())
        recovery.contain(recovery.generation, null, RuntimeException("once more"))
        assertEquals(1, gaveUp.size)
        // Spread out over more than a minute, the same number is fine.
        val slow = recovery()
        gaveUp.clear()
        repeat(WindowRecovery.MAX_FAILURES + 3) {
            slow.contain(slow.generation, null, RuntimeException("slow"))
            slow.tryAgain()
            clock += WindowRecovery.WINDOW_MS
        }
        assertTrue(gaveUp.isEmpty())
    }

    @Test
    fun runningOutOfMemoryIsNotContained() {
        val recovery = recovery()
        recovery.contain(0, null, OutOfMemoryError("heap"))
        assertEquals(1, gaveUp.size)
        assertNull(recovery.failure)
    }

    /** The error as a macOS log of 1.28.2-beta.2 shows it, thrown on a mouse release. */
    private fun closedLayerRace() = IllegalArgumentException("RootNodeOwner is already disposed").apply {
        stackTrace = arrayOf(
            StackTraceElement("androidx.compose.ui.node.RootNodeOwner", "measureAndLayout", "RootNodeOwner.skiko.kt", 245),
            StackTraceElement("androidx.compose.ui.scene.CanvasLayersComposeSceneImpl", "doMeasureAndLayout", "CanvasLayersComposeScene.skiko.kt", 299),
            StackTraceElement("androidx.compose.ui.scene.BaseComposeScene\$inputHandler\$1", "invoke", "BaseComposeScene.skiko.kt", 58),
            StackTraceElement("androidx.compose.ui.scene.ComposeSceneMediator\$mouseListener\$1", "mouseReleased", "ComposeSceneMediator.desktop.kt", 314),
        )
    }

    @Test
    fun aPopupClosedWhileLaidOutIsLetPassWithoutTheCard() {
        val recovery = recovery()
        recovery.contain(0, null, closedLayerRace())
        assertNull(recovery.failure)
        assertEquals(0, recovery.generation)
        assertTrue(reported.isEmpty())
        assertTrue(gaveUp.isEmpty())
        // The card's own window too: it is not a second failure there.
        recovery.contain(0, null, RuntimeException("a real one"))
        recovery.contain(1, null, closedLayerRace())
        assertTrue(gaveUp.isEmpty())
        assertEquals(1, recovery.generation)
    }

    @Test
    fun theRaceOverAndOverShowsTheCardInstead() {
        val recovery = recovery()
        repeat(WindowRecovery.MAX_PASSING) { recovery.contain(0, null, closedLayerRace()) }
        assertNull(recovery.failure)
        val last = closedLayerRace()
        recovery.contain(0, null, last)
        assertSame(last, recovery.failure)
        assertEquals(listOf<Throwable>(last), reported)
        // A minute later the count starts again.
        recovery.tryAgain()
        clock += WindowRecovery.WINDOW_MS + 1
        recovery.contain(1, null, closedLayerRace())
        assertNull(recovery.failure)
    }

    @Test
    fun onlyThatRaceIsLetPass() {
        assertTrue(WindowRecovery.isClosedLayerRace(closedLayerRace()))
        // The same message from somewhere else, or another message, is a failure.
        val elsewhere = IllegalArgumentException("RootNodeOwner is already disposed").apply {
            stackTrace = arrayOf(StackTraceElement("com.tmplayer.desktop.ui.Home", "draw", "Home.kt", 1))
        }
        assertFalse(WindowRecovery.isClosedLayerRace(elsewhere))
        val other = IllegalArgumentException("index out of range").apply { stackTrace = closedLayerRace().stackTrace }
        assertFalse(WindowRecovery.isClosedLayerRace(other))
        assertFalse(WindowRecovery.isClosedLayerRace(IllegalStateException("RootNodeOwner is already disposed")))
        val recovery = recovery()
        recovery.contain(0, null, elsewhere)
        assertSame(elsewhere, recovery.failure)
    }

    /**
     * The race itself, in the window's kind of scene (one canvas, a layer per popup), on the path
     * the log shows: a mouse release lays the scene out first. Content composed while it is
     * measured (BoxWithConstraints, as lazy lists and grids do with their items) drops an open
     * popup when its room changes, so the popup's layer is closed in the middle of the scene's pass
     * over its layers. Compose 1.12.2 throws for it; a later version may not, which is fine.
     * Whatever is thrown is what [WindowRecovery] lets pass, and the scene is well after it: the
     * popup is gone, the content is drawn and the next click lands.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun aPopupClosedWhileTheSceneMeasuresIsTheRaceAndTheSceneCarriesOn() {
        var width by mutableStateOf(600)
        var tipOpen = false
        var rowDrawn = 0
        var clicks = 0
        val scene = ImageComposeScene(800, 400, density = Density(1f)) {
            Box(
                Modifier
                    // Read while measuring, so a change is laid out without a recomposition.
                    .layout { measurable, _ ->
                        val placeable = measurable.measure(Constraints.fixed(width, 300))
                        layout(width, 300) { placeable.place(0, 0) }
                    }
                    .clickable { clicks++ },
            ) {
                BoxWithConstraints {
                    Text("row", Modifier.drawBehind { rowDrawn++ })
                    if (maxWidth > 400.dp) {
                        Popup {
                            DisposableEffect(Unit) {
                                tipOpen = true
                                onDispose { tipOpen = false }
                            }
                            Text("tip")
                        }
                    }
                }
            }
        }
        try {
            scene.render()
            assertTrue(tipOpen)
            width = 300
            Snapshot.sendApplyNotifications()
            val thrown = runCatching { scene.sendPointerEvent(PointerEventType.Release, Offset(10f, 10f)) }.exceptionOrNull()
            assertFalse(tipOpen)
            if (thrown != null) {
                assertTrue("not the race: $thrown", WindowRecovery.isClosedLayerRace(thrown))
                val recovery = recovery()
                recovery.contain(0, null, thrown)
                assertNull(recovery.failure)
                assertTrue(reported.isEmpty())
            }
            val drawn = rowDrawn
            scene.sendPointerEvent(PointerEventType.Press, Offset(10f, 10f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(10f, 10f))
            scene.render()
            assertEquals(1, clicks)
            assertTrue(rowDrawn > drawn)
        } finally {
            scene.close()
        }
    }

    /**
     * An exception thrown by a click handler and by an effect: what the window's handler is handed
     * in the app is handed to the recovery here (a test scene has no window to do it), and the card
     * replaces the shell; Try again and Settings each bring it back.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun anExceptionInAHandlerOrAnEffectShowsTheCard() = runComposeUiTest {
        val recovery = recovery()
        var settingsOpened = false
        var effectThrows by mutableStateOf(false)
        setContent {
            TmMaterialTheme(dark = true) {
                ProvideStrings {
                    Surface(Modifier.size(900.dp, 700.dp), color = Tone.background) {
                        ShellOrFailure(recovery, onTryAgain = {}, onSettings = { settingsOpened = true }) {
                            Button(onClick = { throw IllegalStateException("a chat page's click") }) { Text("Break") }
                            LaunchedEffect(effectThrows) {
                                if (effectThrows) {
                                    try {
                                        error("an effect's TDLib call")
                                    } catch (e: IllegalStateException) {
                                        recovery.contain(recovery.generation, null, e)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        try {
            onNodeWithText("Break").performClick()
        } catch (e: IllegalStateException) {
            recovery.contain(recovery.generation, null, e)
        }
        waitForIdle()
        onNodeWithText(L.errorsScreenFailedTitle).assertExists()
        onNodeWithText(L.logsSave).assertExists()
        // By its semantics action: the test scene's pointer is left mid click by the throw above.
        onNodeWithText(L.commonTryAgain).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        onNodeWithText("Break").assertExists()

        clock += 1_000
        effectThrows = true
        waitForIdle()
        onNodeWithText(L.errorsScreenFailedTitle).assertExists()
        // Settings is another screen: the effect that threw is not drawn there.
        effectThrows = false
        onNodeWithText(L.navSettings).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        assertTrue(settingsOpened)
        assertEquals(2, reported.size)
        assertTrue(gaveUp.isEmpty())
    }
}
