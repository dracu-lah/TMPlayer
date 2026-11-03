package com.tmplayer.ui.settings

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Storage location list on a phone (HX57): "Other folder" is offered where the system's folder
 * picker exists, with its detail line, and left out where it does not.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
class DownloadLocationPhoneTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private fun open() {
        FormFactor.override(false)
        rule.setContent {
            TmMaterialTheme(dark = true) { Column(androidx.compose.ui.Modifier.fillMaxSize()) { DownloadLocationRow(onMoved = {}) } }
        }
        rule.onNodeWithText("Storage location").performClick()
        rule.waitForIdle()
    }

    @Test
    fun `other folder is offered when the system has a folder picker`() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        shadowOf(rule.activity.packageManager).addResolveInfoForIntent(
            picker,
            ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = "com.android.documentsui"; name = "Picker" } },
        )

        open()

        rule.onAllNodesWithText("Other folder").assertCountEquals(1)
        rule.onAllNodesWithText("Pick or create a folder, such as Download/TMPlayer").assertCountEquals(1)
        rule.onAllNodesWithText("Internal storage").assertCountEquals(2)
    }

    @Test
    fun `other folder is left out when nothing can pick a folder`() {
        open()

        rule.onAllNodesWithText("Other folder").assertCountEquals(0)
        rule.onAllNodesWithText("Internal storage").assertCountEquals(2)
    }
}
