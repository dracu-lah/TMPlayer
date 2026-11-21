package com.tmplayer.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tmplayer.data.FormFactor
import com.tmplayer.data.OfflineHome
import com.tmplayer.data.ResumeRecord
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.browse.ContinueCardArt
import com.tmplayer.ui.browse.LocalOfflineHome
import com.tmplayer.ui.connect.OfflineBanner
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Offline Home (PL-13, R4): the offline card leads to Downloads where Downloads is a place to go,
 * has Try again, and cards in Downloads say Downloaded.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class OfflineHomeTest {

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

    private val record = ResumeRecord(
        chatId = -100L, messageId = 7L, fileId = 1, title = "Harbour Notes", chatTitle = "Films",
        sizeBytes = 1_000, durationSec = 6_000, positionMs = 600_000, durationMs = 6_000_000, updatedAt = 1,
    )

    @Test
    fun `the offline card says so and opens Downloads on a touch screen`() {
        FormFactor.override(false)
        Motion.reduced = true
        var opened = 0
        rule.setContent {
            TMPlayerTheme { OfflineBanner(onOpenDownloads = { opened++ }) }
        }
        rule.onNodeWithText(L.stateOffline).assertIsDisplayed()
        rule.onNodeWithText(L.commonRetry).assertIsDisplayed()
        rule.onNodeWithText(L.downloadsTitle).assertIsDisplayed().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `the offline card on a television has Try again and no Downloads button`() {
        FormFactor.override(true)
        Motion.reduced = true
        rule.setContent {
            TMPlayerTheme { OfflineBanner(onOpenDownloads = null) }
        }
        rule.onNodeWithText(L.commonRetry).assertIsDisplayed()
        rule.onNodeWithText(L.downloadsTitle).assertDoesNotExist()
    }

    @Test
    fun `a continue card in Downloads says Downloaded`() {
        Motion.reduced = true
        rule.setContent {
            TMPlayerTheme {
                CompositionLocalProvider(LocalOfflineHome provides OfflineHome(true, downloads = listOf(record))) {
                    ContinueCardArt(record, record.toMediaItem())
                }
            }
        }
        rule.onNodeWithText(L.gridBadgeDownloaded).assertIsDisplayed()
    }

    @Test
    fun `a continue card not in Downloads, or online, has no badge`() {
        Motion.reduced = true
        val other = record.copy(messageId = 8)
        rule.setContent {
            TMPlayerTheme {
                CompositionLocalProvider(LocalOfflineHome provides OfflineHome(true, downloads = listOf(other))) {
                    ContinueCardArt(record, record.toMediaItem())
                }
            }
        }
        rule.onNodeWithText(L.gridBadgeDownloaded).assertDoesNotExist()
    }
}
