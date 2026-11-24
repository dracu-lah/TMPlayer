package com.tmplayer.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Small guards in PlayerActivity from the second playback review, read from the source (the
 * activity cannot start under Robolectric): the corner window and the orientation (PB2-13), the
 * corner window's play button (PB2-14), a scan left waiting (PB2-15, PB2-16) and the failure sheet's
 * wait for the disk (PB2-19).
 */
class PlayerActivityGuardsTest {

    private val source: String by lazy {
        val here = File("src/main/java/com/tmplayer/player/PlayerActivity.kt")
        (if (here.exists()) here else File("app/${here.path}")).readText()
    }

    private fun body(start: String, end: String = "\n    }\n"): String {
        assertTrue("$start is in PlayerActivity", start in source)
        return source.substringAfter(start).substringBefore(end)
    }

    @Test
    fun `entering and leaving the corner window writes no orientation, only a change of the answer does (PB2-13)`() {
        val changed = body("override fun onConfigurationChanged(newConfig: Configuration) {")
        val pip = changed.indexOf("if (inPictureInPicture ||")
        assertTrue("the corner window returns first", pip > 0 && pip < changed.indexOf("applyOrientation()"))
        assertTrue("if (honoured == lastHonoured) return" in changed)
    }

    @Test
    fun `the corner window's play button is the row's toggle and shows what a press would do (PB2-14)`() {
        val button = body("private fun onPipButton(kind: PipActions.Kind) {")
        assertTrue("PipActions.Kind.PlayPause -> togglePlayback()" in button)
        assertFalse("it.play()" in button)
        val params = body("private fun pictureInPictureParams(): PictureInPictureParams {")
        assertTrue("it.playWhenReady && it.playbackState != Player.STATE_ENDED" in params)
    }

    @Test
    fun `a scan left waiting never lands on another player or video, and asks again on landing (PB2-15)`() {
        assertTrue("cancelScan()" in body("private fun moveToEngine("))
        assertTrue("cancelScan()" in body("private fun playEpisode(item: MediaItem, inChat: String) {"))
        val setup = source.substringBefore("remedies.newVideo(com.tmplayer.data.KnownDevices.remediesFor").length
        assertTrue("cancelScan()" in source.substring(setup, setup + 400))
        val commit = body("private fun commitScan() {")
        assertTrue(commit.indexOf("refuseSeek()") in 1 until commit.indexOf("playing?.seekTo(to)"))
    }

    @Test
    fun `the scan's release is taken before the key goes on to the views, Rewind scans, the flag gates it (PB2-16)`() {
        val dispatch = body("override fun dispatchKeyEvent(event: KeyEvent): Boolean {", "/** A key while the screen is locked")
        val release = dispatch.indexOf("// A scan lands once, when its key is let go")
        assertTrue(release in 1 until dispatch.indexOf("if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)"))
        assertTrue("event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND" in dispatch.substring(release, release + 400))
        // Off (the default), the arrows take today's live ramp: the scan is asked only behind the flag.
        assertTrue("if (scanThenJump && scanFromRemote(forward, event)) return true" in dispatch)
        assertTrue("jumpFromRemote(forward = forward, repeatCount = event.repeatCount, eventMs = event.eventTime)" in dispatch)
        assertTrue("(!controlsUp || remoteScan.target != null)" in dispatch)
    }

    @Test
    fun `the failure sheet's wait for the disk stops with the screen (PB2-19)`() {
        val wait = source.substringAfter("kotlinx.coroutines.withTimeoutOrNull(AVAILABILITY_WAIT_MS) {").substringBefore("} ?: LocalFileAvailability.Missing")
        assertTrue("com.tmplayer.data.runCatchingCancellable { Td.localFileAvailability(fileId) }" in wait)
        assertFalse(" runCatching { Td.localFileAvailability" in wait)
    }
}
