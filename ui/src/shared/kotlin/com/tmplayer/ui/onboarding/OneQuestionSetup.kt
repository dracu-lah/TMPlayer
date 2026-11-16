package com.tmplayer.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.tmplayer.data.DeviceFitRules
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.SetupFlow
import com.tmplayer.i18n.Translator
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * What runs beside the setup's one question ([SetupFlow]), the same on the phone, the television
 * and the desktop. The question itself is the chat picker in its required form, drawn by the host
 * ([com.tmplayer.ui.browse.AddChatsFed]), so this draws nothing:
 *
 * - The setup is drawn in the language in use, so that language has already been seen
 *   ([Setup.markLanguageSeen]): the first Home does not say "Now in Español".
 * - DeviceFit chooses Lighter or Full, silently (R3): when an account that has not answered is
 *   about to be asked, the settings [DeviceFitRules] recommends for this device and that are
 *   still off are turned on, once, in one write. A fine device has nothing to turn on, so it
 *   stays Full. Each stays its own setting in Settings, undone in its own row, and Settings'
 *   "Redo setup choices" still lists them. A replay and an account that already answered
 *   (a sign in on a device that had a setup) write nothing, so a choice made later is never
 *   overwritten.
 * - [SetupFlow.Next.Finish] marks the setup done (not cancelled by the screen going away under
 *   it, so the next start never asks again).
 */
@Composable
fun SetupFlowEffects(settings: SettingsStore, next: SetupFlow.Next, replay: Boolean) {
    val language = Translator.active.collectAsState().value.tag
    LaunchedEffect(language) { Setup.markLanguageSeen(settings, language) }

    val fit = rememberDeviceFit()
    val seekThumbnails = rememberSeekThumbnailsShown()
    val light by settings.lightSettings.collectAsState(initial = null)
    var applied by rememberSaveable { mutableStateOf(false) }
    // Asked, not just opened: the stored answer is read and is still missing (or the wait for it).
    val asking = !replay && (next == SetupFlow.Next.Wait || next == SetupFlow.Next.Offline || next == SetupFlow.Next.Choose)
    val now = light
    LaunchedEffect(asking, now != null) {
        if (!asking || applied || now == null) return@LaunchedEffect
        applied = true
        withContext(NonCancellable) {
            runCatching {
                settings.applyRecommendations(DeviceFitRules.recommendations(fit, now, seekThumbnails, pictures = hasPictureSettings))
            }
        }
    }

    LaunchedEffect(next) {
        if (next == SetupFlow.Next.Finish) withContext(NonCancellable) { runCatching { settings.markSetupDone() } }
    }
}
