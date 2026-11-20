package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.tmplayer.desktop.player.OnlineSubtitlesPanel
import com.tmplayer.online.OnlineSubtitles
import com.tmplayer.online.SubtitleTarget
import com.tmplayer.ui.about.About
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Online subtitles on the desktop, over a canned OpenSubtitles ([OnlineFixture]): the Settings
 * group in each state, the sign in prompt, and the player's results panel in each state. With
 * TMPLAYER_SHOTS=<dir> the frames are written there as PNGs.
 */
class OnlineSubtitlesRenderTest {

    @After
    fun reset() {
        OnlineSubtitles.current = null
    }

    @Test
    fun settingsGroupInEachState() {
        for (state in listOf("signed_out", "signed_in", "quota", "expired", "unavailable", "subdl")) {
            OnlineFixture.install(state)
            save("desktop-settings-$state.png", render(height = 420) { OnlineSubtitlesGroup() })
        }
        OnlineFixture.install("signed_out")
        save("desktop-settings-signin-dialog.png", render(height = 900) { SignInDialog(onClose = {}) })
    }

    @Test
    fun aBuildWithoutTheKeyDrawsNothingAndCreditsNobody() {
        OnlineFixture.install("none")
        assertFalse(OnlineSubtitles.available)
        assertTrue(About.groups("1.0.0").none { group -> group.links.any { it.url == About.OPENSUBTITLES } })
        save("desktop-settings-none.png", render(height = 200) { OnlineSubtitlesGroup() })

        OnlineFixture.install("signed_out")
        assertTrue("About credits OpenSubtitles", About.groups("1.0.0").any { group -> group.links.any { it.url == About.OPENSUBTITLES } })
        save("desktop-about-credit.png", render(height = 1500) { AboutPage("1.0.0", onBack = {}) })
    }

    @Test
    fun playerPanelInEachState() {
        val target = SubtitleTarget("The.Coast.S01E04.1080p.WEB.H264.mkv", 1_400_000_000, hash = "8e245d9679d31e12")
        for (state in listOf("signed_in", "signed_out", "quota", "expired", "unavailable", "offline", "empty", "subdl")) {
            val online = OnlineFixture.install(state)
            val result = runBlocking { online.search(target) }
            save(
                "desktop-player-online-$state.png",
                render(height = 720, background = Color(0xFF203040)) {
                    Box(Modifier.fillMaxSize()) {
                        OnlineSubtitlesPanel(target = { target }, onLoaded = { _, _ -> }, onClose = {}, preset = result)
                    }
                },
            )
        }
    }

    /**
     * MSone's search in the player's panel, in each state the gateway can be in, and its row in
     * Settings once switched on with the hidden `msone` key. Every hit credits MSone and its
     * translator; every state that cannot search offers Check again and a file of one's own.
     */
    @Test
    fun msonePanelInEachState() {
        val target = SubtitleTarget("The.Coast.S01E04.1080p.WEB.H264.mkv", 1_400_000_000)
        for ((state, health) in listOf(
            "off" to """{"providers":{"msone":{"state":"off","message":null}}}""",
            "paused" to """{"providers":{"msone":{"state":"paused","message":"MSone asked for a short break while they move servers."}}}""",
            "stopped" to """{"providers":{"msone":{"state":"stopped","message":null}}}""",
            "on" to """{"providers":{"msone":{"state":"on","message":null}}}""",
            "unknown" to null,
        )) {
            val dir = java.nio.file.Files.createTempDirectory("tm-msone-$state").toFile()
            val online = OnlineSubtitles(
                apiKey = "promo-key",
                store = com.tmplayer.online.OnlineSubtitlesStore(File(dir, "account.properties")),
                cache = com.tmplayer.online.SubtitleCache(File(dir, "cache")),
                appVersion = "1.0.0",
                http = com.tmplayer.online.HttpTransport { request ->
                    when {
                        health == null -> throw com.tmplayer.platform.IOException("offline fixture")
                        "/health" in request.url -> com.tmplayer.online.HttpResponse(200, body = health.toByteArray())
                        "/msone/search" in request.url -> com.tmplayer.online.HttpResponse(200, body = MSONE_HITS.toByteArray())
                        else -> com.tmplayer.online.HttpResponse(404)
                    }
                },
            )
            online.setSubdlKey("msone")
            OnlineSubtitles.current = online
            val result = runBlocking { online.searchMsone(target) }
            if (state == "on") assertTrue(result.hits.size == 2) else assertTrue(result.notice is com.tmplayer.online.SubtitleNotice.MsoneOff)
            save(
                "desktop-player-msone-$state.png",
                render(height = 720, background = Color(0xFF203040)) {
                    Box(Modifier.fillMaxSize()) {
                        OnlineSubtitlesPanel(target = { target }, onLoaded = { _, _ -> }, onClose = {}, msone = true, preset = result)
                    }
                },
            )
            save("desktop-settings-msone-$state.png", render(height = 520) { OnlineSubtitlesGroup() })
        }
    }

