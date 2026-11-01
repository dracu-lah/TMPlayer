package com.tmplayer.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.tmplayer.data.DeviceFit
import com.tmplayer.data.Recommendation
import com.tmplayer.data.SettingsStore
import com.tmplayer.ui.components.deviceForm

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
    val asked = remember(steps, crashReporting.available) { Setup.withCrashReports(steps, crashReporting.available) }
    val pages = remember(asked) { Setup.pages(asked) }
    val setup = remember(pages) { pages.takeIf { it.isNotEmpty() }?.let { SetupState.paged(it) } }
    var phase by remember { mutableStateOf(SetupPhase.Appearance) }
    val groups = if (setup != null) listOf(1, pages.size) else listOf(1)

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
