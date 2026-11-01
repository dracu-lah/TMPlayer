package com.tmplayer.ui.onboarding

import com.tmplayer.data.AuthState
import com.tmplayer.data.SettingsStore
import com.tmplayer.i18n.Languages
import com.tmplayer.i18n.Translator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** The setup after sign in (CP67): who sees it, which steps, and how it is walked. */
class SetupTest {

    @Test
    fun `every step, in order`() {
        assertEquals(
            listOf(SetupStep.Posters, SetupStep.Storage, SetupStep.Performance),
            Setup.steps(posters = true, storage = true),
        )
    }

    @Test
    fun `a build without online lookups has no posters step`() {
        assertEquals(
            listOf(SetupStep.Storage, SetupStep.Performance),
            Setup.steps(posters = false, storage = true),
        )
        assertEquals(listOf(SetupStep.Performance), Setup.steps(posters = false, storage = false))
    }

    @Test
    fun `a low-end device gets the Device step in Storage's place, never both`() {
        assertEquals(
            listOf(SetupStep.Posters, SetupStep.Device),
            Setup.steps(posters = true, storage = true, device = true),
        )
        assertEquals(listOf(SetupStep.Device), Setup.steps(posters = false, storage = false, device = true))
        assertFalse(SetupStep.Device in Setup.steps(posters = true, device = false))
    }

    @Test
    fun `a Device step with only Low storage mode to offer leaves Performance to ask`() {
        assertEquals(
            listOf(SetupStep.Posters, SetupStep.Device, SetupStep.Performance),
            Setup.steps(posters = true, device = true, devicePerformance = false),
        )
    }

    @Test
    fun `Mobile data is asked on a phone only, last`() {
        assertEquals(
            listOf(SetupStep.Posters, SetupStep.Storage, SetupStep.Performance, SetupStep.MobileData),
            Setup.steps(posters = true, phone = true),
        )
        assertFalse(SetupStep.MobileData in Setup.steps(posters = true, phone = false))
        assertEquals(SetupStep.MobileData, Setup.steps(posters = false, device = true, phone = true).last())
    }

    @Test
    fun `Performance is pictured with shots, Mobile data is drawn`() {
        assertEquals(listOf(SetupChoice.FullQuality, SetupChoice.Lighter), SetupStep.Performance.choices)
        assertTrue(SetupChoice.FullQuality.screenshot && SetupChoice.Lighter.screenshot)
        assertEquals(listOf(SetupChoice.SaveData, SetupChoice.NoLimits), SetupStep.MobileData.choices)
        assertFalse(SetupChoice.SaveData.screenshot || SetupChoice.NoLimits.screenshot)
    }

    @Test
    fun `Performance starts on what is set, Mobile data on saving`() {
        val lighter = SetupValues(posters = true, lowStorage = false, lighter = true, saveData = false)
        assertEquals(SetupChoice.Lighter, lighter.current(SetupStep.Performance))
        assertEquals(SetupChoice.SaveData, lighter.current(SetupStep.MobileData))
        assertEquals(SetupChoice.FullQuality, lighter.copy(lighter = false).current(SetupStep.Performance))
    }

    @Test
    fun `the Device step starts on Apply, and has no pictures`() {
        assertEquals(listOf(SetupChoice.ApplyRecommended, SetupChoice.NotNow), SetupStep.Device.choices)
        assertEquals(SetupChoice.ApplyRecommended, SetupValues(posters = false, lowStorage = true).current(SetupStep.Device))
        assertFalse(SetupChoice.ApplyRecommended.screenshot)
    }

    @Test
    fun `the switch steps share one page at the end, the pictured ones keep their own`() {
        val phone = listOf(SetupStep.Posters, SetupStep.Storage, SetupStep.Performance, SetupStep.MobileData, SetupStep.CrashReports)
        assertEquals(
            listOf(
                listOf(SetupStep.Posters),
                listOf(SetupStep.Performance),
                listOf(SetupStep.Storage, SetupStep.MobileData, SetupStep.CrashReports),
            ),
            Setup.pages(phone),
        )
        // Device stays on a page of its own like Posters and Performance.
        assertEquals(
            listOf(listOf(SetupStep.Posters), listOf(SetupStep.Device), listOf(SetupStep.MobileData, SetupStep.CrashReports)),
            Setup.pages(listOf(SetupStep.Posters, SetupStep.Device, SetupStep.MobileData, SetupStep.CrashReports)),
        )
        // Two are enough to share.
        assertEquals(
            listOf(listOf(SetupStep.Performance), listOf(SetupStep.Storage, SetupStep.CrashReports)),
            Setup.pages(listOf(SetupStep.Storage, SetupStep.Performance, SetupStep.CrashReports)),
        )
    }

