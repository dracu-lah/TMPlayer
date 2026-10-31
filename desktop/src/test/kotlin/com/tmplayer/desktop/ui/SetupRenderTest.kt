package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.ThemeChoice
import com.tmplayer.data.UiScale
import com.tmplayer.data.UpdateScheduler
import com.tmplayer.data.WatchedStore
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.DesktopWatchCache
import com.tmplayer.i18n.Languages
import com.tmplayer.i18n.Translator
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.ui.nav.BackStack
import com.tmplayer.ui.nav.LocalBackStack
import com.tmplayer.ui.onboarding.AppearanceStep
import com.tmplayer.ui.onboarding.CrashReporting
import com.tmplayer.ui.onboarding.SetupScreen
import com.tmplayer.ui.onboarding.SetupAndTour
import com.tmplayer.ui.onboarding.SetupState
import com.tmplayer.ui.onboarding.Tour
import com.tmplayer.ui.onboarding.TourProgress
import com.tmplayer.ui.onboarding.TourScreen
import com.tmplayer.ui.onboarding.SetupStep
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The setup after sign in (CP67) and the first-run Home on the desktop: every step light and dark,
 * in Arabic (right to left) and the pseudo-locale, then walked from the keyboard alone. With
 * TMPLAYER_SHOTS=<dir> the frames are written there as PNGs.
 */
@OptIn(ExperimentalTestApi::class)
class SetupRenderTest {

