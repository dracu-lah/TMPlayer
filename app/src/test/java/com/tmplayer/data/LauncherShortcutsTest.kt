package com.tmplayer.data

import android.content.Context
import android.content.pm.ShortcutManager
import androidx.test.core.app.ApplicationProvider
import com.tmplayer.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WP-42: the launcher's shortcuts open their screens, the last chat is published only while the
 * profile is not hidden, and nothing is published below Android 7.1.
 */
@RunWith(RobolectricTestRunner::class)
class LauncherShortcutsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun published() = context.getSystemService(ShortcutManager::class.java).dynamicShortcuts

    @Test
    fun theRuleIsPure() {
        assertEquals(
            listOf("continue", "search", "downloads", "last_chat"),
            LauncherShortcuts.planIds(hidden = false, lastChatId = 7L, lastChatTitle = "Night Train"),
        )
        // Hidden profile: the chat's name stays off the launcher.
        assertEquals(
            listOf("continue", "search", "downloads"),
            LauncherShortcuts.planIds(hidden = true, lastChatId = 7L, lastChatTitle = "Night Train"),
        )
        // Nothing to name: no chat shortcut.
        assertFalse("last_chat" in LauncherShortcuts.planIds(false, 0L, "x"))
        assertFalse("last_chat" in LauncherShortcuts.planIds(false, 7L, null))
        assertFalse("last_chat" in LauncherShortcuts.planIds(false, 7L, "  "))
    }

    @Test
    @Config(sdk = [30])
    fun eachShortcutOpensItsScreen() {
        LauncherShortcuts.publish(context, 7L, "Night Train", hidden = false)
        val byId = published().associateBy { it.id }
        assertEquals(setOf("continue", "search", "downloads", "last_chat"), byId.keys)
        assertEquals(LauncherShortcuts.OPEN_CONTINUE, byId.getValue("continue").intent!!.getStringExtra(MainActivity.EXTRA_OPEN))
        assertEquals(LauncherShortcuts.OPEN_SEARCH, byId.getValue("search").intent!!.getStringExtra(MainActivity.EXTRA_OPEN))
        assertEquals(MainActivity.OPEN_DOWNLOADS, byId.getValue("downloads").intent!!.getStringExtra(MainActivity.EXTRA_OPEN))
        assertEquals(7L, byId.getValue("last_chat").intent!!.getLongExtra(BackgroundChecks.EXTRA_OPEN_CHAT, 0L))
        assertEquals("Night Train", byId.getValue("last_chat").shortLabel.toString())
        for (shortcut in byId.values) assertEquals(MainActivity::class.java.name, shortcut.intent!!.component?.className)
    }

    @Test
    @Config(sdk = [30])
    fun locking_a_pin_profile_takes_the_chat_off() {
        LauncherShortcuts.publish(context, 7L, "Night Train", hidden = false)
        assertTrue(published().any { it.id == "last_chat" })
        LauncherShortcuts.publish(context, 7L, "Night Train", hidden = true)
        assertFalse(published().any { it.id == "last_chat" })
        assertEquals(3, published().size)
    }

    private fun pinned() = context.getSystemService(ShortcutManager::class.java).pinnedShortcuts

    @Test
    @Config(sdk = [30])
    fun a_copy_pinned_to_the_home_screen_loses_the_chat_name_SEC2_03() {
        val manager = context.getSystemService(ShortcutManager::class.java)
        LauncherShortcuts.publish(context, 7L, "Night Train", hidden = false)
        // The viewer dragged the chat's shortcut to the home screen.
        manager.requestPinShortcut(published().first { it.id == "last_chat" }, null)
        assertEquals("Night Train", pinned().first { it.id == "last_chat" }.shortLabel.toString())

        LauncherShortcuts.removeLastChat(context)
        val left = pinned().first { it.id == "last_chat" }
        assertEquals(com.tmplayer.i18n.L.chatsLastShortcut, left.shortLabel.toString())
        assertEquals(com.tmplayer.i18n.L.chatsLastShortcut, left.longLabel.toString())
        assertFalse(left.intent!!.hasExtra(BackgroundChecks.EXTRA_OPEN_CHAT))
        assertFalse(published().any { it.id == "last_chat" })

    }

    @Test
    @Config(sdk = [30])
    fun publishing_while_hidden_unnames_a_pinned_copy_too_SEC2_03() {
        val manager = context.getSystemService(ShortcutManager::class.java)
        LauncherShortcuts.publish(context, 7L, "Night Train", hidden = false)
        manager.requestPinShortcut(published().first { it.id == "last_chat" }, null)
        LauncherShortcuts.publish(context, 7L, "Night Train", hidden = true)
        val left = pinned().first { it.id == "last_chat" }
        assertEquals(com.tmplayer.i18n.L.chatsLastShortcut, left.shortLabel.toString())
        assertFalse(left.intent!!.hasExtra(BackgroundChecks.EXTRA_OPEN_CHAT))
    }

    @Test
    fun theLastChatStaysOffWheneverAnythingHidesIt() {
        val open = AppLockSettings()
        assertFalse(LauncherShortcuts.hiddenFor(open, profileHidden = false, profileHasPin = false, mayWrite = true))
        // A lock not read yet: hidden, never open.
        assertTrue(LauncherShortcuts.hiddenFor(null, false, false, true))
        // A locked or away profile; a profile with a PIN even unlocked (its chat names are its own).
        assertTrue(LauncherShortcuts.hiddenFor(open, profileHidden = true, profileHasPin = true, mayWrite = true))
        assertTrue(LauncherShortcuts.hiddenFor(open, profileHidden = false, profileHasPin = true, mayWrite = true))
        // The app lock asking when the app opens, and the kids lock, as the widget has it.
        assertTrue(LauncherShortcuts.hiddenFor(AppLockSettings(pinSet = true, onOpen = true), false, false, true))
        assertTrue(LauncherShortcuts.hiddenFor(AppLockSettings(pinSet = true, kids = true), false, false, true))
        // Outside writes refused.
        assertTrue(LauncherShortcuts.hiddenFor(open, false, false, mayWrite = false))
        // A PIN set without "ask when the app opens" hides nothing by itself.
        assertFalse(LauncherShortcuts.hiddenFor(AppLockSettings(pinSet = true), false, false, true))
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { what }
            Thread.sleep(20)
        }
    }

    @Test
    @Config(sdk = [30])
    fun theFollowerTakesTheChatOffWithTheLockOnOpenAndAtProcessStart() {
        val store = SettingsStore(context)
        kotlinx.coroutines.runBlocking {
            store.setPin("1234")
            store.setLockOnOpen(true)
        }
        // An earlier run left the chat on the launcher.
        LauncherShortcuts.publish(context, 7L, "Night Train", hidden = false)
        assertTrue(published().any { it.id == "last_chat" })
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val job = LauncherShortcuts.follow(context, scope)
        waitUntil("the chat shortcut is removed") { published().none { it.id == "last_chat" } }
        // Only the chat: the three generic shortcuts hold no content of the profile.
        assertEquals(setOf("continue", "search", "downloads"), published().map { it.id }.toSet())
        // Cancel and wait: a clear() still running on a Default thread when the next test starts, on a
        // loaded machine, took the next test's pinned copy off before it was read (gate run, 2026-11-26).
        job.cancel()
        kotlinx.coroutines.runBlocking { job.join() }
    }

    @Test
    @Config(sdk = [24])
    fun android7AndOlderPublishNothing() {
        assertFalse(LauncherShortcuts.supported())
        // Publishing there is a no-op (ShortcutManager is new in Android 7.1): it must not throw.
        LauncherShortcuts.publish(context, 7L, "Night Train", hidden = false)
    }
}
