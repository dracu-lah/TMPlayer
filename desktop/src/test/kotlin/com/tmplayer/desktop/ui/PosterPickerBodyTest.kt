package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tmplayer.data.MediaItem
import com.tmplayer.data.PosterPick
import com.tmplayer.data.VideoCorrection
import com.tmplayer.online.MetaInfo
import com.tmplayer.online.MetaKind
import com.tmplayer.online.MetaProvider
import com.tmplayer.online.PosterChoice
import com.tmplayer.ui.browse.PickerLoad
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Choose the right poster" on the desktop, by keys and clicks alone: the first title has the
 * keyboard, Down walks the list, Enter picks once, the title on show is marked, a failure offers
 * Try again with the keyboard on it, and the box for the other videos of the title sends them along.
 */
@OptIn(ExperimentalTestApi::class)
class PosterPickerBodyTest {

    private val choices = listOf(
        PosterChoice(1, false, "Idiot", 2022, "cs", "A Czech comedy.", null),
        PosterChoice(2, false, "Idiot", 2021, "ta", "A Tamil horror comedy.", null),
        PosterChoice(9, true, "Idiot", 2019, "en", "", null),
    )

    private fun video(id: Long) = MediaItem(
        chatId = 7, messageId = id, fileId = id.toInt(), title = "Idiot $id", sizeBytes = 1, durationSec = 60, mimeType = "video/mp4",
        thumbnailFileId = 0, miniThumbnail = null, date = 1, fileName = "Idiot.2022.$id.mkv", caption = "",
    )

    @Test
    fun `the first title has the keyboard, Down walks the list and Enter picks once`() = runComposeUiTest {
        val first = FocusRequester()
        val picked = mutableListOf<Pair<PosterPick, Int>>()
        setContent {
            TmMaterialTheme(dark = true) {
                Surface(Modifier.size(600.dp, 700.dp), color = Tone.background) {
                    PosterPickerBody(PickerLoad.Ready(choices), null, null, emptyList(), first, { p, others -> picked += p to others.size }, {})
                }
            }
        }
        runOnIdle { first.requestFocus() }
        onNodeWithTag("choice-1").assertIsFocused()
        onNodeWithTag("choice-1").performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithTag("choice-2").assertIsFocused()
        onNodeWithTag("choice-1").assertIsNotFocused()
        onNodeWithTag("choice-2").performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithTag("choice-9").assertIsFocused()
        onNodeWithTag("choice-9").performKeyInput { pressKey(Key.DirectionUp) }
        onNodeWithTag("choice-2").performKeyInput {
            pressKey(Key.Enter)
            // Double press: the same press again, not a second pick.
            pressKey(Key.Enter)
        }
        waitForIdle()
        assertEquals(listOf(PosterPick(2, show = false) to 0), picked)
    }

    @Test
    fun `a show is picked as a show`() = runComposeUiTest {
        val first = FocusRequester()
        val picked = mutableListOf<PosterPick>()
        setContent {
            TmMaterialTheme(dark = true) {
                PosterPickerBody(PickerLoad.Ready(choices), null, null, emptyList(), first, { p, _ -> picked += p }, {})
            }
        }
        onNodeWithTag("choice-9").performClick()
        assertEquals(listOf(PosterPick(9, show = true)), picked)
    }

    @Test
    fun `the title on show is marked, whether the tile matched it or the viewer picked it before`() = runComposeUiTest {
        val shown = MetaInfo(MetaProvider.Tmdb, "2", MetaKind.Film, "Idiot", 2021)
        setContent {
            TmMaterialTheme(dark = true) {
                PosterPickerBody(PickerLoad.Ready(choices), shown, null, emptyList(), FocusRequester(), { _, _ -> }, {})
            }
        }
        onNodeWithText("Showing now", substring = true).assertExists()
        // Exactly one title carries it.
        assertEquals(1, onAllNodesWithTextCount("Showing now"))
    }

    private fun SemanticsNodeInteractionsProvider.onAllNodesWithTextCount(text: String): Int =
        onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    @Test
    fun `a pick made before is marked even when TMDB did not match it now`() = runComposeUiTest {
        setContent {
            TmMaterialTheme(dark = true) {
                PosterPickerBody(PickerLoad.Ready(choices), null, VideoCorrection(pick = PosterPick(1)), emptyList(), FocusRequester(), { _, _ -> }, {})
            }
        }
        assertEquals(1, onAllNodesWithTextCount("Showing now"))
    }

    @Test
    fun `a failure says so and puts the keyboard on Try again`() = runComposeUiTest {
        val first = FocusRequester()
        var retried = 0
        setContent {
            TmMaterialTheme(dark = true) {
                PosterPickerBody(PickerLoad.Message("TMDB could not be reached.", canRetry = true), null, null, emptyList(), first, { _, _ -> }, { retried++ })
            }
        }
        onNodeWithText("TMDB could not be reached.").assertExists()
        runOnIdle { first.requestFocus() }
        onNodeWithText("Try again").assertIsFocused()
        onNodeWithText("Try again").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(1, retried)
    }

    @Test
    fun `a message with no way to try again offers none, and nothing found says so`() = runComposeUiTest {
        var empty by mutableStateOf<PickerLoad>(PickerLoad.Message("Posters are off.", canRetry = false))
        setContent {
            TmMaterialTheme(dark = true) { PosterPickerBody(empty, null, null, emptyList(), FocusRequester(), { _, _ -> }, {}) }
        }
        onNodeWithText("Try again").assertDoesNotExist()
        empty = PickerLoad.Ready(emptyList())
        waitForIdle()
        onNodeWithText("TMDB has no title like this one.").assertExists()
    }

    @Test
    fun `the others of the title are sent along only when the box is ticked`() = runComposeUiTest {
        val others = listOf(video(21), video(22))
        val picked = mutableListOf<Int>()
        setContent {
            TmMaterialTheme(dark = true) {
                PosterPickerBody(PickerLoad.Ready(choices), null, null, others, FocusRequester(), { _, with -> picked += with.size }, {})
            }
        }
        onNodeWithText("Also use it for 2 other videos", substring = true).assertExists()
        onNodeWithTag("poster-others").performClick()
        onNodeWithTag("choice-1").performClick()
        assertEquals(listOf(2), picked)
    }
}