    @Test
    fun `a lone switch step keeps its own page where it was`() {
        val steps = listOf(SetupStep.Posters, SetupStep.Storage, SetupStep.Performance)
        assertEquals(steps.map { listOf(it) }, Setup.pages(steps))
        assertEquals(listOf(listOf(SetupStep.CrashReports)), Setup.pages(listOf(SetupStep.CrashReports)))
        assertEquals(emptyList<List<SetupStep>>(), Setup.pages(emptyList()))
    }

    @Test
    fun `every platform's setup is three pages after Appearance`() {
        fun pages(form: com.tmplayer.data.DeviceForm, crash: Boolean, recommendations: List<com.tmplayer.data.Recommendation> = emptyList()) =
            Setup.pages(Setup.withCrashReports(Setup.stepsFor(form, posters = true, recommendations = recommendations), crash))
        val lighter = listOf(com.tmplayer.data.Recommendation.entries.first { it != com.tmplayer.data.Recommendation.LowStorageMode })
        // Phone: Posters, Performance, then Storage, Mobile data and Crash reports together.
        assertEquals(3, pages(com.tmplayer.data.DeviceForm.Phone, crash = true).size)
        // Phone short of memory: Posters, Device, then Mobile data and Crash reports together.
        assertEquals(
            listOf(listOf(SetupStep.Posters), listOf(SetupStep.Device), listOf(SetupStep.MobileData, SetupStep.CrashReports)),
            pages(com.tmplayer.data.DeviceForm.Phone, crash = true, recommendations = lighter),
        )
        // Television: Posters, Performance, then Storage and Crash reports together.
        assertEquals(3, pages(com.tmplayer.data.DeviceForm.Tv, crash = true).size)
        // Desktop with or without crash reports.
        assertEquals(3, pages(com.tmplayer.data.DeviceForm.Desktop, crash = false).size)
        assertEquals(3, pages(com.tmplayer.data.DeviceForm.Desktop, crash = true).size)
    }

    @Test
    fun `a paged setup walks by page, and its steps are every page's`() {
        val setup = SetupState.paged(Setup.pages(listOf(SetupStep.Posters, SetupStep.Storage, SetupStep.Performance, SetupStep.MobileData)))
        assertEquals(listOf(SetupStep.Posters), setup.page)
        assertTrue(setup.next())
        assertEquals(SetupStep.Performance, setup.step)
        assertTrue(setup.next())
        assertEquals(listOf(SetupStep.Storage, SetupStep.MobileData), setup.page)
        assertTrue(setup.isLast)
        assertFalse(setup.next())
        assertEquals(listOf(SetupStep.Posters, SetupStep.Performance, SetupStep.Storage, SetupStep.MobileData), setup.steps)
        // Built from steps, every step is a page of its own, as before.
        assertEquals(4, SetupState(listOf(SetupStep.Posters, SetupStep.Storage, SetupStep.Performance, SetupStep.MobileData)).pages.size)
    }

    @Test
    fun `the storage step is on for every platform`() {
        assertTrue(Setup.STORAGE_STEP)
        assertTrue(SetupStep.Storage in Setup.steps(posters = false))
    }

    @Test
    fun `it shows only when armed, and waits for the chat list or the time limit`() {
        assertEquals(Setup.Decision.None, Setup.decide(pending = false, chatsLoaded = true))
        assertEquals(Setup.Decision.None, Setup.decide(pending = false, chatsLoaded = false, waitedOut = true))
        assertEquals(Setup.Decision.Wait, Setup.decide(pending = true, chatsLoaded = false))
        assertEquals(Setup.Decision.Show, Setup.decide(pending = true, chatsLoaded = true))
        assertEquals(Setup.Decision.Show, Setup.decide(pending = true, chatsLoaded = false, waitedOut = true))
    }

