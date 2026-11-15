package com.tmplayer.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.tmplayer.data.DeviceForm
import com.tmplayer.data.DeviceFit
import com.tmplayer.data.DeviceFitRules
import com.tmplayer.data.LightSettings
import com.tmplayer.data.Recommendation
import com.tmplayer.data.SettingsStore
import com.tmplayer.i18n.Strings
import com.tmplayer.ui.components.deviceForm
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.floatingSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A recommendation as its own Settings row names it, and what it does once on. */
data class RecommendationCopy(val title: String, val detail: String)

fun Strings.recommendationCopy(recommendation: Recommendation): RecommendationCopy = when (recommendation) {
    Recommendation.LowStorageMode -> RecommendationCopy(settingsStreamRecommended, settingsStreamSaveDetail)
    Recommendation.NoSeekThumbnails -> RecommendationCopy(settingsTrickplay, settingsTrickplayOff)
    Recommendation.LighterPictures -> RecommendationCopy(settingsLighterPictures, settingsLighterPicturesOn)
    Recommendation.ReduceMotion -> RecommendationCopy(settingsReduceMotion, settingsReduceMotionOn)
}

/** "This device is short on memory", "on storage", or both. */
fun Strings.fitHeadline(fit: DeviceFit): String = when {
    fit.shortOfMemory && fit.shortOfStorage -> deviceShortBoth
    fit.shortOfStorage -> deviceShortStorage
    else -> deviceShortMemory
}

/** The settings this device is recommended, still off, as they change; empty for a device that is fine. */
@Composable
fun rememberRecommendations(settings: SettingsStore, fit: DeviceFit = rememberDeviceFit()): List<Recommendation> {
    val now by settings.lightSettings.collectAsState(initial = null)
    val shown = rememberSeekThumbnailsShown()
    val known = now ?: return emptyList()
    return remember(fit, known, shown) { DeviceFitRules.recommendations(fit, known, shown, pictures = hasPictureSettings) }
}

/** For the hint after a stutter or a memory warning, which are proof enough of short memory. */
fun recommendationsAfterStruggle(fit: DeviceFit, now: LightSettings, seekThumbnailsShown: Boolean): List<Recommendation> =
    DeviceFitRules.recommendations(fit.copy(shortOfMemory = true), now, seekThumbnailsShown, pictures = hasPictureSettings)

/**
 * The changes, one line each: the setting's name as its own row says it, and the line that row
 * shows once applied. Then the note that posters and details stay on, when they could matter.
 */
@Composable
fun RecommendationList(recommendations: List<Recommendation>, modifier: Modifier = Modifier, postersNote: Boolean = true) {
    val s = LocalStrings.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (recommendation in recommendations) {
            val copy = s.recommendationCopy(recommendation)
            Row(verticalAlignment = Alignment.Top) {
                Icon(TmIcons.CheckCircle, contentDescription = null, tint = Tone.accent, modifier = Modifier.padding(top = 1.dp).size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(copy.title, style = MaterialTheme.typography.titleSmall, color = Tone.text)
                    Text(copy.detail, style = MaterialTheme.typography.bodySmall, color = Tone.muted)
                }
            }
        }
    }
}

/**
 * Settings' "Recommended for this device" card, at the top of the list while anything recommended
 * is still off: the reason, the changes, Apply all and Not now (which closes it for good). On a
 * remote the focus starts on Apply all when [focusApply].
 */
@Composable
fun DeviceFitCard(
    fit: DeviceFit,
    recommendations: List<Recommendation>,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    focusApply: Boolean = false,
) {
    val s = LocalStrings.current
    val apply = remember { FocusRequester() }
    val whole = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    Column(
        modifier
            .fillMaxWidth()
            .then(Modifier.bringIntoViewRequester(whole))
            .floatingSurface()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(s.deviceCardTitle, style = MaterialTheme.typography.titleMedium, color = Tone.accent)
        Text(s.fitHeadline(fit), style = MaterialTheme.typography.titleSmall, color = Tone.text)
        Text(s.deviceQuestion, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
        RecommendationList(recommendations)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SetupButton(s.deviceApplyAll, primary = true, modifier = Modifier.focusRequester(apply), onClick = onApply)
            SetupButton(s.deviceNotNow, onClick = onDismiss)
        }
    }
    if (focusApply) {
        LaunchedEffect(Unit) {
            delay(FOCUS_DELAY_MS)
            runCatching { apply.requestFocus() }
            // The focus brings only the button into view; the reason above it belongs with it.
            runCatching { whole.bringIntoView() }
        }
    }
}

/**
 * The one-time hint on a phone, at the foot of the screen like the language card: the device is
 * struggling, Apply or Not now. A television asks the same in a dialog (the shell's TvConfirm).
 */
@Composable
fun DeviceHintBar(onApply: () -> Unit, onNotNow: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    Column(
        modifier.widthIn(max = 560.dp).floatingSurface().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(s.deviceHintTitle, style = MaterialTheme.typography.titleMedium, color = Tone.text)
        Text(s.deviceHintBody, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            SetupButton(s.deviceNotNow, onClick = onNotNow)
            SetupButton(s.deviceApply, primary = true, onClick = onApply)
        }
    }
}

/** Whether a remote or a keyboard drives this screen, which is when a first focus is placed. */
@Composable
internal fun remoteDriven(): Boolean = deviceForm() != DeviceForm.Phone

private const val FOCUS_DELAY_MS = 400L

/**
 * The Settings card, wired: shown while this device has anything recommended still off and the
 * viewer has not closed it. Apply all writes them in one go (each keeps its own row); Not now
 * closes the card for good.
 */
@Composable
fun DeviceFitSettingsCard(settings: SettingsStore, onApplied: () -> Unit, modifier: Modifier = Modifier) {
    val fit = rememberDeviceFit()
    if (!fit.any) return
    val dismissed by settings.deviceCardDismissed.collectAsState(initial = true)
    val recommendations = rememberRecommendations(settings, fit)
    if (dismissed || recommendations.isEmpty()) return
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    DeviceFitCard(
        fit = fit,
        recommendations = recommendations,
        onApply = {
            scope.launch { runCatching { settings.applyRecommendations(recommendations) } }
            onApplied()
        },
        onDismiss = { scope.launch { runCatching { settings.dismissDeviceCard() } } },
        modifier = modifier,
        // On a remote the card is the first thing on the page, and Apply all is what it is for.
        focusApply = remoteDriven(),
    )
}