    /**
     * The viewer's own SubMaker in the player's panel and in Settings, over a fake addon: the list
     * with a ready subtitle and "Make <Language>" entries, the progress line of a running job (with
     * Cancel), a stopped one (with Try again), and the states that cannot search (no address, an
     * address the addon turned down, no IMDb id).
     */
    @Test
    fun subMakerPanelInEachState() {
        val base = "https://addon.test/tok-3f9a2b7c1d"
        val target = SubtitleTarget("The.Coast.2024.1080p.WEB.H264.mkv", 1_400_000_000, imdb = "tt1234567")
        fun install(status: Int, makeAddress: Boolean = true): OnlineSubtitles {
            val dir = java.nio.file.Files.createTempDirectory("tm-submaker").toFile()
            val online = OnlineSubtitles(
                apiKey = "promo-key",
                store = com.tmplayer.online.OnlineSubtitlesStore(File(dir, "account.properties")),
                cache = com.tmplayer.online.SubtitleCache(File(dir, "cache")),
                appVersion = "1.0.0",
                http = com.tmplayer.online.HttpTransport { request ->
                    when {
                        "/subtitles/" in request.url -> com.tmplayer.online.HttpResponse(status, body = SUBMAKER_LIST.replace("BASE", base).toByteArray())
                        "/translate/" in request.url -> com.tmplayer.online.HttpResponse(403)
                        else -> com.tmplayer.online.HttpResponse(404)
                    }
                },
            )
            if (makeAddress) online.setSubMakerUrl("$base/manifest.json")
            OnlineSubtitles.current = online
            return online
        }
        val shown = mapOf(
            "list" to 200, "rejected" to 404, "busy" to 429, "offline" to 500,
        )
        for ((state, status) in shown) {
            val online = install(status)
            val result = runBlocking { online.searchSubMaker(target) }
            if (state == "list") assertTrue(result.hits.size == 3 && result.hits.count { it.translateTo.isNotEmpty() } == 2)
            else assertTrue(state, result.hits.isEmpty() && result.notice != null)
            save(
                "desktop-player-submaker-$state.png",
                render(height = 720, background = Color(0xFF203040)) {
                    Box(Modifier.fillMaxSize()) {
                        OnlineSubtitlesPanel(target = { target }, onLoaded = { _, _ -> }, onClose = {}, submaker = true, preset = result)
                    }
                },
            )
            save("desktop-settings-submaker-$state.png", render(height = 520) { OnlineSubtitlesGroup() })
        }
        // A running job and a stopped one, in the panel that finds them by the video and the language.
        val online = install(200)
        online.translations.setForeground(false)
        val result = runBlocking { online.searchSubMaker(target) }
        val make = result.hits.first { it.translateTo == "Malayalam" }
        assertTrue(online.translate(target.videoKey, make, null) { _, _ -> })
        save(
            "desktop-player-submaker-running.png",
            render(height = 720, background = Color(0xFF203040)) {
                Box(Modifier.fillMaxSize()) {
                    OnlineSubtitlesPanel(target = { target }, onLoaded = { _, _ -> }, onClose = {}, submaker = true)
                }
            },
        )
        online.translations.cancelAll()
        assertTrue(online.translations.states.value.isEmpty())
        // No address: no row in Settings reads set, and a search says where to add it.
        online.setSubMakerUrl("")
        assertTrue(runBlocking { online.searchSubMaker(target) }.notice == com.tmplayer.online.SubtitleNotice.SubMakerOff)
        save("desktop-settings-submaker-none.png", render(height = 520) { OnlineSubtitlesGroup() })
        save("desktop-submaker-dialog.png", render(height = 700) { SubMakerDialog(onClose = {}) })
    }

    private fun render(height: Int, background: Color? = null, page: @Composable () -> Unit): ByteArray =
        ImageComposeScene(WIDTH, height, Density(1.5f)) {
            TmMaterialTheme(dark = true) {
                Surface(Modifier.fillMaxSize(), color = background ?: Tone.background) {
                    Column(Modifier.padding(horizontal = if (background == null) 32.dp else 0.dp)) { page() }
                }
            }
        }.use { scene ->
            scene.render(0)
            Thread.sleep(300)
            scene.render(500_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }

    private fun save(name: String, png: ByteArray) {
        assertTrue("$name drew nothing", png.size > 1_000)
        val out = System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        File(out).apply { mkdirs() }.resolve(name).writeBytes(png)
    }

    private companion object {
        const val WIDTH = 1280

        const val SUBMAKER_LIST = """{"subtitles":[
            {"id":"sub_toolbox","lang":"Sub Toolbox","url":"BASE/sub-toolbox/x"},
            {"id":"os_1","lang":"eng","label":"The.Coast.2024.1080p.WEB • OpenSubtitles","url":"BASE/sub/eng.srt"},
            {"id":"translate_mal","lang":"Make Malayalam","url":"BASE/translate/mal"},
            {"id":"translate_fra","lang":"Make French","url":"BASE/translate/fra"}
        ]}"""

        const val MSONE_HITS = """{"hits":[
            {"id":"9001","title":"The Coast","year":2024,"kind":"single","season":1,"episode":4,
             "translator":{"name":"Anu","url":"https://example.org/anu"},
             "downloadUrl":"https://malayalamsubtitles.org/download/9001","pageUrl":"https://malayalamsubtitles.org/the-coast-s01e04","credit":"Subtitles by MSone"},
            {"id":"9000","title":"The Coast","year":2024,"kind":"pack","season":1,"episode":null,
             "translator":{"name":"Biju","url":null},
             "downloadUrl":"https://malayalamsubtitles.org/download/9000","pageUrl":"https://malayalamsubtitles.org/the-coast-s01","credit":"Subtitles by MSone"}
        ]}"""
    }
}
