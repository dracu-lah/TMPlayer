package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tmplayer.i18n.L
import com.tmplayer.ui.i18n.ProvideStrings
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import org.junit.Assert.assertEquals
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
