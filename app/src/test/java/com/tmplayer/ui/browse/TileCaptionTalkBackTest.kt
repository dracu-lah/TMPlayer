package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.TMPlayerTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WP-17: a tile's line of facts is drawn with the middle dot between them and read with commas, so
 * TalkBack never says "middle dot", on the phone's caption and on the television's. A caller that
 * passes its own wording keeps it; one that only joins with the dot gets the commas.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
class TileCaptionTalkBackTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private fun show(tv: Boolean) {
        FormFactor.override(tv)
        rule.setContent {
            TMPlayerTheme {
                Column {
                    TileCaption(
                        "1 h 12 min  ·  1.2 GB  ·  720p",
                        androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        { Color.White },
                        description = "1 h 12 min, 1.2 GB, 720p",
                    )
                    TileCaption(
                        "2021  ·  Stopped at 12:03",
                        androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        { Color.White },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun check() {
        rule.onNode(hasContentDescription("1 h 12 min, 1.2 GB, 720p")).assertExists()
        rule.onNode(hasContentDescription("2021, Stopped at 12:03")).assertExists()
        rule.onNode(hasContentDescription("·", substring = true)).assertDoesNotExist()
    }

    @Test
    fun `the phone's caption is read with commas`() {
        show(tv = false)
        check()
    }

    @Test
    fun `the television's caption is read with commas`() {
        show(tv = true)
        check()
    }
}
