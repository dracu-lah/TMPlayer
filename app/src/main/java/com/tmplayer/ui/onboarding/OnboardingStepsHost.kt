package com.tmplayer.ui.onboarding

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.data.BackgroundChecks
import com.tmplayer.data.FormFactor
import com.tmplayer.data.NotificationAsk
import com.tmplayer.data.OnboardingSteps
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.SupportReminder
import com.tmplayer.ui.about.About
import com.tmplayer.ui.settings.rememberCanNotify
import com.tmplayer.ui.update.openLink
import kotlinx.coroutines.launch

/**
 * The first run's optional steps after the chats question, on a phone, a tablet and a television
 * ([OnboardingSteps]): notifications (Android 13 or later, never on a television) and supporting
 * TMPlayer (not in the Play build). Draws the step that is due and returns true so the shell shows
 * nothing else; returns false, drawing nothing, when none is due.
 *
 * The system's question is put only from the step's own button, through the shell's Activity
 * Result launcher ([NotificationAsk]), never on start up. Once it was asked in any way, or refused,
 * the step turns into the route through the system's notification settings and is never put again;
 * coming back from there is read on resume, and granted notifications end the step by themselves.
 * Skip and Back end a step as skipped. Every outcome is stored, so a restart, a rotation or a
 * second launch resumes at the right step and never repeats one.
 *
 * Nothing is drawn while the app lock is unread ([lockRead] false) or the app is [locked]: a step
 * that is due waits (this still returns true, so the shell shows nothing else either) and never
 * shows for a frame before the lock state is known. There is no default for either, so a caller
 * cannot fail open by leaving them out.
 */
@Composable
fun OnboardingStepsHost(settings: SettingsStore, lockRead: Boolean, locked: Boolean): Boolean {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tv = remember { FormFactor.isTv(context) }
    val state by settings.onboardingSteps.collectAsStateWithLifecycle(initialValue = OnboardingSteps.State())
    // Read again on every resume (the system's page and the question both leave and come back) and
    // after each answer, which can arrive before the screen is composed.
    val resumedCan by rememberCanNotify()
    val answers by NotificationAsk.answers.collectAsStateWithLifecycle()
    val canNotify = remember(resumedCan, answers) { BackgroundChecks.canNotify(context) }
    // An answer that was a yes ends the step; a no is stored, so the step turns to its settings form.
    // Only an answer that comes while this screen is up counts: one given earlier in the process is not its.
    val answersBefore = remember { answers }
    LaunchedEffect(answers) {
        if (answers == answersBefore) return@LaunchedEffect
        val granted = NotificationAsk.lastGranted ?: return@LaunchedEffect
        val outcome = if (granted) OnboardingSteps.Outcome.Done else OnboardingSteps.Outcome.Denied
        runCatching { settings.markOnboardingStep(OnboardingSteps.Step.Notifications, outcome) }
    }

    // iOS compiles this file too, and is not a target of these steps: its notification permission
    // is its own flow (WP-38 left it for later) and a donation ask needs the App Store's say. Its
    // twin of BackgroundChecks says it posts no notices, which is how both steps are left out there.
    val android = BackgroundChecks.postsNotices
    val inputs = OnboardingSteps.Inputs(
        notificationsOff = android && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !tv && !canNotify,
        supportOffered = android && SupportReminder.enabled,
    )
    val step = OnboardingSteps.next(state, inputs) ?: return false
    // A lock not yet read waits, and a locked app shows nothing: never fails open (SEC).
    if (!lockRead || locked) return true
    val position = OnboardingSteps.applicable(inputs).let { all ->
        StepOf(all.indexOf(step) + 1, all.size)
    }
    val skip: () -> Unit = { scope.launch { runCatching { settings.markOnboardingStep(step, OnboardingSteps.Outcome.Skipped) } } }

    when (step) {
        OnboardingSteps.Step.Notifications -> {
            // The question is put once, ever, by this step: asked before in any way (a no, a yes
            // that was later turned off, an earlier install) or refused for good, it is the settings route.
            val settingsRoute = state.outcomes[step] == OnboardingSteps.Outcome.Denied ||
                NotificationAsk.asked(context) || !NotificationAsk.canAsk(context)
            NotificationsStepScreen(
                settingsRoute = settingsRoute,
                onAllow = { if (!NotificationAsk.pending) NotificationAsk.request(context) },
                onOpenSettings = { NotificationAsk.openSettings(context) },
                onSkip = skip,
                position = position,
            )
        }
        OnboardingSteps.Step.Support -> {
            // A phone with no browser, like a television, shows the page as a code instead.
            var noBrowser by rememberSaveable { mutableStateOf(false) }
            SupportStepScreen(
                onOpenPage = if (tv || noBrowser) {
                    null
                } else {
                    {
                        if (openLink(context, About.supportUrl(SOURCE))) {
                            scope.launch { runCatching { settings.markOnboardingStep(step, OnboardingSteps.Outcome.Opened) } }
                        } else {
                            noBrowser = true
                        }
                    }
                },
                onSkip = skip,
                position = position,
            )
        }
    }
    return true
}
