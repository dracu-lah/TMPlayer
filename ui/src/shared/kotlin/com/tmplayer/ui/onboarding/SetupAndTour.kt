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
 * The setup a fresh sign in walks through (CP67, CP69): one flow under one name, in three groups
 * on one count and one bar. Appearance first ([AppearanceStep]: theme and interface size, so
 * everything after is already drawn the way the viewer likes it), then the preferences
 * ([SetupScreen]), then the quick tour ([TourScreen]) as the last, optional group. Skip all, on any
 * step, ends the whole setup; Back on a group's first step goes to the step before it.
 *
 * [onDone] is called once, at the end: the host marks the setup and the tour done.
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
    /** The real "Add chats" picker for [SetupStep.Chats] (HX27/HX28); see [SetupScreen]'s own doc. */
    chatsPicker: (@Composable (onClose: () -> Unit) -> Unit)? = null,
) {
    val form = deviceForm()
    val pages = remember(form) { Tour.pages(form) }
    val asked = remember(steps, crashReporting.available) { Setup.withCrashReports(steps, crashReporting.available) }
    val setup = remember(asked) { asked.takeIf { it.isNotEmpty() }?.let { SetupState(it) } }
    val tour = remember(pages) { TourProgress(pages) }
    var phase by remember { mutableStateOf(SetupPhase.Appearance) }
    val groups = listOf(1, asked.size, pages.size)

    when {
        phase == SetupPhase.Appearance -> AppearanceStep(
            settings,
            onNext = { phase = if (setup != null) SetupPhase.Preferences else SetupPhase.Tour },
            onSkipAll = onDone,
            modifier = modifier,
            groups = groups,
        )
        phase == SetupPhase.Preferences && setup != null -> SetupScreen(
            settings,
            setup,
            onDone = { phase = SetupPhase.Tour },
            modifier = modifier,
            fit = fit,
            recommendations = recommendations,
            before = 1,
            groups = groups,
            moreAfter = true,
            onSkipAll = onDone,
            onBackFromFirst = { phase = SetupPhase.Appearance },
            crashReporting = crashReporting,
            chatsPicker = chatsPicker,
        )
        else -> TourScreen(
            tour,
            onDone = onDone,
            modifier = modifier,
            before = 1 + asked.size,
            groups = groups,
            onBackFromFirst = { phase = if (setup != null) SetupPhase.Preferences else SetupPhase.Appearance },
        )
    }
}

/** The setup's three groups, in the order they come. */
private enum class SetupPhase { Appearance, Preferences, Tour }

/** The quick tour on its own: taken from the offer to an updated install, or opened from Settings. */
@Composable
fun TourOnly(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val form = deviceForm()
    val tour = remember(form) { TourProgress(Tour.pages(form)) }
    TourScreen(tour, onDone = onDone, modifier = modifier)
}
