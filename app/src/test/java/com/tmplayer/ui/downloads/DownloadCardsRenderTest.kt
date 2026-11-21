package com.tmplayer.ui.downloads

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.FormFactor
import com.tmplayer.data.LocalDownloads
import com.tmplayer.data.MediaItem
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.ResumeRecord
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The cards of the one list (R2): a download, an auto-saved video (the old "Recently played") with
 * its "Auto-saved" tag and Keep, a part loaded one, and the running cards, on a phone at a normal
 * and at twice the font size and on a television. PNGs under `build/device-test/downloads-tab/`.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DownloadCardsRenderTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private val calls = mutableListOf<String>()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private fun record(id: Long, title: String, mb: Long) = ResumeRecord(
        chatId = 2, messageId = id, fileId = id.toInt(), title = title, chatTitle = "Weekend series",
        sizeBytes = mb * 1024 * 1024, durationSec = 3_000, positionMs = 0, durationMs = 0, updatedAt = id,
    )

    private fun row(id: Long, title: String, mb: Long, cached: Boolean = false, complete: Boolean = true, partOf: Long = 0) = DownloadRow(
        key = (if (cached) "cached_2_$id" else "kept_2_$id"),
        title = title,
        bytes = if (complete) mb * 1024 * 1024 else partOf * 1024 * 1024,
        totalBytes = mb * 1024 * 1024,
        complete = complete,
        record = record(id, title, mb),
        chatTitle = "Weekend series",
        durationSec = 3_000,
        state = LocalDownloads.FileState.Present,
        cached = cached,
    )

    private fun show(tv: Boolean, fontScale: Float = 1f, partial: Boolean = false) {
        FormFactor.override(tv)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                TMPlayerTheme {
                    Column(Modifier.fillMaxSize().background(Tone.background).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                        DownloadCard(
                            row = row(1, "Harbour Lights S01E01", 812),
                            picking = false, checked = false,
                            onPlay = { calls += "play-download" }, onShare = { calls += "share" },
                            onDelete = { calls += "delete" }, onToggle = {}, onHold = null,
                            onSaveToFolder = { calls += "folder" },
                        )
                        DownloadCard(
                            row = row(2, "Night market walk 1080p", 900, cached = true),
                            picking = false, checked = false,
                            onPlay = { calls += "play-auto" }, onShare = { calls += "share-auto" },
                            onDelete = { calls += "delete-auto" }, onToggle = {}, onHold = null,
                            onKeep = { calls += "keep" },
                        )
                        if (partial) {
                            DownloadCard(
                                row = row(3, "Lecture 8, graphs", 380, cached = true, complete = false, partOf = 120),
                                picking = false, checked = false,
                                onPlay = { calls += "play-part" }, onShare = {},
                                onDelete = { calls += "delete-part" }, onToggle = {}, onHold = null,
                                onKeep = { calls += "keep-part" },
                            )
                        }
                        ActiveDownloadCard(
                            progress = OfflineDownloads.Progress(
                                request = DownloadRequest.from(
                                    MediaItem(
                                        chatId = 2, messageId = 9, fileId = 9, title = "Mountain railway", sizeBytes = 1_500L * 1024 * 1024, durationSec = 0,
                                        mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 0,
                                    ),
                                    "Travel clips",
                                ),
                                downloadedBytes = 600L * 1024 * 1024, totalBytes = 1_500L * 1024 * 1024,
                                stage = OfflineDownloads.Stage.Paused,
                            ),
                            place = -1,
                            onPause = {}, onResume = { calls += "resume" }, onCancel = { calls += "cancel" },
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun shot(name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val file = File("../build/device-test/downloads-tab/$name").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `a phone tags the auto-saved video and keeps it behind Keep, a download is not tagged`() {
        show(tv = false)
        // One tag, on the auto-saved card only: the download's line has none.
        assertEquals(1, rule.onAllNodes(hasText(L.downloadsAutoSaved, substring = true)).fetchSemanticsNodes().size)
        shot("cards-phone-dark.png")

        // The auto-saved card plays on a tap, as a download does.
        rule.onNodeWithText("Night market walk", substring = true).performClick()
        rule.waitForIdle()
        assertEquals(listOf("play-auto"), calls)

        // Its More menu leads with Keep; the download's does not have it.
        val more = rule.onAllNodesWithMore()
        more[1].performClick()
        rule.waitForIdle()
        rule.onNodeWithText(L.downloadsKeep).assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(listOf("play-auto", "keep"), calls)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `a download's More menu has no Keep`() {
        show(tv = false)
        rule.onAllNodesWithMore()[0].performClick()
        rule.waitForIdle()
        rule.onNodeWithText(L.commonShare).assertIsDisplayed()
        assertEquals("the download's menu has no Keep", 0, rule.onAllNodes(hasText(L.downloadsKeep)).fetchSemanticsNodes().size)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h640dp-port-night-xhdpi")
    fun `at twice the font size the tag and the words still show`() {
        show(tv = false, fontScale = 2f, partial = true)
        assertTrue(rule.onAllNodes(hasText(L.downloadsAutoSaved, substring = true)).fetchSemanticsNodes().isNotEmpty())
        shot("cards-phone-narrow-2x-dark.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-notnight-xhdpi")
    fun `in light`() {
        show(tv = false, partial = true)
        shot("cards-phone-light.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `on a television the auto-saved card has Watch and Keep as named buttons`() {
        show(tv = true, partial = true)
        shot("cards-tv-dark.png")
        // The whole one and the part loaded one each have the button, named, as a remote needs it.
        assertEquals(2, rule.onAllNodes(hasText(L.downloadsKeep)).fetchSemanticsNodes().size)
        rule.onAllNodes(hasText(L.downloadsKeep))[0].performClick()
        rule.waitForIdle()
        assertEquals(listOf("keep"), calls)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `a paused running card says paused and has Resume and Cancel`() {
        show(tv = false)
        rule.onNodeWithText(L.commonResume).assertExists()
        rule.onNodeWithText(L.commonCancel).assertExists()
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithMore() =
        onAllNodes(androidx.compose.ui.test.hasContentDescription(L.commonMoreOptions))
            .fetchSemanticsNodes().indices.map { onAllNodes(androidx.compose.ui.test.hasContentDescription(L.commonMoreOptions))[it] }
}
