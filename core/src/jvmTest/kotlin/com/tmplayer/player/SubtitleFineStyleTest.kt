package com.tmplayer.player

import com.tmplayer.data.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** WP-29: the fine size, height and colour, the migration from a stored preset look, and the longer delay. */
class SubtitleFineStyleTest {

    private val dir = Files.createTempDirectory("tm-subfine").toFile()
    private val settings = SettingsStore(SettingsStore.openDataStore(dir.resolve(SettingsStore.FILE_NAME)))

    @Test
    fun `a look stored before the fine steps is unchanged, and each preset maps to its nearest step`() {
        val old = SubtitleStyle.from("Large", null, "Dark", "Raised", "Yellow", "Shadow", "Serif", true)
        assertEquals(0, old.sizePercent)
        assertEquals(-1, old.positionPercent)
        assertEquals(0, old.customColour)
        assertEquals(SubtitleSize.Large.fraction, old.fraction, 1e-6f)
        assertEquals(SubtitleSize.Large.mpvScale, old.mpvScale)
        assertEquals(SubtitlePosition.Raised.bottomFraction, old.bottomFraction, 1e-6f)
        assertEquals(SubtitlePosition.Raised.mpvSubPos, old.mpvSubPos)
        assertEquals(SubtitleColour.Yellow.argb, old.colourArgb)
        assertEquals(80, SubtitleStyle(size = SubtitleSize.Small).fineSizePercent)
        assertEquals(100, SubtitleStyle().fineSizePercent)
        assertEquals(120, SubtitleStyle(size = SubtitleSize.Large).fineSizePercent)
        assertEquals(150, SubtitleStyle(size = SubtitleSize.ExtraLarge).fineSizePercent)
        assertTrue(SubtitleStyle().isDefault)
    }

    @Test
    fun `the size steps by ten from where it is and stops at 50 and 250`() {
        var style = SubtitleStyle(size = SubtitleSize.Large)
        style = style.stepSize(1)
        assertEquals(130, style.sizePercent)
        assertEquals(SubtitleSize.Medium.fraction * 1.3f, style.fraction, 1e-6f)
        assertEquals("1.300", style.mpvScale)
        repeat(40) { style = style.stepSize(-1) }
        assertEquals(50, style.sizePercent)
        repeat(40) { style = style.stepSize(1) }
        assertEquals(250, style.sizePercent)
        assertEquals("2.500", style.mpvScale)
    }

    @Test
    fun `the height steps from the preset it stands for, zero to thirty per cent`() {
        var style = SubtitleStyle(position = SubtitlePosition.Raised)
        assertEquals(16, style.positionNowPercent)
        style = style.stepPosition(1)
        assertEquals(18, style.positionPercent)
        assertEquals(0.18f, style.bottomFraction, 1e-6f)
        assertEquals(82, style.mpvSubPos)
        repeat(30) { style = style.stepPosition(1) }
        assertEquals(30, style.positionPercent)
        repeat(30) { style = style.stepPosition(-1) }
        assertEquals(0, style.positionPercent)
        assertEquals(100, style.mpvSubPos)
    }

    @Test
    fun `a custom colour overrides the preset and the presets clear it`() {
        val pink = SubtitleStyle.CUSTOM_COLOURS[4]
        val style = SubtitleStyle(customColour = pink)
        assertEquals(pink, style.colourArgb)
        assertTrue(style.mpvProperties("Sans").contains("sub-color" to style.colourHex))
        assertEquals(0, style.nextColour().customColour)
        assertEquals(0, SubtitleStyle(sizePercent = 130).nextSize().sizePercent)
        assertEquals(-1, SubtitleStyle(positionPercent = 10).nextPosition().positionPercent)
    }

    @Test
    fun `garbage in the stored fine values is the preset`() {
        assertEquals(0, SubtitleStyle.sanitiseSizePercent(55))
        assertEquals(0, SubtitleStyle.sanitiseSizePercent(300))
        assertEquals(0, SubtitleStyle.sanitiseSizePercent(Long.MAX_VALUE))
        assertEquals(0, SubtitleStyle.sanitiseSizePercent(-100))
        assertEquals(120, SubtitleStyle.sanitiseSizePercent(120))
        assertEquals(-1, SubtitleStyle.sanitisePositionPercent(31))
        assertEquals(-1, SubtitleStyle.sanitisePositionPercent(-4))
        assertEquals(0, SubtitleStyle.sanitisePositionPercent(0))
        assertEquals(0, SubtitleStyle.sanitiseCustomColour(12345))
        assertEquals(0, SubtitleStyle.sanitiseCustomColour(-1))
        assertEquals(0, SubtitleStyle.sanitiseCustomColour(Long.MAX_VALUE))
        val yellow = SubtitleStyle.CUSTOM_COLOURS[1]
        assertEquals(yellow, SubtitleStyle.sanitiseCustomColour(SubtitleStyle.storedColour(yellow)))
    }

