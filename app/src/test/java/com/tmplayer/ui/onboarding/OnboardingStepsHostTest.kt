package com.tmplayer.ui.onboarding

import android.Manifest
import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.tmplayer.data.FormFactor
import com.tmplayer.data.NotificationAsk
import com.tmplayer.data.OnboardingSteps
import com.tmplayer.data.OnboardingSteps.Outcome
import com.tmplayer.data.OnboardingSteps.Step
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.SupportReminder
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * The first run's notifications and support steps on Android: the order and the skip paths, the
 * system's question put only from the step's button and never again after an answer, the way
 * through the system's settings, the television's single Skip button and where the remote lands.
 * The permission launcher is a fake: the real one is the shell's Activity Result registration.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class OnboardingStepsHostTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val drain = DrainMainLooperRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var store: SettingsStore
    private var asks = 0

    @Before
    fun setUp() {
        store = SettingsStore(SettingsStore.openDataStore(File(folder.root, "prefs/${SettingsStore.FILE_NAME}")))
        SupportReminder.enabled = true
        asks = 0
        NotificationAsk.launcher = { asks++ }
    }

    @After
    fun tearDown() {
        NotificationAsk.launcher = null
        FormFactor.override(false)
        Motion.reduced = false
        SupportReminder.enabled = false
        app.getSharedPreferences("once", 0).edit().clear().apply()
    }

    private fun firstRun() = runBlocking {
        store.armSetup()
        store.markSetupDone()
    }

    private fun outcomes() = runBlocking { store.onboardingSteps.first().outcomes }

    private var drew = false

    private fun show() {
        rule.setContent { TMPlayerTheme { drew = OnboardingStepsHost(store, lockRead = true, locked = false) } }
        // The store answers on its own thread: the step is up once it has.
        rule.waitUntil(5_000) { drew }
        rule.waitForIdle()
    }

    /** Waits for [text] to be on screen, and says what was there when it is not. */
    private fun waitForText(text: String) {
        try {
            rule.waitUntil(5_000) { rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            rule.onRoot().printToLog("OnboardingStepsHostTest")
            throw AssertionError("waiting for \"$text\"; drew=$drew; outcomes=${outcomes()}", e)
        }
    }

    /** A due step with the lock unread, or the app locked, draws nothing at all and asks nothing, yet holds the shell (true). */
    private fun heldBack(lockRead: Boolean, locked: Boolean) {
        firstRun()
        rule.setContent { TMPlayerTheme { drew = OnboardingStepsHost(store, lockRead = lockRead, locked = locked) } }
        // The store answers on its own thread: the step is due once it has, and then it is held, not drawn.
        rule.waitUntil(5_000) { drew }
        rule.waitForIdle()
        assertTrue("a due step holds the shell back", drew)
        assertEquals("nothing is drawn", 0, rule.onAllNodes(hasText(L.onboardingNotificationsTitle)).fetchSemanticsNodes().size)
        assertEquals("no Skip button either", 0, rule.onAllNodes(hasText(L.setupSkip)).fetchSemanticsNodes().size)
        assertEquals(0, asks)
    }

    @Test
    @Config(sdk = [33])
    fun aLockNotYetReadDrawsNothingAndNeverFailsOpen() = heldBack(lockRead = false, locked = false)

    @Test
    @Config(sdk = [33])
    fun aLockedAppDrawsNoStep() = heldBack(lockRead = true, locked = true)

    @Test
    @Config(sdk = [33])
    fun nothingIsShownOrAskedForAnInstallThatWasOnlyUpdated() {
        rule.setContent { TMPlayerTheme { drew = OnboardingStepsHost(store, lockRead = true, locked = false) } }
        rule.waitForIdle()
        assertFalse(drew)
        assertEquals(0, asks)
    }

    @Test
    @Config(sdk = [33])
    fun theSystemQuestionComesOnlyFromTheButtonNeverOnStart() {
        firstRun()
        show()
        rule.onNodeWithText(L.onboardingNotificationsTitle).assertIsDisplayed()
        rule.onNodeWithText(L.setupSkip).assertIsDisplayed()
        assertEquals("a cold start asks nothing", 0, asks)
        rule.onNodeWithText(L.onboardingNotificationsAllow).performClick()
        assertEquals(1, asks)
        // A second press while the question is up puts nothing more.
        rule.onNodeWithText(L.onboardingNotificationsAllow).performClick()
        assertEquals(1, asks)
    }

    @Test
    @Config(sdk = [33])
    fun aYesEndsTheStepAndMovesToSupport() {
        firstRun()
        show()
        rule.onNodeWithText(L.onboardingNotificationsAllow).performClick()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationAsk.answered(rule.activity, granted = true)
        waitForText(L.onboardingSupportTitle)
        assertEquals(Outcome.Done, outcomes()[Step.Notifications])
    }

    @Test
    @Config(sdk = [33])
    fun aNoTurnsTheStepIntoTheSettingsRouteAndNeverAsksAgain() {
        firstRun()
        show()
        rule.onNodeWithText(L.onboardingNotificationsAllow).performClick()
        NotificationAsk.answered(rule.activity, granted = false)
        waitForText(L.onboardingNotificationsSettings)
        rule.onNodeWithText(L.onboardingNotificationsOffTitle).assertIsDisplayed()
        assertEquals(Outcome.Denied, outcomes()[Step.Notifications])
        rule.onNodeWithText(L.onboardingNotificationsSettings).performClick()
        val started: Intent = shadowOf(rule.activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, started.action)
        assertEquals("no second question", 1, asks)
    }

    @Test
    @Config(sdk = [33])
    fun refusedForGoodBeforeShowsTheSettingsRouteAtOnce() {
        NotificationAsk.answered(rule.activity)
        firstRun()
        show()
        rule.onNodeWithText(L.onboardingNotificationsSettings).assertIsDisplayed()
        assertEquals(0, asks)
    }

    @Test
    @Config(sdk = [33])
    fun skipEndsTheStepAndIsStoredSoItDoesNotComeBack() {
        firstRun()
        show()
        rule.onNodeWithText(L.setupSkip).performClick()
        waitForText(L.onboardingSupportTitle)
        assertEquals(Outcome.Skipped, outcomes()[Step.Notifications])
        assertEquals(0, asks)
    }

    @Test
    @Config(sdk = [33])
    fun backIsSkipAndNeverTraps() {
        firstRun()
        show()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        waitForText(L.onboardingSupportTitle)
        assertEquals(Outcome.Skipped, outcomes()[Step.Notifications])
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntil(5_000) { !drew }
        assertEquals(Outcome.Skipped, outcomes()[Step.Support])
    }

    @Test
    @Config(sdk = [32])
    fun androidTwelveAndOlderHasNoNotificationStep() {
        firstRun()
        show()
        rule.onNodeWithText(L.onboardingSupportTitle).assertIsDisplayed()
        assertEquals(0, asks)
    }

    @Test
    @Config(sdk = [33])
    fun alreadyGrantedSkipsTheNotificationStep() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        firstRun()
        show()
        rule.onNodeWithText(L.onboardingSupportTitle).assertIsDisplayed()
    }

    @Test
    @Config(sdk = [33])
    fun aRestartResumesAtTheStepThatWasNotAnswered() {
        firstRun()
        runBlocking { store.markOnboardingStep(Step.Notifications, Outcome.Skipped) }
        show()
        rule.onNodeWithText(L.onboardingSupportTitle).assertIsDisplayed()
    }

    @Test
    @Config(sdk = [33])
    fun theSupportButtonOpensTheTaggedPageInTheBrowserAndIsStoredOnce() {
        firstRun()
        runBlocking { store.markOnboardingStep(Step.Notifications, Outcome.Skipped) }
        show()
        rule.onNodeWithText(L.onboardingSupportOpen).performClick()
        val started: Intent = shadowOf(rule.activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://tmplayer.org/support/?utm_source=onboarding&utm_medium=app", started.data.toString())
        rule.waitUntil(5_000) { !drew }
        assertEquals(Outcome.Opened, outcomes()[Step.Support])
        // A second launch reads the same stored outcomes and has no step left to show.
        val stored = runBlocking { store.onboardingSteps.first() }
        assertNull(OnboardingSteps.next(stored, OnboardingSteps.Inputs(notificationsOff = true, supportOffered = true)))
    }

    @Test
    @Config(sdk = [33])
    fun theSupportStepIsLeftOutOfABuildWithoutSupportLinks() {
        SupportReminder.enabled = false
        firstRun()
        runBlocking { store.markOnboardingStep(Step.Notifications, Outcome.Skipped) }
        rule.setContent { TMPlayerTheme { drew = OnboardingStepsHost(store, lockRead = true, locked = false) } }
        rule.waitForIdle()
        assertFalse(drew)
    }

    @Test
    @Config(sdk = [33], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun aTelevisionHasNoNotificationStepAndLandsOnSkipWithTheCode() {
        FormFactor.override(true)
        firstRun()
        show()
        rule.onNodeWithText(L.onboardingSupportTitle).assertIsDisplayed()
        rule.onNodeWithText(com.tmplayer.ui.onboarding.shortAddress()).assertIsDisplayed()
        // No browser button: the page is the code, and Skip is the one button and holds the remote.
        assertTrue(rule.onAllNodes(hasText(L.onboardingSupportOpen)).fetchSemanticsNodes().isEmpty())
        rule.waitUntil(5_000) { rule.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(L.setupSkip).assertIsFocused()
        assertEquals(0, asks)
    }

    @Test
    @Config(sdk = [33])
    fun reducedMotionDrawsEveryStepAtRest() {
        Motion.reduced = true
        firstRun()
        show()
        rule.onNodeWithText(L.onboardingNotificationsTitle).assertIsDisplayed()
        assertNull(outcomes()[Step.Notifications])
    }
}
