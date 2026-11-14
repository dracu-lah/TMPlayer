package com.tmplayer.data

import com.tmplayer.player.EngineChoice
import com.tmplayer.player.EngineDecision
import com.tmplayer.player.EngineFailure
import com.tmplayer.player.EngineHistory
import com.tmplayer.player.EngineInput
import com.tmplayer.player.EngineReason
import com.tmplayer.player.EngineSelector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

/**
 * What the player stores for the engine choice (PR2) and how [EngineSelector] reads it back: the
 * failure counts only rise until the viewer changes the setting, and the one automatic switch
 * per video holds across what is stored.
 */
class EngineSettingsTest {

    private val dir = Files.createTempDirectory("tm-engine").toFile()
    private val settings = SettingsStore(SettingsStore.openDataStore(dir.resolve(SettingsStore.FILE_NAME)))
    private val pixel = DeviceFacts(manufacturer = "Google", model = "Pixel 8", sdk = 35)

    @Test
    fun `failures are counted per engine and a new choice starts them again`() = runBlocking {
        assertEquals(EngineHistory.None, settings.engineHistoryNow())
        settings.noteEngineFailure(PlayerEngine.ExoPlayer)
        settings.noteEngineFailure(PlayerEngine.ExoPlayer)
        settings.noteEngineFailure(PlayerEngine.Vlc)
        assertEquals(EngineHistory(2, 1), settings.engineHistoryNow())
        // The same choice again changes nothing.
        settings.setEngineChoice(EngineChoice.Automatic)
        settings.setEngineChoice(EngineChoice.Automatic)
        assertEquals(EngineHistory(2, 1), settings.engineHistoryNow())
        settings.setEngineChoice(EngineChoice.Vlc)
        assertEquals(EngineHistory.None, settings.engineHistoryNow())
        assertEquals(EngineChoice.Vlc, settings.engineChoiceNow())
    }

    @Test
    fun `two stored ExoPlayer failures make Automatic start on VLC, and a VLC failure ends that`() = runBlocking {
        settings.noteEngineFailure(PlayerEngine.ExoPlayer)
        settings.noteEngineFailure(PlayerEngine.ExoPlayer)
        val input = EngineInput(settings.engineChoiceNow(), pixel, settings.engineHistoryNow(), vlcAvailable = true)
        assertEquals(EngineReason.DeviceHistory, EngineSelector.choose(input).reason)
        settings.noteEngineFailure(PlayerEngine.Vlc)
        val after = input.copy(history = settings.engineHistoryNow())
        assertEquals(PlayerEngine.ExoPlayer, EngineSelector.choose(after).engine)
    }

    @Test
    fun `one automatic switch per video, only on a decoder failure, never from VLC`() {
        val input = EngineInput(EngineChoice.Automatic, pixel, vlcAvailable = true)
        val exo = EngineDecision(PlayerEngine.ExoPlayer, EngineReason.Default)
        assertEquals(PlayerEngine.Vlc, EngineSelector.afterFailure(input, exo, EngineFailure.Decoder, switches = 0)?.engine)
        assertNull(EngineSelector.afterFailure(input, exo, EngineFailure.Decoder, switches = 1))
        assertNull(EngineSelector.afterFailure(input, exo, EngineFailure.Source, switches = 0))
        assertNull(EngineSelector.afterFailure(input, EngineDecision(PlayerEngine.Vlc, EngineReason.SwitchedAfterFailure), EngineFailure.Decoder, 0))
        assertNull(EngineSelector.afterFailure(input.copy(choice = EngineChoice.ExoPlayer), exo, EngineFailure.Decoder, 0))
        assertNull(EngineSelector.afterFailure(input.copy(stereo3d = true), exo, EngineFailure.Decoder, 0))
        // The sheet offers the other engine either way, VLC only with its library.
        assertEquals(PlayerEngine.Vlc, EngineSelector.toOffer(input, PlayerEngine.ExoPlayer))
        assertNull(EngineSelector.toOffer(input.copy(vlcAvailable = false), PlayerEngine.ExoPlayer))
        assertEquals(PlayerEngine.ExoPlayer, EngineSelector.toOffer(input, PlayerEngine.Vlc))
    }
}
