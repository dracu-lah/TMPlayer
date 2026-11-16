package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T12: the phone has no in-app mini player. Back from the player stops it and returns to the page
 * that opened it, so nothing of the old dock may be left behind: the player opens in the caller's
 * task (no affinity of its own), the browse shell has no bar hook, and Back ends in `finish()`.
 * Source-level, because the Robolectric tests cannot start the 10,000 line activity.
 */
class BackStopsThePlayerTest {

    private fun module(path: String): File {
        // Gradle runs unit tests from the module directory; an IDE may start from the repository.
        val here = File(path)
        return if (here.exists()) here else File("app/$path")
    }

    private fun text(path: String) = module(path).readText()

    @Test
    fun `the player opens in the caller's task and stays out of the way of Recents`() {
        val manifest = text("src/main/AndroidManifest.xml")
        val player = manifest.substringAfter("android:name=\".player.PlayerActivity\"").substringBefore("/>")
        assertFalse("an own task is what let the old dock keep a player alive", "taskAffinity" in player)
        assertFalse("autoRemoveFromRecents" in player)
        assertTrue("supportsPictureInPicture" in player)
    }

    @Test
    fun `no dock paths are left in the app`() {
        val gone = listOf("MiniDock", "PlayerLaunch", "dockOnBack", "dockOnLeave", "followMiniPlayer", "EXTRA_EXPAND", "tapExpandsDock", "miniPlayerOn")
        val sources = module("src/main").walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }.toList()
        assertTrue(sources.isNotEmpty())
        val found = sources.flatMap { file ->
            val body = file.readText()
            gone.filter { it in body }.map { "${file.name}: $it" }
        }
        assertEquals("leftovers of the phone's mini player: $found", emptyList<String>(), found)
    }

    @Test
    fun `Back with nothing to peel pauses and leaves, a second Back does nothing more`() {
        val source = text("src/main/java/com/tmplayer/player/PlayerActivity.kt")
        assertTrue("if (!peelOneOverlay()) leaveByBack()" in source)
        val body = source.substringAfter("private fun leaveByBack() {").substringBefore("\n    }")
        assertTrue("a second Back while finishing is ignored", "if (isFinishing) return" in body)
        assertTrue(body.indexOf("pause()") in 0 until body.indexOf("finish()"))
    }

    /**
     * Raju step 6.4: a paused video is left with one Back on a phone, the same as a playing one, and
     * the sound goes with it. Nothing on the way from the key to `finish()` may read whether the
     * video plays: a branch there is how a paused video once kept a mini bar and a sound alive.
     */
    @Test
    fun `Back on a paused video closes it, nothing between the key and finish reads the play state`() {
        val source = text("src/main/java/com/tmplayer/player/PlayerActivity.kt")
        val callback = source.substringAfter("onBackPressedDispatcher.addCallback(this) {").substringBefore("\n        }")
        assertEquals("if (!peelOneOverlay()) leaveByBack()", callback.trim())
        val peel = source.substringAfter("private fun peelOneOverlay(): Boolean {").substringBefore("\n    }")
        val rowRule = source.substringAfter("private fun backPeelsTheRow(): Boolean =").substringBefore("\n\n")
        val leave = source.substringAfter("private fun leaveByBack() {").substringBefore("\n    }")
        for ((name, body) in listOf("peelOneOverlay" to peel, "backPeelsTheRow" to rowRule, "leaveByBack" to leave)) {
            for (word in listOf("isPlaying", "playWhenReady", "isPaused", "playbackState")) {
                assertFalse("$name must not branch on $word", word in body)
            }
        }
        // A phone's row is held up while paused, and does not take the first Back: only a sheet opened from it does.
        assertTrue("FormFactor.isTv(this) || menuOpen" in rowRule)
        assertFalse("controlsUp" in rowRule)
        // The sound stops before the screen goes.
        assertTrue(leave.indexOf("pause()") in 0 until leave.indexOf("finish()"))
    }
}