    @Test
    fun `the sign in screens arm it, nothing else does`() {
        assertTrue(Setup.isSignInStep(AuthState.ChooseMethod))
        assertTrue(Setup.isSignInStep(AuthState.Phone()))
        assertTrue(Setup.isSignInStep(AuthState.Qr("tg://login")))
        assertFalse(Setup.isSignInStep(AuthState.Ready))
        assertFalse(Setup.isSignInStep(AuthState.Connecting))
    }

    @Test
    fun `who sees it`() = runBlocking {
        // Somebody signed in before the setup existed, who went through the old tour: never asked.
        val updated = store()
        assertEquals(Setup.Decision.None, Setup.decide(updated.setupPending.first(), chatsLoaded = true))

        // A fresh sign in: the sign in screen arms it, and it shows once the chats are in.
        val fresh = store()
        fresh.armSetup()
        assertEquals(Setup.Decision.Show, Setup.decide(fresh.setupPending.first(), chatsLoaded = true))
        // Skipped or finished: gone, and an app restart does not bring it back.
        fresh.markSetupDone()
        assertEquals(Setup.Decision.None, Setup.decide(fresh.setupPending.first(), chatsLoaded = true))

        // Signed out and in again: asked again.
        fresh.clearEverything()
        fresh.armSetup()
        assertEquals(Setup.Decision.Show, Setup.decide(fresh.setupPending.first(), chatsLoaded = true))

        // Settings' "Run the setup again".
        updated.replaySetup()
        assertEquals(Setup.Decision.Show, Setup.decide(updated.setupPending.first(), chatsLoaded = true))
    }

    @Test
    fun `Skip and picking walk on and finish after the last step`() {
        val setup = SetupState(listOf(SetupStep.Posters, SetupStep.Storage, SetupStep.Performance))
        assertEquals(SetupStep.Posters, setup.step)
        assertTrue(setup.isFirst)
        assertTrue(setup.canSkipAll)
        assertTrue(setup.next())
        assertEquals(SetupStep.Storage, setup.step)
        assertTrue(setup.next())
        assertEquals(SetupStep.Performance, setup.step)
        assertTrue(setup.isLast)
        assertFalse("Skip all says no more than Skip on the last step", setup.canSkipAll)
        assertFalse("done", setup.next())
        assertEquals(SetupStep.Performance, setup.step)
    }

    @Test
    fun `Back goes to the step before, and leaves from the first`() {
        val setup = SetupState(listOf(SetupStep.Posters, SetupStep.Storage), start = 1)
        assertTrue(setup.back())
        assertEquals(SetupStep.Posters, setup.step)
        assertFalse(setup.back())
        assertEquals(SetupStep.Posters, setup.step)
    }

    @Test
    fun `a single step is first and last at once`() {
        val setup = SetupState(listOf(SetupStep.Storage))
        assertTrue(setup.isFirst && setup.isLast)
        assertFalse(setup.canSkipAll)
        assertFalse(setup.next())
    }

    @Test
    fun `a start out of range is held to the steps`() {
        assertEquals(SetupStep.Storage, SetupState(listOf(SetupStep.Posters, SetupStep.Storage), start = 9).step)
    }

    @Test
    fun `each step has two answers, the recommended one first and every setting's default`() {
        for (step in SetupStep.entries) {
            assertEquals(2, step.choices.size)
            if (step == SetupStep.CrashReports) continue
            assertTrue(step.choices.first().recommended)
            assertFalse(step.choices.last().recommended)
        }
        val defaults = SetupValues(posters = true, lowStorage = false)
        for (step in SetupStep.entries) assertEquals(step.choices.first(), defaults.current(step))
    }

    @Test
    fun `the crash report question is asked only where the build can send a report`() {
        val steps = Setup.stepsFor(com.tmplayer.data.DeviceForm.Phone, posters = true, recommendations = emptyList())
        assertFalse(SetupStep.CrashReports in steps)
        assertEquals(steps, Setup.withCrashReports(steps, available = false))
        assertEquals(steps, Setup.withCrashReports(steps, CrashReporting.Unavailable.available))
        val asked = Setup.withCrashReports(steps, available = true)
        assertEquals(steps + SetupStep.CrashReports, asked)
        // Last of the preferences, and only once.
        assertEquals(asked, Setup.withCrashReports(asked, available = true))
        // Settings' Setup choices never list it, on any device: Settings has its own switch.
        for (form in com.tmplayer.data.DeviceForm.entries) {
            assertFalse(SetupStep.CrashReports in Setup.stepsFor(form, true, com.tmplayer.data.Recommendation.entries))
        }
    }

