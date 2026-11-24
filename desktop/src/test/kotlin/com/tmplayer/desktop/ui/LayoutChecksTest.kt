package com.tmplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [LayoutChecks] catch what they are for (an edge 10 dp off, an unequal gap, two sizes for one
 * role) and let a tidy layout through; then they are used on a screen: the Chats list, where a
 * pinned chat's name must start on the same edge as an unpinned one's (the pin once shifted it 20 px).
 */
@OptIn(ExperimentalTestApi::class)
class LayoutChecksTest {

    private fun ComposeUiTest.sample(offset: Int, gapB: Int, sizeB: Int) {
        setContent {
            TmMaterialTheme(dark = true) {
                Column(Modifier.fillMaxSize().background(Color.Black)) {
                    Box(Modifier.padding(start = 32.dp).size(100.dp, 20.dp).testTag("a")) { Text("A", fontSize = 14.sp, modifier = Modifier.testTag("ta")) }
                    Box(Modifier.padding(start = (32 + offset).dp).size(100.dp, 20.dp).testTag("b")) { Text("B", fontSize = sizeB.sp, modifier = Modifier.testTag("tb")) }
                    Row {
                        Box(Modifier.size(40.dp).testTag("r1"))
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(40.dp).testTag("r2"))
                        Spacer(Modifier.width(gapB.dp))
                        Box(Modifier.size(40.dp).testTag("r3"))
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun expectFailure(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: AssertionError) {
            return
        }
        fail("$what should have failed")
    }

    @Test
    fun `a tidy layout passes every check`() = runComposeUiTest {
        sample(offset = 0, gapB = 8, sizeB = 14)
        val check = LayoutChecks(this)
        check.sharedLeftEdge(listOf("a", "b"))
        check.equalGapsAcross(listOf("r1", "r2", "r3"))
        check.equalTextSizes(listOf("ta", "tb"))
        check.equalWidths(listOf("a", "b"))
        check.equalHeights(listOf("r1", "r2", "r3"))
    }

    @Test
    fun `an edge 10 dp off, an unequal gap and two text sizes are caught`() = runComposeUiTest {
        sample(offset = 10, gapB = 20, sizeB = 18)
        val check = LayoutChecks(this)
        expectFailure("edges") { check.sharedLeftEdge(listOf("a", "b")) }
        expectFailure("gaps") { check.equalGapsAcross(listOf("r1", "r2", "r3")) }
        expectFailure("sizes") { check.equalTextSizes(listOf("ta", "tb")) }
    }

    @Test
    fun `a pinned chat's name starts on the same edge as an unpinned one's`() = runComposeUiTest {
        val chats = listOf(
            ChatSummary(id = 1, title = "Pinned chat", miniThumbnail = null, photoFileId = 0, kind = ChatKind.Channel, isPinned = true),
            ChatSummary(id = 2, title = "Plain chat", miniThumbnail = null, photoFileId = 0, kind = ChatKind.Channel, isPinned = false),
        )
        setContent {
            TmMaterialTheme(dark = true) {
                Column(Modifier.fillMaxSize().background(Color.Black), horizontalAlignment = Alignment.Start) {
                    chats.forEach { chat ->
                        Box(Modifier.testTag("row-${chat.id}")) {
                            ChatRow(chat, favourite = false, onOpen = {}, onStar = {})
                        }
                    }
                }
            }
        }
        waitForIdle()
        val check = LayoutChecks(this)
        // The names themselves: the two Text nodes, found through their rows.
        val names = chats.map { chat ->
            onNodeWithTagCompat("row-${chat.id}").let { chat.title }
        }
        val lefts = names.map { name ->
            androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Text, listOf(androidx.compose.ui.text.AnnotatedString(name)))
                .let { matcher -> onNode(matcher, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left }
        }
        assertTrue("the names start at $lefts", lefts.max() - lefts.min() <= 1f)
        check.sharedLeftEdge(listOf("row-1", "row-2"))
    }

    private fun ComposeUiTest.onNodeWithTagCompat(tag: String) = onNode(androidx.compose.ui.test.hasTestTag(tag))
}
