package com.tmplayer.data

import com.tmplayer.data.OnboardingSteps.Inputs
import com.tmplayer.data.OnboardingSteps.Outcome
import com.tmplayer.data.OnboardingSteps.State
import com.tmplayer.data.OnboardingSteps.Step
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

/** The first run's notifications and support steps: order, skip paths, what is stored and when it is armed. */
class OnboardingStepsTest {

    private val both = Inputs(notificationsOff = true, supportOffered = true)
    private val armed = State(armed = true)

    @Test
    fun `notifications come first, then support, then nothing`() {
        assertEquals(Step.Notifications, OnboardingSteps.next(armed, both))
        val afterNotes = armed.with(Step.Notifications, Outcome.Skipped)
        assertEquals(Step.Support, OnboardingSteps.next(afterNotes, both))
        assertNull(OnboardingSteps.next(afterNotes.with(Step.Support, Outcome.Skipped), both))
    }

    @Test
    fun `nothing shows until the first run armed it, so an updated install sees none`() {
        assertNull(OnboardingSteps.next(State(), both))
        assertNull(OnboardingSteps.next(OnboardingSteps.decode(null), both))
        assertNull(OnboardingSteps.next(OnboardingSteps.decode("notifications=skipped"), both))
    }

    @Test
    fun `the notifications step is skipped where it does not apply or is already granted`() {
        // Android 12 and older, a television, the desktop, and granted: notificationsOff is false.
        val none = Inputs(notificationsOff = false, supportOffered = true)
        assertEquals(Step.Support, OnboardingSteps.next(armed, none))
        assertEquals(listOf(Step.Support), OnboardingSteps.applicable(none))
    }

    @Test
    fun `the support step is left out of a build without support links`() {
        val play = Inputs(notificationsOff = true, supportOffered = false)
        assertEquals(Step.Notifications, OnboardingSteps.next(armed, play))
        assertNull(OnboardingSteps.next(armed.with(Step.Notifications, Outcome.Skipped), play))
        assertNull(OnboardingSteps.next(armed, Inputs()))
    }

    @Test
    fun `a no keeps the notifications step in its settings form until it is skipped or granted`() {
        val denied = armed.with(Step.Notifications, Outcome.Denied)
        assertEquals(Step.Notifications, OnboardingSteps.next(denied, both))
        // Turned on in the system settings: nothing is off any more, on to support.
        assertEquals(Step.Support, OnboardingSteps.next(denied, both.copy(notificationsOff = false)))
        assertEquals(Step.Support, OnboardingSteps.next(denied.with(Step.Notifications, Outcome.Skipped), both))
    }

    @Test
    fun `a granted answer and an opened page are never shown again`() {
        val done = armed.with(Step.Notifications, Outcome.Done).with(Step.Support, Outcome.Opened)
        assertNull(OnboardingSteps.next(done, both))
    }

    @Test
    fun `step positions count the steps this device has`() {
        assertEquals(listOf(Step.Notifications, Step.Support), OnboardingSteps.applicable(both))
        assertEquals(listOf(Step.Notifications), OnboardingSteps.applicable(Inputs(notificationsOff = true)))
    }

    @Test
    fun `the stored text round trips and garbage reads as nothing armed`() {
        val state = armed.with(Step.Notifications, Outcome.Denied).with(Step.Support, Outcome.Opened)
        assertEquals("armed;notifications=denied;support=opened", OnboardingSteps.encode(state))
        assertEquals(state, OnboardingSteps.decode(OnboardingSteps.encode(state)))
        assertNull(OnboardingSteps.encode(State()))
        for (junk in listOf("", ";;;", "armed;;x=y;notifications=maybe", "\u0000", "a".repeat(100_000))) {
            val read = OnboardingSteps.decode(junk)
            // Never throws, and an unknown value shows nothing extra.
            assertEquals(emptyMap<Step, Outcome>(), read.outcomes)
        }
        assertEquals(State(armed = true), OnboardingSteps.decode("armed;notifications=maybe"))
    }

    @Test
    fun `the first run arms the steps once, a replay and an update do not, and outcomes are stored`() = runBlocking {
        val file = Files.createTempDirectory("tm-onboarding").resolve(SettingsStore.FILE_NAME).toFile()
        val store = SettingsStore(SettingsStore.openDataStore(file))
        // An install that was only updated: nothing armed, a recorded outcome changes nothing.
        store.markOnboardingStep(Step.Support, Outcome.Opened)
        assertEquals(State(), store.onboardingSteps.first())

        store.armSetup()
        store.markSetupDone()
        assertEquals(armed, store.onboardingSteps.first())

        store.markOnboardingStep(Step.Notifications, Outcome.Skipped)
        store.markOnboardingStep(Step.Support, Outcome.Opened)
        // A replay from Settings runs the question again and does not arm or reset anything.
        store.replaySetup()
        store.markSetupDone()
        assertEquals(
            armed.with(Step.Notifications, Outcome.Skipped).with(Step.Support, Outcome.Opened),
            store.onboardingSteps.first(),
        )
    }
}
