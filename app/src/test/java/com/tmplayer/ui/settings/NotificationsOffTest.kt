package com.tmplayer.ui.settings

import android.Manifest
import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.tmplayer.data.NotificationAsk
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.browse.withBlockedLine
import com.tmplayer.ui.theme.TMPlayerTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * WP-38: after a refusal the Notifications page says so and opens the system's page; the question is
 * asked only while the system will still show it, and its "asked" mark follows the answer.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class NotificationsOffTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val drain = DrainMainLooperRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        NotificationAsk.launcher = null
        app.getSharedPreferences("once", 0).edit().clear().apply()
    }

    @Test
    @Config(sdk = [33])
    fun theCardShowsAndOpensTheAppsNotificationSettings() {
        rule.setContent { TMPlayerTheme { NotificationsOffCard() } }
        rule.onNodeWithText(L.notifyOffTitle).assertIsDisplayed()
        rule.onNodeWithText(L.notifyOpenSettings).performClick()
        val started: Intent = shadowOf(rule.activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, started.action)
        assertEquals(rule.activity.packageName, started.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    @Config(sdk = [33])
    fun neverAskedWithTheQuestionStillToCome() {
        assertTrue(NotificationAsk.canAsk(rule.activity))
    }

    @Test
    @Config(sdk = [33])
    fun refusedForGoodHasNoQuestionLeft() {
        // Asked and answered no, and Android says no rationale: it shows nothing any more.
        NotificationAsk.answered(rule.activity)
        assertTrue(NotificationAsk.asked(rule.activity))
        assertFalse(NotificationAsk.canAsk(rule.activity))
    }

    @Test
    @Config(sdk = [33])
    fun grantedHasNothingToAsk() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(NotificationAsk.canAsk(rule.activity))
    }

    @Test
    @Config(sdk = [32])
    fun androidBefore13HasNoQuestion() {
        assertFalse(NotificationAsk.canAsk(rule.activity))
    }

    @Test
    @Config(sdk = [33])
    fun theAskedMarkIsWrittenByTheAnswerNotByTheQuestion() {
        var launched = 0
        NotificationAsk.launcher = { launched++ }
        NotificationAsk.request(rule.activity)
        assertEquals(1, launched)
        assertTrue(NotificationAsk.pending)
        assertFalse("not marked before the answer", NotificationAsk.asked(rule.activity))
        NotificationAsk.answered(rule.activity)
        assertFalse(NotificationAsk.pending)
        assertTrue(NotificationAsk.asked(rule.activity))
        // A second ask after a final refusal puts nothing.
        NotificationAsk.request(rule.activity)
        assertEquals(1, launched)
    }

    @Test
    @Config(sdk = [33])
    fun theQueuedLineSaysProgressShowsHereOnlyWhenNothingCanReachThem() {
        // Denied, no question pending: the line is added.
        val blocked = withBlockedLine(rule.activity, "Downloading")
        assertEquals("Downloading\n${L.notifyBlockedCardLine}", blocked)
        // The question still on screen: not yet.
        NotificationAsk.launcher = {}
        NotificationAsk.request(rule.activity)
        assertEquals("Downloading", withBlockedLine(rule.activity, "Downloading"))
        NotificationAsk.answered(rule.activity)
        // Granted: never.
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals("Downloading", withBlockedLine(rule.activity, "Downloading"))
    }

    @Test
    @Config(sdk = [23])
    fun androidBefore13ReadsTheAppSwitchAndTheCardLinkStillOpensSettings() {
        rule.setContent { TMPlayerTheme { NotificationsOffCard() } }
        rule.onNodeWithText(L.notifyOpenSettings).performClick()
        assertNotNull(shadowOf(rule.activity).nextStartedActivity)
    }
}
