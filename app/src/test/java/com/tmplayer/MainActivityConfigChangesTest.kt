package com.tmplayer

import android.content.ComponentName
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * WP-39, second commit: a turn, a fold, an unfold or a split screen resize no longer recreates the
 * shell. That is safe because the tree reads its size from the composition (BoxWithConstraints,
 * LocalConfiguration, LocalWindowInfo: every one found by grep is reactive, and the one-shot reads in
 * the shell, such as FormFactor.isTv, are about the UI mode, which still recreates), and the two
 * came-from values and the screen are saved anyway ([StateSurvivesRecreationTest]).
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityConfigChangesTest {

    @Test
    fun theShellHandlesSizeAndOrientationItself() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        for ((name, bit) in listOf(
            "screenSize" to ActivityInfo.CONFIG_SCREEN_SIZE,
            "smallestScreenSize" to ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE,
            "screenLayout" to ActivityInfo.CONFIG_SCREEN_LAYOUT,
            "orientation" to ActivityInfo.CONFIG_ORIENTATION,
            "locale" to ActivityInfo.CONFIG_LOCALE,
            "layoutDirection" to ActivityInfo.CONFIG_LAYOUT_DIRECTION,
        )) {
            assertTrue("MainActivity handles $name", info.configChanges and bit != 0)
        }
    }

    @Test
    fun theUiModeStillRecreatesSoTheThemeAndTheLayoutChoiceAreReadAgain() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        assertTrue(info.configChanges and ActivityInfo.CONFIG_UI_MODE == 0)
    }
}