    @Test
    fun `the store round trips the fine values and drops them when a preset is picked`() = runBlocking {
        val style = SubtitleStyle(sizePercent = 140, positionPercent = 12, customColour = SubtitleStyle.CUSTOM_COLOURS[7])
        settings.setSubtitleStyle(style)
        assertEquals(style, settings.subtitleStyle.first())
        settings.setSubtitleStyle(style.copy(size = SubtitleSize.Small, sizePercent = 0, positionPercent = -1, customColour = 0))
        val back = settings.subtitleStyleNow()
        assertEquals(0, back.sizePercent)
        assertEquals(-1, back.positionPercent)
        assertEquals(0, back.customColour)
        assertEquals(SubtitleSize.Small, back.size)
        assertFalse(back.isDefault)
    }

    @Test
    fun `a video's own look (This video only) is kept for that video alone and reads back the same`() = runBlocking {
        val shared = SubtitleStyle(size = SubtitleSize.Large)
        val own = SubtitleStyle(sizePercent = 170, positionPercent = 12, colour = SubtitleColour.Yellow, bold = true)
        settings.setSubtitleStyle(shared)
        settings.setVideoSubtitleStyle(5L, 7L, own)
        assertEquals(own, settings.videoSubtitleStyle(5L, 7L))
        assertEquals("another video keeps following the shared look", null, settings.videoSubtitleStyle(5L, 8L))
        assertEquals("the shared look is untouched", shared, settings.subtitleStyleNow())
        settings.setVideoSubtitleStyle(5L, 7L, null)
        assertEquals(null, settings.videoSubtitleStyle(5L, 7L))
        assertEquals(shared, settings.subtitleStyleNow())
    }

    @Test
    fun `a stored per video look that is cut short, wrong typed or huge reads as the default or as nothing`() {
        assertEquals(null, SubtitleStyle.decode(null))
        assertEquals(null, SubtitleStyle.decode(""))
        assertEquals(null, SubtitleStyle.decode("garbage"))
        assertEquals(null, SubtitleStyle.decode("size=Large;".repeat(100)))
        val odd = SubtitleStyle.decode("size=Nope;sp=999999;pp=-5;cc=12;bold=maybe")
        assertEquals(SubtitleStyle(), odd)
        assertEquals(SubtitleStyle(size = SubtitleSize.Small), SubtitleStyle.decode("size=Small"))
    }

    @Test
    fun `the subtitle delay reaches a minute and a long press steps a second, the sound stays at ten seconds`() {
        assertEquals(60_000L, SyncDelays.MAX_MS)
        assertEquals(1_000L, SyncDelays.stepLong(0, 1))
        assertEquals(-1_000L, SyncDelays.stepLong(0, -1))
        assertEquals(60_000L, SyncDelays.stepLong(59_500, 1))
        assertEquals(60_000L, SyncDelays.decode("999999,0").subtitleMs)
        assertEquals(10_000L, SyncDelays.decode("0,999999").audioMs)
        assertEquals(10_000L, SyncDelays.stepAudio(9_500, 1, long = true))
        assertEquals(-10_000L, SyncDelays.stepAudio(-10_000, -1))
    }

    @Test
    fun `a track inside the file goes no earlier than its buffer reaches, a subtitle file the full minute (PB2-11)`() {
        assertEquals(-SyncDelays.EMBEDDED_LEAD_MS, SyncDelays.subtitleApplied(-60_000, sideLoaded = false))
        assertEquals(-60_000L, SyncDelays.subtitleApplied(-60_000, sideLoaded = true))
        // Later is fine either way: the lines already read wait for their time.
        assertEquals(60_000L, SyncDelays.subtitleApplied(60_000, sideLoaded = false))
        assertEquals(-3_000L, SyncDelays.subtitleApplied(-3_000, sideLoaded = false))
    }
}
