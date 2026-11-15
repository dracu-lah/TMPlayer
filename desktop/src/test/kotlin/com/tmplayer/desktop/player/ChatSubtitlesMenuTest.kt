package com.tmplayer.desktop.player

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.tmplayer.i18n.L
import com.tmplayer.player.ChatSubtitles
import com.tmplayer.ui.i18n.ProvideStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "In this chat" in the desktop's Subtitles menu (EG-F48): the entry only when files were found, the
 * page of them best first, and a click handing exactly that file to the player.
 */
@OptIn(ExperimentalTestApi::class)
class ChatSubtitlesMenuTest {

    private fun hit(step: Long, name: String, language: String? = null) = ChatSubtitles.Hit(
        doc = ChatSubtitles.Doc(step shl 20, step.toInt(), name, 40_000, 1_000),
        relation = ChatSubtitles.Relation.Neighbour,
        nameMatch = ChatSubtitles.NameMatch.Exact,
        language = language,
        forced = false,
        hearing = false,
        archive = false,
    )

    private fun menu(page: MenuPage, hits: List<ChatSubtitles.Hit>, opened: (MenuAt) -> Unit, acted: (MenuAction) -> Unit): @androidx.compose.runtime.Composable () -> Unit = {
        ProvideStrings {
            PlayerTheme {
                CompositionLocalProvider(LocalChatSubtitles provides hits) {
                    PlayerMenu(
                        menu = MenuAt(page, MenuAt.Anchor.Subtitles),
                        status = PlaybackStatus(opened = true, durationMs = 2_634_000, positionMs = 1_260_000, playing = true),
                        tracks = emptyList(),
                        fullscreen = false,
                        ignoreClicks = false,
                        miniPlayerAvailable = false,
                        alwaysOnTopAvailable = false,
                        fromTelegram = true,
                        savable = true,
                        watchedLabel = null,
                        sleepTimer = null,
                        onOpenMenu = opened,
                        onClose = {},
                        onAction = acted,
                    )
                }
            }
        }
    }

    @Test
    fun theEntryIsThereOnlyWhenFilesWereFoundAndOpensTheirPage() = runComposeUiTest {
        var page: MenuAt? = null
        setContent(menu(MenuPage.Subtitles, listOf(hit(1, "Show.S01E02.en.srt", "en")), { page = it }, {}))
        onNodeWithText(L.tracksInChat).performClick()
        assertEquals(MenuPage.ChatSubtitles, page?.page)
    }

    @Test
    fun noFilesNoEntry() = runComposeUiTest {
        setContent(menu(MenuPage.Subtitles, emptyList(), {}, {}))
        onNodeWithText(L.tracksInChat).assertDoesNotExist()
        onNodeWithText(L.commonOff).assertExists()
    }

    @Test
    fun theFilesAreListedBestFirstAndAClickChoosesThatOne() = runComposeUiTest {
        var chosen: MenuAction? = null
        setContent(menu(MenuPage.ChatSubtitles, listOf(hit(1, "Show.S01E02.en.srt", "en"), hit(2, "Show.S01E02.fr.srt", "fr")), {}, { chosen = it }))
        onNodeWithText("Show.S01E02.en.srt").assertExists()
        assertNull(chosen)
        onNodeWithText("Show.S01E02.fr.srt").performClick()
        assertEquals("Show.S01E02.fr.srt", (chosen as MenuAction.ChatSubtitle).hit.doc.fileName)
    }
}
