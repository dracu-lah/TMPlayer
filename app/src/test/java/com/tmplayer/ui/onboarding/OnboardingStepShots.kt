package com.tmplayer.ui.onboarding

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws the first run's notifications and support steps to `design/onboarding/` for the review page
 * there (index.html), one class per form factor. Not a pass or fail check: it has to compose and
 * write the files. Reduced motion is on, so each picture shows the step at rest.
 */
@OptIn(ExperimentalTestApi::class)
abstract class OnboardingShots(private val prefix: String, private val tv: Boolean = false) {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
    }

    private fun shot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        FormFactor.override(tv)
        Motion.reduced = true
        rule.setContent { TMPlayerTheme { content() } }
        rule.waitForIdle()
        save("$prefix-$name.png", rule.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap())
    }

    private fun save(name: String, bitmap: Bitmap) {
        val file = File("../design/onboarding/$name").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun notificationsAsk() {
        if (tv) return
        shot("1-notifications") { NotificationsStepScreen(settingsRoute = false, onAllow = {}, onOpenSettings = {}, onSkip = {}, position = StepOf(1, 2)) }
    }

    @Test
    fun notificationsSettingsRoute() {
        if (tv) return
        shot("2-notifications-settings") { NotificationsStepScreen(settingsRoute = true, onAllow = {}, onOpenSettings = {}, onSkip = {}, position = StepOf(1, 2)) }
    }

    @Test
    fun support() {
        shot("3-support") { SupportStepScreen(onOpenPage = if (tv) null else ({}), onSkip = {}, position = if (tv) StepOf(1, 1) else StepOf(2, 2)) }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xxhdpi")
class OnboardingPhonePortraitShots : OnboardingShots("phone-portrait")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w891dp-h411dp-land-night-xxhdpi")
class OnboardingPhoneLandscapeShots : OnboardingShots("phone-landscape")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h1280dp-port-night-xhdpi")
class OnboardingTabletShots : OnboardingShots("tablet")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class OnboardingTvShots : OnboardingShots("tv", tv = true)
