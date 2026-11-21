package com.tmplayer.ui.browse

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.FormFactor
import com.tmplayer.data.GlobalSearch
import com.tmplayer.data.MediaItem
import com.tmplayer.data.ResultGroup
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws the global search page in each of its states to `design/search-review/` for the review
 * page there (index.html). Not a pass or fail check: it only has to compose and write the files.
 */
private object Review {
    val chats = listOf(
        ChatSummary(1, "Harbour Lights fans", null, 0, ChatKind.Group),
        ChatSummary(2, "Weekend Club", null, 0, ChatKind.Channel),
        ChatSummary(3, "Harbour photos", null, 0, ChatKind.Channel),
    )

    fun video(id: Long, name: String, chat: Long = 1) = MediaItem(
        chatId = chat, messageId = id, fileId = id.toInt(), title = name, sizeBytes = 900_000_000, durationSec = 5_400,
        mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = (1000 - id).toInt(), fileName = name,
    )

    val groups = listOf(
        ResultGroup("a", listOf(video(1, "Harbour Lights S02E03 1080p.mkv"), video(11, "Harbour Lights S02E03 1080p.mkv", 3))),
        ResultGroup("b", listOf(video(2, "Harbour Lights S02E04 720p.mkv"))),
        ResultGroup("c", listOf(video(3, "Night Harbour 2021 1080p.mkv", 2))),
        ResultGroup("d", listOf(video(4, "Harbour at Dawn 2019 2160p.mkv", 2))),
    )

    fun save(name: String, bitmap: Bitmap) {
        val file = File("../design/search-review/$name").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

@OptIn(ExperimentalTestApi::class)
abstract class PhoneShots {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        Motion.reduced = false
    }

    protected fun shot(
        name: String,
        state: GlobalSearchState,
        text: String,
        scale: Float = 1f,
        recents: List<String> = listOf("harbour lights", "night harbour", "weekend club"),
    ) {
        Motion.reduced = true
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = scale)) {
                TMPlayerTheme {
                    var field by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
                    PhoneSearchScreen(
                        state = state.copy(query = GlobalSearch.parse(field.text)),
                        text = field,
                        onText = { field = it },
                        onSubmit = {},
                        onClose = {},
                        fieldModifier = Modifier,
                        chats = Review.chats,
                        favourites = setOf(2L),
                        recentSearches = recents,
                        continueWatching = emptyList(),
                        continueArt = { it.toMediaItem() },
                        progress = { null },
                        onOpenChat = {},
                        onOpenGroup = {},
                        onResume = {},
                        onFilters = {},
                        onSort = {},
                        onLoadMore = {},
                        onRetry = {},
                        onRemoveRecent = {},
                        onClearRecent = { emptyList() },
                        onRestoreRecent = {},
                        onVoice = {},
                        listState = rememberLazyListState(),
                    )
                }
            }
        }
        rule.waitForIdle()
        repeat(5) { rule.mainClock.advanceTimeBy(100) }
        Review.save(name, rule.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap())
    }
}

private fun results(q: String, groups: List<ResultGroup> = Review.groups, extra: GlobalSearchState.() -> GlobalSearchState = { this }) =
    GlobalSearchState(
        query = GlobalSearch.parse(q),
        groups = groups,
        chats = Review.chats.associate { it.id to com.tmplayer.data.ChatBrief.of(it) },
        endReached = true,
    ).extra()

