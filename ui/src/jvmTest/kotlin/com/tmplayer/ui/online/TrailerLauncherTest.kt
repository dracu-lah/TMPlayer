package com.tmplayer.ui.online

import com.tmplayer.online.MetaTrailer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Every trailer button's one way in (private/TRAILERS-PLAN.md, T1): with a platform player behind
 * [LocalTrailerOpener] and the setting on, the trailer goes to it, and its fallback is the
 * external open, once; the setting off, no network or a bad key go outside without it. With no
 * player of the platform's own ([TrailerOpener.External]), the order from before stands.
 */
class TrailerLauncherTest {

    private val trailer = MetaTrailer("YE7VzlLtp-4")

    private class Fake(override val online: Boolean = true, val fallBack: Int = 0) : TrailerOpener {
        val opened = mutableListOf<String>()
        override fun open(trailer: MetaTrailer, onFallback: () -> Unit) {
            opened += trailer.key
            repeat(fallBack) { onFallback() }
        }
    }

    private class Outside(private val answer: Boolean = true) : (MetaTrailer) -> Boolean {
        val opened = mutableListOf<String>()
        override fun invoke(trailer: MetaTrailer): Boolean {
            opened += trailer.key
            return answer
        }
    }

    @Test
    fun theSettingOnSendsTheTrailerToThePlatformsPlayer() {
        val seam = Fake()
        val outside = Outside()
        val launcher = TrailerLauncher(outside, inApp = true, appFirst = { error("not before the platform's player") }, seam = seam) { true }
        launcher.open(trailer)
        assertEquals(listOf(trailer.key), seam.opened)
        assertEquals(emptyList<String>(), outside.opened)
        assertNull("the old WebView player stays shut", launcher.playing)
    }

    @Test
    fun theFallbackIsTheExternalOpenOnceAndItsQrWhereNothingOpensIt() {
        val seam = Fake(fallBack = 3)
        val outside = Outside(answer = false)
        val launcher = TrailerLauncher(outside, inApp = false, seam = seam) { true }
        launcher.open(trailer)
        assertEquals(listOf(trailer.key), outside.opened)
        assertEquals(trailer, launcher.unopened)
    }

    @Test
    fun theSettingOffANetworkDownOrABadKeyGoOutsideWithoutThePlayer() {
        for ((seam, setting, key) in listOf(
            Triple(Fake(), false, trailer.key),
            Triple(Fake(online = false), true, trailer.key),
            Triple(Fake(), true, "promoharbour"),
        )) {
            val outside = Outside()
            val launcher = TrailerLauncher(outside, inApp = true, seam = seam) { setting }
            launcher.open(MetaTrailer(key))
            assertEquals(emptyList<String>(), seam.opened)
            assertEquals(listOf(key), outside.opened)
            assertNull(launcher.playing)
            assertNull(launcher.unopened)
        }
    }

    @Test
    fun withoutAPlatformPlayerTheOrderFromBeforeStands() {
        // The YouTube app first, where there is one.
        val app = Outside()
        val outside = Outside()
        TrailerLauncher(outside, inApp = true, appFirst = app) { true }.open(trailer)
        assertEquals(listOf(trailer.key), app.opened)
        assertEquals(emptyList<String>(), outside.opened)

        // Then Android's WebView player, with the setting on.
        val none = Outside(answer = false)
        val webView = TrailerLauncher(outside, inApp = true, appFirst = none) { true }
        webView.open(trailer)
        assertEquals(trailer, webView.playing)

        // The setting off: the browser, never the WebView.
        val off = TrailerLauncher(outside, inApp = true, appFirst = none) { false }
        off.open(trailer)
        assertNull(off.playing)
        assertEquals(listOf(trailer.key), outside.opened)

        // No WebView (the desktop, iOS, a television without one): outside.
        val desktop = Outside()
        TrailerLauncher(desktop, inApp = false) { true }.open(trailer)
        assertEquals(listOf(trailer.key), desktop.opened)
    }

    @Test
    fun theDefaultSeamIsTheExternalOpen() {
        var fellBack = 0
        TrailerOpener.External.open(trailer) { fellBack++ }
        assertEquals(1, fellBack)
    }
}
