package com.tmplayer.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerChoiceTest {

    private val vlc = ExternalApp("org.videolan.vlc", "org.videolan.vlc.gui.video.VideoPlayerActivity", "VLC")
    private val mx = ExternalApp("com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.ad.ActivityScreen", "MX Player")
    private val just = ExternalApp("com.brouken.player", "com.brouken.player.PlayerActivity", "Just Player")
    private val kodi = ExternalApp("org.xbmc.kodi", "org.xbmc.kodi.Splash", "Kodi", acceptsFile = true, acceptsStream = false)

    @Test
    fun `stored text round trips and unknown values mean TMPlayer`() {
        val app = PlayerChoice.App("org.videolan.vlc", "org.videolan.vlc.gui.video.VideoPlayerActivity")
        assertEquals(app, PlayerChoice.parse(app.encode()))
        assertEquals(PlayerChoice.Ask, PlayerChoice.parse("ask"))
        assertEquals(PlayerChoice.Ask, PlayerChoice.parse(" ASK "))
        assertEquals(PlayerChoice.TmPlayer, PlayerChoice.parse("tmplayer"))
        for (bad in listOf(null, "", " ", "vlc", "app:", "app:/", "app:pkg/Cls", "app:a.b/", "app:/a.b", "app:a..b/C", "app:a.b/C d",
            "app:a.b/../x", "app:1a.b/C", "app:a.b/" + "C".repeat(300), "ask2", "app:a.b/C/D")) {
            assertEquals(PlayerChoice.TmPlayer, PlayerChoice.parse(bad), "for $bad")
        }
        assertEquals(PlayerChoice.TmPlayer, PlayerChoice.parse("x".repeat(5000)))
        // A nested class keeps its dollar sign.
        assertTrue(PlayerChoice.parse("app:a.b/c.D\$E") is PlayerChoice.App)
    }

    @Test
    fun `VLC and MX Player lead, the rest by name, and this app is left out`() {
        val own = ExternalApp("com.tmplayer", "com.tmplayer.player.PlayerActivity", "TMPlayer")
        val test = ExternalApp("com.tmplayer.test", "com.tmplayer.player.PlayerActivity", "TMPlayer test")
        val mxPro = ExternalApp("com.mxtech.videoplayer.pro", "x.Y", "MX Player Pro")
        val ordered = ExternalPlay.order(listOf(kodi, just, own, mxPro, test, mx, vlc), "com.tmplayer")
        assertEquals(listOf("VLC", "MX Player", "MX Player Pro", "Just Player", "Kodi"), ordered.map { it.label })
    }

    @Test
    fun `two components of one app become one line and blank labels use the package`() {
        val second = vlc.copy(className = "org.videolan.vlc.StartActivity", acceptsStream = false)
        val ordered = ExternalPlay.order(listOf(second, vlc, just.copy(label = " ")), "")
        assertEquals(2, ordered.size)
        assertEquals("com.brouken.player", ordered[1].label)
        assertEquals(second.className, ordered[0].className)
        assertTrue(ordered[0].acceptsStream)
    }

    @Test
    fun `the list is bounded and rows with no package are dropped`() {
        val many = (1..100).map { ExternalApp("a.p$it", "a.C", "App $it") } + ExternalApp("", "x.C", "x")
        assertEquals(ExternalPlay.MAX_APPS, ExternalPlay.order(many, "").size)
    }

    @Test
    fun `two apps with one label are told apart by package`() {
        val a = ExternalApp("one.player", "one.C", "Player")
        val b = ExternalApp("two.player", "two.C", "player")
        val c = ExternalApp("three.video", "three.C", "Video")
        assertEquals(listOf("Player (one.player)", "player (two.player)", "Video"), ExternalPlay.displayNames(listOf(a, b, c)))
    }

    @Test
    fun `the plan follows the choice, the lock and the cast`() {
        val installed = ExternalPlay.order(listOf(vlc, mx, kodi), "com.tmplayer")
        fun plan(
            choice: PlayerChoice,
            kids: Boolean = false,
            cast: Boolean = false,
            force: Boolean = false,
            stream: Boolean = false,
            apps: List<ExternalApp> = installed,
            cannot: Boolean = false,
        ) = ExternalPlay.plan(choice, apps, kids, cast, force, stream, cannot)

        assertEquals(ExternalPlay.Plan.BuiltIn(ExternalPlay.BuiltInWhy.Chosen), plan(PlayerChoice.TmPlayer))
        assertEquals(ExternalPlay.Plan.Ask(installed), plan(PlayerChoice.Ask))
        assertEquals(ExternalPlay.Plan.Launch(vlc), plan(vlc.choice))
        // The kids lock and a cast session beat every choice, the viewer's own press included.
        assertEquals(ExternalPlay.Plan.BuiltIn(ExternalPlay.BuiltInWhy.KidsLock), plan(vlc.choice, kids = true, force = true))
        assertEquals(ExternalPlay.Plan.BuiltIn(ExternalPlay.BuiltInWhy.Casting), plan(PlayerChoice.Ask, cast = true))
        // "Open in another app" always asks, even over a default.
        assertEquals(ExternalPlay.Plan.Ask(installed), plan(vlc.choice, force = true))
        assertEquals(ExternalPlay.Plan.Ask(installed), plan(PlayerChoice.TmPlayer, force = true))
        // Nothing installed: only TMPlayer, so nothing to ask.
        assertEquals(ExternalPlay.Plan.BuiltIn(ExternalPlay.BuiltInWhy.NoApps), plan(PlayerChoice.Ask, apps = emptyList()))
        assertEquals(ExternalPlay.Plan.BuiltIn(ExternalPlay.BuiltInWhy.CannotHand), plan(vlc.choice, cannot = true))
    }

    @Test
    fun `a default that is gone or cannot stream asks again`() {
        val installed = ExternalPlay.order(listOf(vlc, kodi), "")
        val gone = mx.choice
        assertEquals(ExternalPlay.Plan.Ask(installed, gone = gone), ExternalPlay.plan(gone, installed, kidsLocked = false, casting = false))
        // Restored on a phone without the app, the same.
        val noKodi = ExternalPlay.plan(kodi.choice, listOf(vlc), kidsLocked = false, casting = false)
        assertEquals(ExternalPlay.Plan.Ask(listOf(vlc), gone = kodi.choice), noKodi)
        // A video not all here goes to an app that takes an address; Kodi (file only) falls out.
        val streamed = ExternalPlay.plan(kodi.choice, installed, kidsLocked = false, casting = false, needsStream = true)
        assertEquals(ExternalPlay.Plan.Ask(listOf(vlc), gone = kodi.choice), streamed)
        // An update that renamed the activity keeps the choice.
        val renamed = vlc.copy(className = "org.videolan.vlc.NewActivity")
        assertEquals(ExternalPlay.Plan.Launch(renamed), ExternalPlay.plan(vlc.choice, listOf(renamed), false, false))
    }

    @Test
    fun `closing within two seconds with no position is a failed start`() {
        assertTrue(ExternalPlay.closedTooFast(300, null))
        assertTrue(ExternalPlay.closedTooFast(1_999, 0))
        assertFalse(ExternalPlay.closedTooFast(2_000, null))
        assertFalse(ExternalPlay.closedTooFast(500, 12_000))
        assertFalse(ExternalPlay.closedTooFast(-5, null))
    }

    @Test
    fun `a stream is let go only after the app came back and nothing reads it`() {
        assertTrue(ExternalPlay.streamShouldClose(returned = true, running = true, holdsWindow = false))
        assertFalse(ExternalPlay.streamShouldClose(returned = true, running = true, holdsWindow = true))
        assertFalse(ExternalPlay.streamShouldClose(returned = false, running = true, holdsWindow = false))
        assertFalse(ExternalPlay.streamShouldClose(returned = true, running = false, holdsWindow = false))
    }

    @Test
    fun `a position the app returns is taken only when it is plausible`() {
        assertEquals(5_000L, ExternalPlay.positionFrom(listOf(null, 5_000L), 60_000))
        assertNull(ExternalPlay.positionFrom(listOf(-1L, 0L, 90_000L), 60_000))
        assertEquals(90_000L, ExternalPlay.positionFrom(listOf(90_000L), 0))
        assertNull(ExternalPlay.positionFrom(listOf(Long.MAX_VALUE), 0))
        assertNull(ExternalPlay.positionFrom(emptyList(), 60_000))
    }

    @Test
    fun `streaming asks the disk for the rest of the video, up to a cap`() {
        val gb = 1024L * 1024 * 1024
        assertTrue(ExternalPlay.hasRoomToStream(10 * gb, 2 * gb))
        assertFalse(ExternalPlay.hasRoomToStream(10 * gb, 2 * gb - 1))
        assertTrue(ExternalPlay.hasRoomToStream(100L * 1024 * 1024, 100L * 1024 * 1024))
        assertFalse(ExternalPlay.hasRoomToStream(100L * 1024 * 1024, 99L * 1024 * 1024))
        // A video of unknown or negative size needs nothing; a failed free reading refuses.
        assertTrue(ExternalPlay.hasRoomToStream(-1, 0))
        assertFalse(ExternalPlay.hasRoomToStream(5 * gb, -1))
        assertEquals(2 * gb, ExternalPlay.streamNeeds(Long.MAX_VALUE))
    }

    @Test
    fun `extras carry what each app can take and nothing it cannot`() {
        val vlcExtras = ExternalExtras.forApp("org.videolan.vlc", "A video", 61_000, "content://x/sub.srt")
        assertTrue(ExternalExtra.Long64("position", 61_000) in vlcExtras)
        assertTrue(ExternalExtra.Text("title", "A video") in vlcExtras)
        assertTrue(vlcExtras.any { it.key == "subtitles_location" })
        val mxExtras = ExternalExtras.forApp("com.mxtech.videoplayer.pro", "A video", 61_000, null)
        assertTrue(ExternalExtra.Whole("position", 61_000) in mxExtras)
        assertTrue(ExternalExtra.Flag("return_result", true) in mxExtras)
        assertTrue(ExternalExtras.takesSubtitleArray("com.mxtech.videoplayer.ad"))
        // An unknown app is told the title only; no position, no guesses.
        assertEquals(listOf<ExternalExtra>(ExternalExtra.Text("title", "A video")), ExternalExtras.forApp("some.player", "A video", 61_000, "x"))
        // Nothing to say, nothing sent; absurd values are bounded.
        assertTrue(ExternalExtras.forApp("some.player", "", 0, null).isEmpty())
        val big = ExternalExtras.forApp("com.mxtech.videoplayer.ad", "t".repeat(5_000), Long.MAX_VALUE, null)
        assertEquals(200, (big.first() as ExternalExtra.Text).value.length)
        assertTrue(big.filterIsInstance<ExternalExtra.Whole>().single().value > 0)
    }

    @Test
    fun `VLC is offered only where its library is, and a stored VLC without it is named`() {
        assertEquals(listOf(EngineChoice.Automatic, EngineChoice.ExoPlayer), EngineOptions.offered(vlcAvailable = false))
        assertEquals(listOf(EngineChoice.Automatic, EngineChoice.ExoPlayer, EngineChoice.Vlc), EngineOptions.offered(vlcAvailable = true))
        assertTrue(EngineOptions.storedButUnavailable(EngineChoice.Vlc, vlcAvailable = false))
        assertFalse(EngineOptions.storedButUnavailable(EngineChoice.Vlc, vlcAvailable = true))
        assertFalse(EngineOptions.storedButUnavailable(EngineChoice.Automatic, vlcAvailable = false))
        // A stored value from a newer build reads as Automatic.
        assertEquals(EngineChoice.Automatic, EngineChoice.from("quantum"))
    }

    @Test
    fun `tunneled mode reads its stored name and falls back to Auto`() {
        assertEquals(TunneledMode.On, TunneledMode.from("on"))
        assertEquals(TunneledMode.Off, TunneledMode.from(" OFF "))
        for (bad in listOf(null, "", "maybe", "2")) assertEquals(TunneledMode.Auto, TunneledMode.from(bad))
    }
}