    private val settings = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-setup-render").resolve(SettingsStore.FILE_NAME).toFile()),
    )
    private val dir = Files.createTempDirectory("tm-setup-render-extras").toFile()
    private val shell = ShellState(
        settings,
        object : DownloadRunner {
            override fun download(request: DownloadRequest) = Unit
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        },
        WatchedStore(WatchedStore.openDataStore(Files.createTempDirectory("tm-setup-watched").resolve(WatchedStore.FILE_NAME).toFile())),
        services = {
            DesktopExtras(
                prefs = DesktopPrefs(dir.resolve("desktop.properties")),
                updates = UpdateScheduler(settings.updatePrefs, check = { _, _ -> false }, onSkip = {}),
                watchCache = DesktopWatchCache(settings, { dir.resolve("cache") }),
            )
        },
    )

    // What a device that is fine gets: Device takes Storage's place only on a low-end one.
    private val all = SetupStep.entries.filter { it != SetupStep.Device }

    @After
    fun reset() {
        com.tmplayer.data.DeviceFitOverride.forced = null
        OnlineMetadata.current = null
        Translator.use(Languages.ENGLISH)
    }

    @Test
    fun everyStepLightAndDark() {
        MetaFixture.install("on")
        for (step in all) {
            for (dark in listOf(true, false)) {
                save("setup-${step.name.lowercase()}-${if (dark) "dark" else "light"}.png", render(dark) {
                    SetupScreen(settings, remember { SetupState(all, start = step.ordinal) }, onDone = {})
                })
            }
        }
        for (dark in listOf(true, false)) {
            save("setup-appearance-${if (dark) "dark" else "light"}.png", render(dark) {
                AppearanceStep(settings, onNext = {}, onSkipAll = {}, groups = listOf(1, all.size, 6))
            })
        }
        save("setup-appearance-narrow.png", render(true, width = 520, height = 900) {
            AppearanceStep(settings, onNext = {}, onSkipAll = {})
        })
        // A narrow window stacks the words over the answers, as a phone does.
        save("setup-narrow.png", render(true, width = 520, height = 900) {
            SetupScreen(settings, remember { SetupState(all) }, onDone = {})
        })
    }

    @Test
    fun rightToLeftAndPseudo() {
        MetaFixture.install("on")
        Translator.use("ar")
        for (step in all) {
            save("setup-ar-${step.name.lowercase()}.png", render(true, rtl = true) {
                SetupScreen(settings, remember { SetupState(all, start = step.ordinal) }, onDone = {})
            })
        }
        Translator.use(Languages.PSEUDO)
        for (step in all) {
            save("setup-xa-${step.name.lowercase()}.png", render(true) {
                SetupScreen(settings, remember { SetupState(all, start = step.ordinal) }, onDone = {})
            })
        }
    }

    /**
     * Each step opens with the answer set now marked and the keyboard on Next. A click on an
     * answer only marks it; Next applies what is marked and moves on, Next on an untouched step
     * changes nothing, Back walks back, and Done on the last step finishes.
     */
    @Test
    fun walkedFromTheKeyboard() = runComposeUiTest {
        val online = MetaFixture.install("on")
        val back = BackStack()
        val setup = SetupState(listOf(SetupStep.Posters, SetupStep.Storage, SetupStep.Performance))
        var done = false
        setContent {
            CompositionLocalProvider(LocalBackStack provides back) {
                TmMaterialTheme(dark = true) {
                    Box(Modifier.size(1280.dp, 800.dp)) { SetupScreen(settings, setup, onDone = { done = true }) }
                }
            }
        }
        fun button(label: String) = hasText(label) and hasClickAction()
        fun press(key: Key) = onNode(isFocused()).performKeyInput { pressKey(key) }
        // The first focus is asked for over a few frames (see SetupScreen), so it is waited for.
        fun awaitFocus(node: androidx.compose.ui.test.SemanticsMatcher) {
            waitUntil(timeoutMillis = WRITE_MS) { onAllNodes(node and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        }

        waitForIdle()
        awaitFocus(button("Next"))
        // Marking an answer changes nothing yet; Next writes it and moves on.
        onNode(button("Posters off")).performClick()
        waitForIdle()
        assertEquals(SetupStep.Posters, setup.step)
        assertTrue(online.settings.value.enabled)
        awaitFocus(button("Next"))
        press(Key.Enter)
        waitForIdle()
        assertEquals(SetupStep.Storage, setup.step)
        waitUntil(timeoutMillis = WRITE_MS) { !online.settings.value.enabled }

        // Back returns to the posters step, with the answer now set marked.
        back.handleBack()
        waitForIdle()
        assertEquals(SetupStep.Posters, setup.step)
        onNode(button("Posters off")).assertIsSelected()
        onNode(button("Posters on")).performClick()
        onNode(button("Next")).performClick()
        waitForIdle()
        waitUntil(timeoutMillis = WRITE_MS) { online.settings.value.enabled }

        // Next on an untouched step keeps it as it is.
        assertEquals(SetupStep.Storage, setup.step)
        onNode(button("Next")).performClick()
        waitForIdle()
        assertEquals(SetupStep.Performance, setup.step)
        assertFalse(runBlocking { settings.lowStorageMode.first() })
        onNode(button("Done")).performClick()
        waitForIdle()
        assertTrue("finished after the last step", done)
    }

    /**
     * CP68: a disk short of room. The Device step in Storage's place (light, dark, Arabic), the
     * Settings card, and from the keyboard: focus on Apply, Enter turns Low storage mode on.
     */
    @Test
    fun deviceStepAndCard() {
        val fit = com.tmplayer.data.DeviceFit(shortOfMemory = false, shortOfStorage = true)
        val all = com.tmplayer.data.Recommendation.entries.toList()
        val steps = listOf(SetupStep.Posters, SetupStep.Device)
        for (dark in listOf(true, false)) {
            save("setup-device-${if (dark) "dark" else "light"}.png", render(dark) {
                SetupScreen(settings, remember { SetupState(steps, start = 1) }, onDone = {}, fit = fit, recommendations = all)
            })
        }
        save("setup-device-narrow.png", render(true, width = 520, height = 900) {
            SetupScreen(settings, remember { SetupState(steps, start = 1) }, onDone = {}, fit = fit, recommendations = all)
        })
        Translator.use("ar")
        save("setup-device-ar.png", render(true, rtl = true) {
            SetupScreen(
                settings,
                remember { SetupState(steps, start = 1) },
                onDone = {},
                fit = com.tmplayer.data.DeviceFit(true, true),
                recommendations = all,
            )
        })
        Translator.use(Languages.ENGLISH)
        save("settings-device-card.png", render(true) {
            com.tmplayer.ui.onboarding.DeviceFitCard(fit, all, onApply = {}, onDismiss = {})
        })
        // The wired card on the real Settings page, with the verdict forced.
        com.tmplayer.data.DeviceFitOverride.forced = fit
        save("settings-with-device-card.png", render(true) { SettingsPage(shell, "2.0.0") })
    }

    @Test
    fun deviceStepFromTheKeyboard() = runComposeUiTest {
        val fit = com.tmplayer.data.DeviceFit(shortOfMemory = false, shortOfStorage = true)
        val setup = SetupState(listOf(SetupStep.Device))
        var done = false
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(1280.dp, 800.dp)) {
                    SetupScreen(
                        settings,
                        setup,
                        onDone = { done = true },
                        fit = fit,
                        recommendations = listOf(com.tmplayer.data.Recommendation.LowStorageMode),
                    )
                }
            }
        }
        // Apply is marked, the recommended answer, and the keyboard starts on Done.
        onNode(hasText("Apply") and hasClickAction()).assertIsSelected()
        waitUntil(timeoutMillis = WRITE_MS) {
            onAllNodes(hasText("Done") and hasClickAction() and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        onNode(isFocused()).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertTrue(done)
        waitUntil(timeoutMillis = WRITE_MS) { runBlocking { settings.lowStorageMode.first() } }
    }

    @Test
    fun skipAllEndsItAndChangesNothing() = runComposeUiTest {
        val online = MetaFixture.install("on")
        var done = false
        setContent {
            TmMaterialTheme(dark = false) {
                Box(Modifier.size(1280.dp, 800.dp)) { SetupScreen(settings, remember { SetupState(all) }, onDone = { done = true }) }
            }
        }
        onNode(hasText("Skip all") and hasClickAction()).performClick()
        waitForIdle()
        assertTrue(done)
        assertFalse(runBlocking { settings.lowStorageMode.first() })
        assertTrue(online.settings.value.enabled)
    }

    @Test
    fun postersOffFromTheSetupSticks() = runComposeUiTest {
        val online = MetaFixture.install("on")
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(1280.dp, 800.dp)) {
                    SetupScreen(settings, remember { SetupState(listOf(SetupStep.Posters, SetupStep.Storage)) }, onDone = {})
                }
            }
        }
        onNode(hasText("Posters off") and hasClickAction()).performClick()
        onNode(hasText("Next") and hasClickAction()).performClick()
        waitForIdle()
        waitUntil(timeoutMillis = WRITE_MS) { !online.settings.value.enabled }
        assertTrue("chosen, not a stale default", online.settings.value.offByChoice)
    }

    /**
     * HX28's Chats step. "Browse everything" writes an explicit answer (AddedMode.All) rather than
     * leaving it unanswered, even though nothing changed. "Choose chats" opens the real picker at
     * once rather than waiting for Next; the picker's own close (Done or an unchanged Back, both
     * resolved either way) moves the setup on to the next step, exactly like skipping it would.
     */
    @Test
    fun chatsStepBrowseAllOrOpensThePicker() = runComposeUiTest {
        MetaFixture.install("on")
        var pickerShown = false
        var done = false
        var pickerClose: (() -> Unit)? = null
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(1280.dp, 800.dp)) {
                    SetupScreen(
                        settings,
                        remember { SetupState(listOf(SetupStep.Chats)) },
                        onDone = { done = true },
                        chatsPicker = { onClose ->
                            pickerShown = true
                            pickerClose = onClose
                        },
                    )
                }
            }
        }
        // A single-step setup's primary button says Done, not Next.
        onNode(hasText("Done") and hasClickAction()).performClick()
        waitForIdle()
        waitUntil(timeoutMillis = WRITE_MS) {
            runBlocking { settings.addedChats.first().mode == com.tmplayer.data.AddedMode.All }
        }
        assertFalse("the picker never opened for Browse everything", pickerShown)
        assertTrue("last step: Next already finished it", done)
    }

    @Test
    fun chooseChatsOpensThePickerAtOnce() = runComposeUiTest {
        MetaFixture.install("on")
        var pickerShown = false
        var done = false
        var pickerClose: (() -> Unit)? = null
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(1280.dp, 800.dp)) {
                    SetupScreen(
                        settings,
                        remember { SetupState(listOf(SetupStep.Chats)) },
                        onDone = { done = true },
                        chatsPicker = { onClose ->
                            pickerShown = true
                            pickerClose = onClose
                        },
                    )
                }
            }
        }
        onNode(hasText("Choose chats") and hasClickAction()).performClick()
        waitForIdle()
        assertTrue("tapping it opens the real picker, not a wait for Next", pickerShown)
        assertFalse("the picker, not the two-answer card, decides when this step is done", done)
        // The picker closing (Done, or an unchanged Back) resolves the step either way, same as
        // skipping it: nothing left half set, and the setup moves on by itself.
        pickerClose?.invoke()
        waitForIdle()
        assertTrue("the setup moved on once the picker was done with it", done)
    }

    /** CP69: Lighter turns the three lighter rows on together, Save mobile data turns on both its settings. */
    @Test
    fun performanceAndMobileDataApply() = runComposeUiTest {
        MetaFixture.install("on")
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(1280.dp, 800.dp)) {
                    SetupScreen(settings, remember { SetupState(listOf(SetupStep.Performance, SetupStep.MobileData)) }, onDone = {})
                }
            }
        }
        onNode(hasText("Lighter") and hasClickAction()).performClick()
        onNode(hasText("Next") and hasClickAction()).performClick()
        waitForIdle()
        // Three separate writes, which land one after another (slowest on Windows), so all are awaited.
        waitUntil(timeoutMillis = WRITE_MS) {
            runBlocking { settings.lighterPictures.first() && settings.reduceMotion.first() && !settings.trickplay.first() }
        }
        // Saving is marked as the suggestion although the settings start off, so Done writes it.
        onNode(hasText("Save mobile data") and hasClickAction()).assertIsSelected()
        onNode(hasText("Done") and hasClickAction()).performClick()
        waitForIdle()
        waitUntil(timeoutMillis = WRITE_MS) { runBlocking { settings.saveMobileData.first() } }
        // Wi-Fi only is not part of the answer: it would stop streaming on mobile data altogether.
        assertFalse(runBlocking { settings.wifiOnlyDownloads.first() })
    }

    /**
     * CP102f: the crash report question opens off with nothing suggested. Pressing the switch only
     * marks it; Done writes Settings' "Send crash reports" and starts reporting.
     */
    @Test
    fun crashReportsTurnedOn() = runComposeUiTest {
        var started: Boolean? = null
        var done = false
        val setup = SetupState(listOf(SetupStep.Storage, SetupStep.CrashReports), start = 1)
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(1280.dp, 800.dp)) {
                    SetupScreen(settings, setup, onDone = { done = true }, crashReporting = CrashReporting(true) { started = it })
                }
            }
        }
        val row = hasText("Send crash reports") and hasClickAction()
        onNode(hasText("Crash reports")).assertExists()
        onNode(row).assertIsNotSelected()
        onNode(hasText("Recommended")).assertDoesNotExist()
        onNode(hasText("Read the privacy policy") and hasClickAction()).assertExists()
        onNode(row).performClick()
        waitForIdle()
        onNode(row).assertIsSelected()
        assertFalse("marked, not yet written", runBlocking { settings.crashReportsNow() })
        onNode(hasText("Done") and hasClickAction()).performClick()
        waitForIdle()
        assertTrue(done)
        waitUntil(timeoutMillis = WRITE_MS) { runBlocking { settings.crashReportsNow() } && started == true }
    }

    /** CP102f: Done on the untouched question sends nothing and starts nothing. */
    @Test
    fun crashReportsLeftOff() = runComposeUiTest {
        var started: Boolean? = null
        var done = false
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(1280.dp, 800.dp)) {
                    SetupScreen(
                        settings,
                        remember { SetupState(listOf(SetupStep.CrashReports)) },
                        onDone = { done = true },
                        crashReporting = CrashReporting(true) { started = it },
                    )
                }
            }
        }
        onNode(hasText("Done") and hasClickAction()).performClick()
        waitForIdle()
        assertTrue(done)
        assertFalse(runBlocking { settings.crashReportsNow() })
        assertEquals(null, started)
    }

    /**
     * CP102f: the whole flow asks the question last among the preferences when the build can
     * report, and never when it cannot (the desktop today).
     */
    @Test
    fun crashReportsOnlyWhereAvailable() = runComposeUiTest {
        var available by mutableStateOf(true)
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(1280.dp, 800.dp)) {
                    key(available) {
                        SetupAndTour(
                            settings,
                            listOf(SetupStep.Storage),
                            onDone = {},
                            crashReporting = if (available) CrashReporting(true) else CrashReporting.Unavailable,
                        )
                    }
                }
            }
        }
        onNode(hasText("Next") and hasClickAction()).performClick()
        waitForIdle()
        onNode(hasText("Keep whole videos") and hasClickAction()).assertExists()
        onNode(hasText("Next") and hasClickAction()).performClick()
        waitForIdle()
        onNode(hasText("Send crash reports") and hasClickAction()).assertIsNotSelected()
        onNode(hasText("Next") and hasClickAction()).performClick()
        waitForIdle()
        onNode(hasText("Quick tour")).assertExists()

        available = false
        waitForIdle()
        onNode(hasText("Next") and hasClickAction()).performClick()
        waitForIdle()
        onNode(hasText("Next") and hasClickAction()).performClick()
        waitForIdle()
        onNode(hasText("Quick tour")).assertExists()
        onNode(hasText("Send crash reports")).assertDoesNotExist()
    }

    /** CP69: every tour page light and dark, in Arabic and the pseudo-locale, and narrow. */
    @Test
    fun tourEveryPage() {
        val pages = Tour.pages(com.tmplayer.data.DeviceForm.Desktop)
        for ((i, page) in pages.withIndex()) {
            for (dark in listOf(true, false)) {
                save("tour-${page.imageName}-${if (dark) "dark" else "light"}.png", render(dark) {
                    TourScreen(remember { TourProgress(pages, start = i) }, onDone = {}, before = 5, groups = listOf(1, 4, pages.size))
                })
            }
        }
        Translator.use("ar")
        save("tour-ar-videos.png", render(true, rtl = true) { TourScreen(remember { TourProgress(pages) }, onDone = {}) })
        Translator.use(Languages.PSEUDO)
        save("tour-xa-settings.png", render(true) { TourScreen(remember { TourProgress(pages, start = pages.lastIndex) }, onDone = {}) })
        Translator.use(Languages.ENGLISH)
        save("tour-narrow.png", render(true, width = 520, height = 900) { TourScreen(remember { TourProgress(pages) }, onDone = {}) })
    }

    /** CP69: from the keyboard the tour starts on Next, Enter walks it, Done ends it. */
    @Test
    fun tourFromTheKeyboard() = runComposeUiTest {
        val pages = Tour.pages(com.tmplayer.data.DeviceForm.Desktop)
        val tour = TourProgress(pages)
        var done = false
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(1280.dp, 800.dp)) { TourScreen(tour, onDone = { done = true }) }
            }
        }
        for (i in pages.indices) {
            val label = if (i == pages.lastIndex) "Done" else "Next"
            waitUntil(timeoutMillis = WRITE_MS) {
                onAllNodes(hasText(label) and hasClickAction() and isFocused()).fetchSemanticsNodes().isNotEmpty()
            }
            onNode(isFocused()).performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
        }
        assertTrue(done)
    }

    /**
     * One flow: Appearance, then the preferences, then the tour. Back on the tour's first page
     * returns to the last preference, and Skip all from the tour ends everything.
     */
    @Test
    fun setupThenTour() = runComposeUiTest {
        MetaFixture.install("on")
        var done = false
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(1280.dp, 800.dp)) {
                    SetupAndTour(settings, listOf(SetupStep.Storage), onDone = { done = true })
                }
            }
        }
        onNode(hasText("Theme and size")).assertExists()
        onNode(hasText("Next") and hasClickAction()).performClick()
        waitForIdle()
        onNode(hasText("Keep whole videos") and hasClickAction()).assertExists()
        onNode(hasText("Next") and hasClickAction()).performClick()
        waitForIdle()
        onNode(hasText("Quick tour")).assertExists()
        onNode(hasText("Back") and hasClickAction()).performClick()
        waitForIdle()
        onNode(hasText("Keep whole videos") and hasClickAction()).assertExists()
        onNode(hasText("Back") and hasClickAction()).performClick()
        waitForIdle()
        onNode(hasText("Theme and size")).assertExists()
        onNode(hasText("Next") and hasClickAction()).performClick()
        waitForIdle()
        onNode(hasText("Next") and hasClickAction()).performClick()
        waitForIdle()
        onNode(hasText("Skip all") and hasClickAction()).performClick()
        waitForIdle()
        assertTrue(done)
    }

    /** The Appearance step writes the theme and the interface size as they are picked. */
    @Test
    fun appearanceAppliesAsPicked() = runComposeUiTest {
        var next = false
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(1280.dp, 800.dp)) {
                    AppearanceStep(settings, onNext = { next = true }, onSkipAll = {})
                }
            }
        }
        onNode(hasText("System") and hasClickAction()).assertIsSelected()
        // Pure black, the switch under the theme: offered under System and Dark, folded away
        // under Light.
        onNode(hasText("Pure black") and hasClickAction()).performClick()
        waitUntil(timeoutMillis = WRITE_MS) { runBlocking { settings.pureBlack.first() } }
        onNode(hasText("Light") and hasClickAction()).performClick()
        waitUntil(timeoutMillis = WRITE_MS) { runBlocking { settings.themeChoice.first() } == ThemeChoice.Light }
        waitForIdle()
        onNode(hasText("Pure black")).assertDoesNotExist()
        onNode(hasText("Dark") and hasClickAction()).performClick()
        waitUntil(timeoutMillis = WRITE_MS) { runBlocking { settings.themeChoice.first() } == ThemeChoice.Dark }
        waitForIdle()
        onNode(hasText("Pure black") and hasClickAction()).assertIsSelected()
        onNode(hasText("Largest") and hasClickAction()).performClick()
        waitUntil(timeoutMillis = WRITE_MS) { runBlocking { settings.uiScale.first() } == UiScale.Largest }
        onNode(hasText("Next") and hasClickAction()).performClick()
        assertTrue(next)
    }

    @Test
    fun firstRunHome() {
        shell.go(Destination.Home)
        for (dark in listOf(true, false)) {
            save("home-first-run-${if (dark) "dark" else "light"}.png", render(dark) {
                HomeRowsView(shell, emptyList(), emptyList(), emptyMap(), onRowShown = {}, onArtWanted = {}, onRefresh = {})
            })
        }
    }

    private fun render(
        dark: Boolean,
        width: Int = WIDTH,
        height: Int = HEIGHT,
        rtl: Boolean = false,
        page: @Composable () -> Unit,
    ): ByteArray =
        ImageComposeScene(width, height, Density(1f)) {
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                TmMaterialTheme(dark = dark) {
                    Surface(Modifier.fillMaxSize(), color = Tone.background) { page() }
                }
            }
        }.use { scene ->
            scene.render(0)
            Thread.sleep(300)
            scene.render(500_000_000)
            scene.render(1_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }

    private fun save(name: String, png: ByteArray) {
        assertTrue(png.size > 1000)
        val out = System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        File(out).apply { mkdirs() }.resolve(name).writeBytes(png)
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 800

        /** How long a choice has to reach the settings file, which is written off the UI thread. */
        const val WRITE_MS = 3_000L
    }
}
