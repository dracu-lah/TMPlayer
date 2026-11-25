package com.tmplayer.ui.profiles

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.tmplayer.data.AppLockSettings
import com.tmplayer.data.ProfileUnlock
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.lock.ProfileFlow
import com.tmplayer.ui.lock.ProfilesGate
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Add account on the Mi TV and the phone (user, 2026-11-25): the press closed the account sheet and
 * drew nothing. The process's [ProfileFlow] is first asked for inside the first composition (a
 * `remember` in MainActivity), so its state was made in that composition's snapshot. The app lock
 * is read on the app's scope a few milliseconds later, before that composition is applied, and the
 * answer was lost when it was: the flow never knew the lock, so Add account waited on an unread
 * lock, fail closed and drawn nothing, for the life of the process.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class ProfileFlowMadeInCompositionTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val drain = DrainMainLooperRule()

    private var kit: ProfileKit? = null

    @Before
    fun setUp() {
        ProfileUnlock.lock()
    }

    @After
    fun tearDown() {
        kit?.close()
        ProfileUnlock.lock()
    }

    /** The shell as MainActivity makes it, the lock's answer in before the first composition is applied; then Add account. */
    private fun pressAddOnAFlowMadeInComposition(): Pair<ProfileKit, ProfileFlow> {
        val k = ProfileKit("Asha Rao").also { kit = it }
        val read = CountDownLatch(1)
        // As on Android: nothing known at once, the store's first answer a moment later.
        k.platform.readable = false
        k.platform.lockFlow = flow {
            emit(AppLockSettings())
            read.countDown()
            awaitCancellation()
        }
        var made: ProfileFlow? = null
        rule.setContent {
            val flow = remember {
                k.flow().also { assertTrue("the store answered", read.await(5, TimeUnit.SECONDS)) }
            }
            made = flow
            ProfilesGate(flow, signInStep = false) { Text("Home") }
        }
        rule.waitForIdle()
        val flow = made!!
        assertTrue("the app lock is known", flow.appLockKnown)
        assertFalse("no PIN set, so Add asks none", flow.appLockPin)
        rule.runOnIdle { flow.add() }
        return k to flow
    }

    private fun waitForTheNewProfile(k: ProfileKit, flow: ProfileFlow) {
        rule.waitUntil(15_000) { k.book.list.value.profiles.size == 2 && !flow.adding }
        assertEquals("never left waiting on a lock it read", ProfileFlow.Stage.Picker, flow.stage)
        assertEquals("the new profile is the open one", k.book.list.value.profiles.last().id, k.book.list.value.activeId)
    }

    @Test
    fun `TV, the app lock read while the composition that made the flow runs is known, and Add account opens the new profile`() {
        val (k, flow) = pressAddOnAFlowMadeInComposition()
        waitForTheNewProfile(k, flow)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-port-xxhdpi")
    fun `phone, the app lock read while the composition that made the flow runs is known, and Add account opens the new profile`() {
        val (k, flow) = pressAddOnAFlowMadeInComposition()
        waitForTheNewProfile(k, flow)
    }
}
