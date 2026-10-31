package com.tmplayer.ui.onboarding

import com.tmplayer.data.DeviceForm
import com.tmplayer.data.Recommendation
import com.tmplayer.data.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Settings' Setup choices against the setup: the same steps for every form factor, and an answer
 * changed there writes what the setup writes for it.
 */
class SetupChoicesTest {

    @Test
    fun `each form factor lists the steps the setup asks`() {
        val cases = listOf(
            emptyList(),
            listOf(Recommendation.LowStorageMode),
            listOf(Recommendation.LowStorageMode, Recommendation.LighterPictures, Recommendation.ReduceMotion),
        )
        for (recommendations in cases) {
            for (posters in listOf(true, false)) {
                // The arguments the setup's hosts passed before Setup.stepsFor existed.
                val phone = Setup.steps(
                    posters,
                    device = recommendations.isNotEmpty(),
                    devicePerformance = recommendations.any { it != Recommendation.LowStorageMode },
                    phone = true,
                )
                val tv = Setup.steps(
                    posters,
                    device = recommendations.isNotEmpty(),
                    devicePerformance = recommendations.any { it != Recommendation.LowStorageMode },
                    phone = false,
                )
                val desktop = Setup.steps(posters, device = recommendations.isNotEmpty(), devicePerformance = false)
                assertEquals(phone, Setup.stepsFor(DeviceForm.Phone, posters, recommendations))
                assertEquals(tv, Setup.stepsFor(DeviceForm.Tv, posters, recommendations))
                assertEquals(desktop, Setup.stepsFor(DeviceForm.Desktop, posters, recommendations))
            }
        }
        // Mobile data on a phone alone, and the Device step only when something is recommended.
        assertTrue(SetupStep.MobileData in Setup.stepsFor(DeviceForm.Phone, true, emptyList()))
        assertFalse(SetupStep.MobileData in Setup.stepsFor(DeviceForm.Tv, true, emptyList()))
        assertFalse(SetupStep.MobileData in Setup.stepsFor(DeviceForm.Desktop, true, emptyList()))
        assertFalse(SetupStep.Device in Setup.stepsFor(DeviceForm.Phone, true, emptyList()))
        assertTrue(SetupStep.Device in Setup.stepsFor(DeviceForm.Tv, true, listOf(Recommendation.ReduceMotion)))
    }

    @Test
    fun `every answer writes its settings and reads back as chosen`() = runBlocking {
        val settings = store()
        var posters = true
        // Device and Chats are both actions rather than plain settings (chosen() is null for
        // either), so their answers do not read back as "chosen" the way every other one does.
        for (choice in SetupChoice.entries.filter { it.step != SetupStep.Device && it.step != SetupStep.Chats }) {
            choice.applyTo(settings, posters = { posters = it })
            val now = values(settings, posters)
            assertEquals(choice.name, choice, now.chosen(choice.step))
        }
        SetupChoice.Lighter.applyTo(settings, posters = null)
        assertTrue(settings.lighterPictures.first())
        assertTrue(settings.reduceMotion.first())
        assertFalse(settings.trickplay.first())
        SetupChoice.SaveData.applyTo(settings, posters = null)
        assertTrue(settings.saveMobileData.first())
        // Wi-Fi only is left as it was: it would stop streaming on mobile data altogether.
        assertFalse(settings.wifiOnlyDownloads.first())
        settings.setWifiOnlyDownloads(true)
        SetupChoice.SaveData.applyTo(settings, posters = null)
        assertTrue(settings.wifiOnlyDownloads.first())
        SetupChoice.NoLimits.applyTo(settings, posters = null)
        assertFalse(settings.saveMobileData.first())
        assertFalse(settings.wifiOnlyDownloads.first())
    }

    @Test
    fun `Apply turns the recommended settings on, Not now changes nothing`() = runBlocking {
        val settings = store()
        SetupChoice.NotNow.applyTo(settings, posters = null, recommendations = Recommendation.entries)
        assertFalse(settings.lowStorageMode.first())
        SetupChoice.ApplyRecommended.applyTo(settings, posters = null, recommendations = Recommendation.entries)
        assertTrue(settings.lowStorageMode.first())
        assertTrue(settings.lighterPictures.first())
        assertTrue(settings.reduceMotion.first())
        assertFalse(settings.trickplay.first())
        assertEquals(null, values(settings, true).chosen(SetupStep.Device))
    }

    private suspend fun values(settings: SettingsStore, posters: Boolean) = SetupValues(
        posters = posters,
        lowStorage = settings.lowStorageMode.first(),
        lighter = settings.lighterPictures.first(),
        saveData = settings.saveMobileData.first(),
        crashReports = settings.crashReports.first(),
    )

    private fun store(): SettingsStore {
        val file = Files.createTempDirectory("tm-setup-choices").resolve(SettingsStore.FILE_NAME).toFile()
        return SettingsStore(SettingsStore.openDataStore(file))
    }
}
