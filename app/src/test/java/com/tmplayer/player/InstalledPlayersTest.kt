package com.tmplayer.player

import android.app.Application
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The player list and the explicit intent (PR4): what `queryIntentActivities` finds is turned into
 * one line per app, VLC and MX first, this app left out, and an app that takes only a stream is
 * told apart from one that takes a file. The intent is aimed at the component, never implicit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InstalledPlayersTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun info(pkg: String, cls: String, label: String, exported: Boolean = true): ResolveInfo = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            packageName = pkg
            name = cls
            this.exported = exported
            applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = pkg }
        }
        nonLocalizedLabel = label
    }

    private fun install(address: String, vararg infos: ResolveInfo) {
        val pm = shadowOf(context.packageManager)
        for (info in infos) pm.addResolveInfoForIntent(InstalledPlayers.probe(address), info)
    }

    @Test
    fun `players are listed with VLC and MX first and this app left out`() = runBlocking {
        val vlc = info("org.videolan.vlc", "org.videolan.vlc.gui.video.VideoPlayerActivity", "VLC")
        val mx = info("com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.ad.ActivityScreen", "MX Player")
        val just = info("com.brouken.player", "com.brouken.player.PlayerActivity", "Just Player")
        val own = info(context.packageName, "com.tmplayer.player.PlayerActivity", "TMPlayer")
        val hidden = info("some.private.app", "some.private.Activity", "Private", exported = false)
        install(InstalledPlayers.FILE_ADDRESS, just, own, mx, vlc, hidden)
        install(InstalledPlayers.STREAM_ADDRESS, vlc, just)

        val found = InstalledPlayers.find(context)
        assertEquals(listOf("VLC", "MX Player", "Just Player"), found.map { it.app.label })
        // MX takes a file but not an address; VLC and Just Player take both.
        assertTrue(found[0].app.acceptsStream)
        assertFalse(found[1].app.acceptsStream)
        assertTrue(found[1].app.acceptsFile)
    }

    @Test
    fun `an app that takes only an address is listed as a stream player`() = runBlocking {
        val mpv = info("is.xyz.mpv", "is.xyz.mpv.MPVActivity", "mpv")
        install(InstalledPlayers.STREAM_ADDRESS, mpv)
        val found = InstalledPlayers.find(context).single().app
        assertTrue(found.acceptsStream)
        assertFalse(found.acceptsFile)
    }

    @Test
    fun `no player installed is an empty list, not an error`() = runBlocking {
        assertTrue(InstalledPlayers.find(context).isEmpty())
    }

    @Test
    fun `the intent names the app's own component and carries its extras`() {
        val vlc = ExternalApp("org.videolan.vlc", "org.videolan.vlc.gui.video.VideoPlayerActivity", "VLC")
        val uri = android.net.Uri.parse("http://127.0.0.1:5000/token/video.mkv")
        val intent = ExternalPlayer.viewIntent(context, vlc, uri, "video/x-matroska", "A video", 61_000)
        assertEquals(ComponentName(vlc.packageName, vlc.className), intent.component)
        assertEquals(android.content.Intent.ACTION_VIEW, intent.action)
        assertEquals(uri, intent.data)
        assertEquals("video/x-matroska", intent.type)
        assertEquals(61_000L, intent.getLongExtra("position", -1))
        assertEquals("A video", intent.getStringExtra("title"))
        // An address needs no read grant; a started-from-application intent opens its own task.
        assertEquals(0, intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertTrue(intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `a file handoff grants read access and an unknown app gets only the title`() {
        val other = ExternalApp("some.player", "some.player.Main", "Some")
        val uri = android.net.Uri.parse("content://com.tmplayer.test.updates/video.mp4")
        val intent = ExternalPlayer.viewIntent(context, other, uri, "", "T", 5_000)
        assertTrue(intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals("video/*", intent.type)
        assertNull(intent.extras?.get("position"))
        assertEquals("T", intent.getStringExtra("title"))
    }
}
