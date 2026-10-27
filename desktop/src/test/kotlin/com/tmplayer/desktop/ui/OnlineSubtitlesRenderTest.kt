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
