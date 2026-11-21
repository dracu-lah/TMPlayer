package com.tmplayer

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.HomeLayout
import com.tmplayer.data.HomeRow
import com.tmplayer.data.MediaItem
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.browse.HomeSource
import com.tmplayer.ui.browse.HomeViewModel
import com.tmplayer.ui.browse.rememberHomeRows
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * HX81 (Sentry 161627449): the shell ([Root] in MainActivity) recomposed on the main thread of a
 * TV box until the input wait ran out. A busy account changes the chat list on every post, and
 * the list and Home's rows were read in the shell's own body, so each change ran the whole shell
 * again. [ShellHomeRows] is the scope they are read in now: a new list recomposes it, never the
 * composable that hosts it. The second test is the control: read inline, the host recomposes.
 */
@RunWith(RobolectricTestRunner::class)
class ShellHomeRowsTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private val source = object : HomeSource {
        override suspend fun chatMedia(chatId: Long): List<MediaItem> = emptyList()
        override suspend fun recentMedia(): List<MediaItem> = emptyList()
        override suspend fun mediaItem(chatId: Long, messageId: Long): MediaItem? = null
    }

    private fun model() = HomeViewModel(source, connected = MutableStateFlow(true))

    /** Three thousand chats, as a large account has, with [unread] on the first: one post's change. */
    private fun chats(unread: Int): List<ChatSummary> = List(3_000) { i ->
        ChatSummary(id = i + 1L, title = "Chat $i", miniThumbnail = null, photoFileId = 0, kind = ChatKind.Channel, unreadCount = if (i == 0) unread else 0)
    }

    @Test
    fun `a new chat list recomposes Home's rows and not the shell`() {
        val model = model()
        val list = mutableStateOf(chats(0), referentialEqualityPolicy())
        val into = mutableStateOf(emptyList<HomeRow>(), referentialEqualityPolicy())
        var shell = 0
        rule.setContent {
            shell++
            ShellHomeRows(
                model = model, chats = list, favourites = setOf(1L), continueWatching = emptyList(), watchLater = emptyList(),
                watched = emptyList(), layout = HomeLayout(), lists = emptyList(), watchProgress = emptyMap(),
                signedIn = false, channelsOn = null, into = into,
            )
        }
        rule.waitForIdle()
        val settled = shell
        repeat(20) { n ->
            rule.runOnIdle { list.value = chats(n + 1) }
            rule.waitForIdle()
        }
        assertEquals("the shell ran again for a new chat list", settled, shell)
    }

    @Test
    fun `control - read in the host's body, every new chat list recomposes the host`() {
        val model = model()
        val list = mutableStateOf(chats(0), referentialEqualityPolicy())
        var host = 0
        rule.setContent {
            host++
            rememberHomeRows(model, list.value, setOf(1L), emptyList(), layout = HomeLayout())
        }
        rule.waitForIdle()
        val settled = host
        repeat(5) { n ->
            rule.runOnIdle { list.value = chats(n + 1) }
            rule.waitForIdle()
        }
        assertTrue("the control should recompose its host, else the test above proves nothing", host >= settled + 5)
    }
}
