package com.tmplayer.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.tmplayer.data.DeviceFit
import com.tmplayer.data.Recommendation
import com.tmplayer.data.SettingsStore
import com.tmplayer.i18n.Translator
import com.tmplayer.ui.components.deviceForm
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The setup a fresh sign in walks through (CP67, CP69): one flow under one name, in two groups on
 * one count and one bar. Appearance first ([AppearanceStep]: theme and interface size, so
 * everything after is already drawn the way the viewer likes it), then the preferences
 * ([SetupScreen]), with the switch steps together on one page ([Setup.pages]). Skip all, on any
 * step, ends the whole setup; Back on the preferences' first page goes back to Appearance.
 *
 * The quick tour is no longer a counted part of it: once the setup is done the shell offers the
 * tour on its own, once (`SettingsStore.markSetupDone` leaves it at `TourState.Never`), after the
 * mandatory chat choice, and [TourOnly] shows it when taken.
 *
 * [onDone] is called once, at the end: the host marks the setup done.
 */
@Composable
fun SetupAndTour(
    settings: SettingsStore,
    steps: List<SetupStep>,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    fit: DeviceFit = DeviceFit.Fine,
    recommendations: List<Recommendation> = emptyList(),
    /**
     * Whether this build can send crash reports, and what turns them on (CP102f). Available, the
     * preferences end with the crash report question; the desktop passes the default until it
     * reports crashes.
     */
    crashReporting: CrashReporting = CrashReporting.Unavailable,
) {
    // Performance only where the device is short of memory (ON-8); the rest keep their defaults.
    val asked = remember(steps, fit, crashReporting.available) {
        Setup.withCrashReports(Setup.firstRun(steps, fit), crashReporting.available)
    }
    val pages = remember(asked) { Setup.pages(asked) }
    val setup = remember(pages) { pages.takeIf { it.isNotEmpty() }?.let { SetupState.paged(it) } }
    var phase by remember { mutableStateOf(SetupPhase.Appearance) }
    val groups = if (setup != null) listOf(1, pages.size) else listOf(1)
    val scope = rememberCoroutineScope()
    var finishing by remember { mutableStateOf(false) }

    // The setup is in this language, so it is not announced again on the first Home (ON-10).
    val language = Translator.active.collectAsState().value.tag
    LaunchedEffect(language) { Setup.markLanguageSeen(settings, language) }

    // Use recommended settings: what walking through on Next would leave, in one press. A device
    // with recommendations gets them (the Device step starts on Apply); every other default is
    // already the recommended one. Written to the end before the setup closes (ON-16).
    val useRecommended: () -> Unit = {
        if (!finishing) {
            finishing = true
            scope.launch {
                withContext(NonCancellable) {
                    withTimeoutOrNull(WRITES_MS) {
                        if (recommendations.isNotEmpty()) {
                            try {
                                SetupChoice.ApplyRecommended.applyTo(settings, posters = null, recommendations = recommendations)
                            } catch (e: Exception) {
                                // A write that fails must not keep the setup open.
                            }
                        }
                    }
                }
                onDone()
            }
        }
    }

    when {
        phase == SetupPhase.Preferences && setup != null -> SetupScreen(
            settings,
            setup,
            onDone = onDone,
            modifier = modifier,
            fit = fit,
            recommendations = recommendations,
            before = 1,
            groups = groups,
            onSkipAll = onDone,
            onBackFromFirst = { phase = SetupPhase.Appearance },
            crashReporting = crashReporting,
        )
        else -> AppearanceStep(
            settings,
            onNext = { if (setup != null) phase = SetupPhase.Preferences else onDone() },
            onSkipAll = onDone,
            modifier = modifier,
            groups = groups,
            moreAfter = setup != null,
            onUseRecommended = if (setup != null) useRecommended else null,
        )
    }
}

/** The setup's two groups, in the order they come. */
private enum class SetupPhase { Appearance, Preferences }

/** The quick tour on its own: taken from the offer to an updated install, or opened from Settings. */
@Composable
fun TourOnly(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val form = deviceForm()
    val tour = remember(form) { TourProgress(Tour.pages(form)) }
    TourScreen(tour, onDone = onDone, modifier = modifier)
}