    @Test
    fun `crash reports start off and no answer is suggested`() {
        assertEquals(SetupChoice.CrashReportsOff, SetupValues(posters = true, lowStorage = false).current(SetupStep.CrashReports))
        assertEquals(SetupChoice.CrashReportsOff, SetupValues(posters = true, lowStorage = false).chosen(SetupStep.CrashReports))
        assertTrue(SetupStep.CrashReports.choices.none { it.recommended })
        assertTrue(SetupStep.CrashReports.choices.none { it.screenshot })
        // Run again from Settings with the switch on, the question shows it on.
        val on = SetupValues(posters = true, lowStorage = false, crashReports = true)
        assertEquals(SetupChoice.CrashReportsOn, on.current(SetupStep.CrashReports))
        assertFalse(store().let { runBlocking { it.crashReportsNow() } })
    }

    @Test
    fun `yes sets Settings' crash report switch and starts reporting, no turns both off`() = runBlocking {
        val settings = store()
        val calls = mutableListOf<Boolean>()
        SetupChoice.CrashReportsOn.applyTo(settings, posters = null, crashReports = { calls += it })
        assertTrue(settings.crashReports.first())
        assertTrue(settings.crashReportsNow())
        SetupChoice.CrashReportsOff.applyTo(settings, posters = null, crashReports = { calls += it })
        assertFalse(settings.crashReportsNow())
        assertEquals(listOf(true, false), calls)
        // No other answer touches it.
        SetupChoice.CrashReportsOn.applyTo(settings, posters = null)
        for (choice in SetupChoice.entries.filter { it.step != SetupStep.CrashReports }) {
            choice.applyTo(settings, posters = null, crashReports = { calls += it })
        }
        assertTrue(settings.crashReportsNow())
        assertEquals(listOf(true, false), calls)
    }

    @Test
    fun `the answer a remote starts on is the one set now`() {
        val set = SetupValues(posters = false, lowStorage = true)
        assertEquals(SetupChoice.PostersOff, set.current(SetupStep.Posters))
        assertEquals(SetupChoice.LowStorage, set.current(SetupStep.Storage))
    }

    @Test
    fun `screenshots for posters and performance, drawings for storage and mobile data`() {
        assertEquals(
            setOf(
                SetupChoice.PostersOn, SetupChoice.PostersOff,
                SetupChoice.FullQuality, SetupChoice.Lighter,
            ),
            SetupChoice.entries.filter { it.screenshot }.toSet(),
        )
    }

    @Test
    fun `every locale words every step and answer, none of them left in English`() {
        val en = Translator.english()
        for (language in Languages.all) {
            val s = Translator.load(language.tag)
            for (step in SetupStep.entries) {
                val copy = s.setupCopy(step)
                assertTrue(copy.title.isNotBlank() && copy.body.isNotBlank())
                if (language.tag != Languages.ENGLISH) {
                    assertNotEquals("${language.tag} $step", en.setupCopy(step).body, copy.body)
                }
                for (choice in step.choices) {
                    val words = s.choiceCopy(choice)
                    assertTrue(words.title.isNotBlank() && words.detail.isNotBlank())
                    if (language.tag != Languages.ENGLISH) {
                        assertNotEquals("${language.tag} $choice", en.choiceCopy(choice).detail, words.detail)
                    }
                }
            }
            assertNotEquals(language.tag, s.setupStep(1, 3), s.setupStep(2, 3))
        }
    }

    @Test
    fun `the language picker lists the system first, then every language by its own name`() {
        val choices = Onboarding.languages()
        assertEquals("", choices.first().tag)
        assertNull(choices.first().name)
        assertEquals(Languages.all.map { it.tag }, choices.drop(1).map { it.tag })
        assertEquals("Español (Latinoamérica)", choices.first { it.tag == "es-419" }.name)
    }

    private fun store(): SettingsStore {
        val file = Files.createTempDirectory("tm-setup").resolve(SettingsStore.FILE_NAME).toFile()
        return SettingsStore(SettingsStore.openDataStore(file))
    }
}
