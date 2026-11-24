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

    @Test
    @Config(sdk = [24])
    fun android7AndOlderPublishNothing() {
        assertFalse(LauncherShortcuts.supported())
        // Publishing there is a no-op (ShortcutManager is new in Android 7.1): it must not throw.
        LauncherShortcuts.publish(context, 7L, "Night Train", hidden = false)
    }
}
