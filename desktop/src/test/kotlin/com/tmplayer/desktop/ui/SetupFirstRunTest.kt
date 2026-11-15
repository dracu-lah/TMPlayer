package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tmplayer.data.SettingsStore
import com.tmplayer.ui.onboarding.SetupScreen
import com.tmplayer.ui.onboarding.SetupState
import com.tmplayer.ui.onboarding.SetupStep
import com.tmplayer.ui.theme.TmMaterialTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files

/**
 * The first run's shortcuts and its last press (ON-8, ON-9, ON-16, ON-20): one press to take the
 * recommended settings, the last page's writes surviving the screen leaving, and the remote's way
 * to the shortcut. The desktop's keyboard moves focus as a television's D-pad does.
 */
@OptIn(ExperimentalTestApi::class)
class SetupFirstRunTest {

    private val settings = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-setup-first").resolve(SettingsStore.FILE_NAME).toFile()),
    )

    private fun button(label: String) = hasText(label) and hasClickAction()

    /**
     * ON-16: Done on the last page with an answer that writes three settings, in a host that drops
     * the screen the moment it is told the setup is done (as the shell does once the flag flips).
     */
    @Test
    fun lastPageWritesFinishAfterTheScreenLeaves() = runComposeUiTest {
        var showing by mutableStateOf(true)
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(1280.dp, 800.dp)) {
                    if (showing) {
                        SetupScreen(
                            settings,
                            remember { SetupState(listOf(SetupStep.Performance)) },
                            onDone = { showing = false },
                        )
                    }
                }
            }
        }
        onNode(button("Lighter")).performClick()
        onNode(button("Done")).performClick()
        waitForIdle()
        assertFalse(showing)
        waitUntil(timeoutMillis = WRITE_MS) {
            runBlocking { settings.lighterPictures.first() && settings.reduceMotion.first() && !settings.trickplay.first() }
        }
    }

    private companion object {
        const val WRITE_MS = 3_000L
    }
}