/** Phone portrait, every state. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xxhdpi")
class SearchReviewPhonePortraitShots : PhoneShots() {
    @Test fun empty() = shot("phone-1-empty-recents.png", GlobalSearchState(), "")
    @Test fun typing() = shot("phone-2-typing.png", GlobalSearchState(settling = true), "harbour")
    @Test fun withResults() = shot("phone-3-results.png", results("harbour"), "harbour")
    @Test fun none() = shot("phone-4-no-results.png", results("zzzz qqqq", emptyList()), "zzzz qqqq")
    @Test fun searching() = shot("phone-5-searching.png", GlobalSearchState(searching = true), "harbour")
    @Test fun offline() = shot("phone-6-offline.png", results("harbour") { copy(offline = true) }, "harbour")
    @Test fun error() = shot("phone-7-error.png", GlobalSearchState(error = "Telegram did not answer. Check the connection and try again."), "harbour")
    @Test fun otherChats() = shot("phone-8-other-chats.png", results("harbour") { copy(addedOnly = true) }, "harbour")
}

/** Phone landscape. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w891dp-h411dp-land-night-xxhdpi")
class SearchReviewPhoneLandscapeShots : PhoneShots() {
    @Test fun empty() = shot("phone-land-1-empty.png", GlobalSearchState(), "")
    @Test fun withResults() = shot("phone-land-2-results.png", results("harbour"), "harbour")
    @Test fun error() = shot("phone-land-3-error.png", GlobalSearchState(error = "Telegram did not answer. Check the connection and try again."), "harbour")
}

/** Phone portrait at font scale 1.3. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xxhdpi")
class SearchReviewPhoneLargeFontShots : PhoneShots() {
    @Test fun empty() = shot("phone-font-1-empty.png", GlobalSearchState(), "", scale = 1.3f)
    @Test fun withResults() = shot("phone-font-2-results.png", results("harbour"), "harbour", scale = 1.3f)
    @Test fun otherChats() = shot("phone-font-3-other-chats.png", results("harbour") { copy(addedOnly = true) }, "harbour", scale = 1.3f)
}

/** The television's search page, with the remote on the clear control. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class SearchReviewTvShots {
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

    private fun tv(name: String, state: GlobalSearchState, text: String, focusClear: Boolean = false, scale: Float = 1f) {
        FormFactor.override(true)
        Motion.reduced = true
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = scale)) {
                TMPlayerTheme {
                    var words by remember { mutableStateOf(text) }
                    Column(Modifier.fillMaxSize().background(Tone.background).padding(top = TV_BAR_HEIGHT, end = Tv.SafeH)) {
                        TvSearchScreen(
                            state = state.copy(query = GlobalSearch.parse(words)),
                            text = words,
                            onText = { words = it },
                            onSubmit = {},
                            chats = Review.chats,
                            favourites = setOf(2L),
                            recentSearches = listOf("harbour lights", "night harbour", "weekend club"),
                            suggestions = emptyList(),
                            progress = { null },
                            onOpenChat = {},
                            onOpenGroup = {},
                            onFilters = {},
                            onSort = {},
                            onLoadMore = {},
                            onRetry = {},
                        )
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
        if (focusClear) {
            rule.onNodeWithText(text).requestFocus()
            rule.onAllNodes(isRoot())[0].performKeyInput { pressKey(Key.DirectionRight) }
            rule.waitForIdle()
        }
        repeat(5) { rule.mainClock.advanceTimeBy(100) }
        Review.save(name, rule.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap())
    }

    @Test fun empty() = tv("tv-1-empty-recents.png", GlobalSearchState(), "")
    @Test fun clearFocused() = tv("tv-2-clear-focused.png", GlobalSearchState(searching = true), "harbour", focusClear = true)
}

/** The television's results, in a class of its own: a second walk in a process stalls the frames. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class SearchReviewTvResultsShots {
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

    @Test
    fun results() {
        FormFactor.override(true)
        Motion.reduced = true
        rule.setContent {
            TMPlayerTheme {
                Column(Modifier.fillMaxSize().background(Tone.background).padding(top = TV_BAR_HEIGHT, end = Tv.SafeH)) {
                    TvSearchScreen(
                        state = results("harbour"),
                        text = "harbour",
                        onText = {},
                        onSubmit = {},
                        chats = Review.chats,
                        favourites = setOf(2L),
                        recentSearches = emptyList(),
                        suggestions = emptyList(),
                        progress = { null },
                        onOpenChat = {},
                        onOpenGroup = {},
                        onFilters = {},
                        onSort = {},
                        onLoadMore = {},
                        onRetry = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        repeat(5) { rule.mainClock.advanceTimeBy(100) }
        Review.save("tv-3-results.png", rule.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap())
    }
}
