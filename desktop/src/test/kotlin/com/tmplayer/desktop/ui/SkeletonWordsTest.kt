package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.components.MediaGridSkeleton
import com.tmplayer.ui.components.MediaGridSkeletonRow
import com.tmplayer.ui.components.PageFoot
import com.tmplayer.ui.components.UiState
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.tmplayer.ui.components.ChatListSkeleton as SharedChatListSkeleton

/**
 * Every wait has words (WP-92), on the desktop: a skeleton under [StateBox] draws no label for the
 * first second and one line from then on, says what loads to a screen reader as one indeterminate
 * progress bar, every skeleton takes a label of its own, and a failed next page is a live region.
 */
@OptIn(ExperimentalTestApi::class)
class SkeletonWordsTest {

    private val chats = "Loading your chats"

    private val indeterminate = SemanticsMatcher("is an indeterminate progress bar") {
        it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) == ProgressBarRangeInfo.Indeterminate
    }

    @Test
    fun `no label at half a second, one line from a second, and the skeleton is described`() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            TmMaterialTheme(dark = true) {
                StateBox<Unit>(UiState.Loading(chats), skeleton = { ChatListSkeleton() }) {}
            }
        }
        mainClock.advanceTimeBy(500)
        onNodeWithText(chats).assertDoesNotExist()
        // The skeleton says it to a screen reader from the first frame.
        onNode(hasContentDescription(chats) and indeterminate).assertExists()
        mainClock.advanceTimeBy(700)
        onAllNodesWithText(chats).assertCountEquals(1)
        onNode(hasContentDescription(chats) and indeterminate).assertExists()
    }

    @Test
    fun `the label is one polite live region and takes no room from the slow notice`() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            TmMaterialTheme(dark = true) {
                StateBox<Unit>(UiState.Loading(chats), onRetry = {}, skeleton = { ChatListSkeleton() }) {}
            }
        }
        mainClock.advanceTimeBy(1_100)
        onNode(hasText(chats) and SemanticsMatcher("is a polite live region") {
            it.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite
        }).assertExists()
        // The slow line is still waiting for its own 15 s, and the label is not repeated by it.
        onAllNodesWithText(chats).assertCountEquals(1)
        mainClock.advanceTimeBy(15_000)
        onNode(hasText(com.tmplayer.ui.components.SlowAnswer.MESSAGE)).assertExists()
        onAllNodesWithText(chats).assertCountEquals(1)
    }

    @Test
    fun `the label sits below the first skeleton row`() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            TmMaterialTheme(dark = true) {
                StateBox<Unit>(UiState.Loading(chats), skeleton = { ChatListSkeleton() }) {}
            }
        }
        mainClock.advanceTimeBy(1_100)
        val bounds = onNodeWithText(chats).getBoundsInRoot()
        // A row of the desktop list is about 68 dp, after 8 dp of padding: the label is a third down.
        assertTrue("label top ${bounds.top} is over the first row", bounds.top >= 76.dp)
    }

    @Test
    fun `every skeleton takes a label of its own`() = runComposeUiTest {
        setContent {
            TmMaterialTheme(dark = true) {
                Column {
                    Column(Modifier.height(120.dp)) { ChatListSkeleton(label = "chat list") }
                    Column(Modifier.height(120.dp)) { VideoGridSkeleton(160.dp, posters = false, label = "video grid") }
                    MoreChatsSkeleton(label = "more chats")
                    SubtitleSkeleton(label = "subtitle")
                    Column(Modifier.height(120.dp)) { SharedChatListSkeleton(label = "shared chat list") }
                    Column(Modifier.height(120.dp)) { MediaGridSkeleton(label = "shared grid") }
                    MediaGridSkeletonRow(columns = 3, gap = 8.dp, dense = false, label = "grid foot")
                }
            }
        }
        for (words in listOf("chat list", "video grid", "more chats", "subtitle", "shared chat list", "shared grid", "grid foot")) {
            onNodeWithContentDescription(words).assertExists()
        }
    }

    @Test
    fun `a skeleton with no label says Loading`() = runComposeUiTest {
        setContent { TmMaterialTheme(dark = true) { Column(Modifier.height(200.dp)) { ChatListSkeleton() } } }
        onNode(hasContentDescription(com.tmplayer.i18n.L.commonLoading) and indeterminate).assertExists()
    }

    @Test
    fun `a failed next page is spoken`() = runComposeUiTest {
        setContent {
            TmMaterialTheme(dark = true) {
                PageFoot(loading = false, failure = "Couldn't load more", onRetry = {}, skeleton = {})
            }
        }
        val spoken = onNodeWithText("Couldn't load more").fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, spoken.config.getOrNull(SemanticsProperties.LiveRegion))
    }
}
