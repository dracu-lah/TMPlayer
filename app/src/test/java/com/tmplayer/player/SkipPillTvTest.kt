package com.tmplayer.player

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.tmplayer.data.FormFactor
import com.tmplayer.data.MediaItem
import com.tmplayer.i18n.L
import com.tmplayer.online.MetadataCache
import com.tmplayer.online.MetadataStore
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.online.SkipController
import com.tmplayer.online.SkipGroup
import com.tmplayer.online.SkipKind
import com.tmplayer.online.SkipMode
import com.tmplayer.online.SkipOffer
import com.tmplayer.online.SkipSegment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * GitHub issue 19 ("Skip intro button not appearing" on an Android TV, the switch on): an episode
 * of a show with an intro the player knows of, from the file's chapters or from the online lists,
 * run through the same tick the player runs twice a second ([SkipPill.follow] over a
 * [SkipController]) with "Skip intro buttons" on, raises the pill on a television, over
 * ExoPlayer and over VLC alike. VLC is the case that was broken: the player's loop read ExoPlayer
 * alone, which is gone while VLC plays, so a video on VLC never showed the button.
 *
 * Not covered here (device checks): the pill's place over a real picture, OK on the remote
 * seeking past the intro inside PlayerActivity, and libVLC's own position reports.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class SkipPillTvTest {

    @get:Rule
    val dir = TemporaryFolder()

    private var before: OnlineMetadata? = null

    @Before
    fun setUp() {
        before = OnlineMetadata.current
        FormFactor.override(true)
    }

    @After
    fun tearDown() {
        OnlineMetadata.current = before
        FormFactor.override(false)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** The settings the player reads its skip modes from, as the switches in Settings leave them. */
    private fun settings(lookups: Boolean = true, skipButtons: Boolean = true): OnlineMetadata {
        val metadata = OnlineMetadata(
            buildKey = "",
            store = MetadataStore(dir.newFile()),
            cache = MetadataCache(dir.newFolder()),
            appVersion = "test",
        )
        metadata.setEnabled(lookups)
        metadata.setSkipTimes(skipButtons)
        OnlineMetadata.current = metadata
        return metadata
    }

    private val episode = MediaItem(
        chatId = 7, messageId = 3, fileId = 3, title = "Harbour.Lights.S01E03.1080p.WEB.mkv", sizeBytes = 900_000_000,
        durationSec = 3_600, mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null, date = 1000,
        fileName = "Harbour.Lights.S01E03.1080p.WEB.mkv",
    )

    private fun pill(): SkipPill {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val below = View(activity)
        root.addView(below)
        activity.setContentView(root)
        return SkipPill(root, below, tv = true, onSkip = {})
    }

    private fun SkipPill.words(): String = (view.getChildAt(0) as TextView).text.toString()

    /** Both engines, prepared at [atMs] and playing, as the activity has them on screen. */
    private fun engines(atMs: Long): List<Pair<String, Engine>> = listOf(
        "ExoPlayer" to ExoEngine(FakeMedia3Player()),
        "VLC" to VlcEngine(FakeVlcBackend()),
    ).onEach { (_, engine) ->
        engine.prepare(EngineSource.File("/downloads/Harbour.Lights.S01E03.mkv"), atMs)
        engine.play()
        idle()
    }

    private fun tick(pill: SkipPill, skip: SkipController, engine: Engine, blocked: Boolean = false): SkipOffer? =
        pill.follow(skip, engine.player, blocked = blocked, holdCount = false, nowMs = 1_000L, skipped = emptySet(), cardCoversTheEnd = true)

    private fun chaptered(): SkipController = SkipController {}.apply {
        start(episode)
        chapters(listOf(Chapter(0L), Chapter(90_000L, "Opening"), Chapter(180_000L, "Part A"), Chapter(3_300_000L, "Ending")))
    }

    @Test
    fun `an episode's Opening chapter raises the pill on a television, on ExoPlayer and on VLC`() {
        settings()
        for ((name, engine) in engines(atMs = 100_000L)) {
            val pill = pill()
            val offer = tick(pill, chaptered(), engine)
            assertTrue(name, offer is SkipOffer.Ask)
            assertEquals(name, SkipKind.Opening, offer?.segment?.kind)
            assertTrue(name, pill.isShown)
            assertEquals(name, L.playerSkipOpening, pill.words())
            engine.release()
        }
    }

    @Test
    fun `a known intro time from the online lists raises Skip intro, on both engines`() {
        settings()
        for ((name, engine) in engines(atMs = 70_000L)) {
            val pill = pill()
            val skip = SkipController {}.apply {
                start(episode)
                online(listOf(SkipSegment(SkipKind.Intro, 60_000L, 150_000L)))
            }
            val offer = tick(pill, skip, engine)
            assertEquals(name, SkipKind.Intro, offer?.segment?.kind)
            assertTrue(name, pill.isShown)
            assertEquals(name, L.playerSkipIntro, pill.words())
            engine.release()
        }
    }

    @Test
    fun `the file's chapters need no online lookups, only the skip switch`() {
        settings(lookups = false, skipButtons = true)
        for ((name, engine) in engines(atMs = 100_000L)) {
            val pill = pill()
            tick(pill, chaptered(), engine)
            assertTrue(name, pill.isShown)
            engine.release()
        }
    }

    @Test
    fun `with Skip intro buttons off nothing is offered`() {
        settings(skipButtons = false)
        for ((name, engine) in engines(atMs = 100_000L)) {
            val pill = pill()
            assertNull(name, tick(pill, chaptered(), engine))
            assertFalse(name, pill.isShown)
            engine.release()
        }
    }

    @Test
    fun `the pill goes once the playhead is past the intro, and stays away while something covers it`() {
        settings()
        for ((name, engine) in engines(atMs = 100_000L)) {
            val pill = pill()
            val skip = chaptered()
            tick(pill, skip, engine)
            assertTrue(name, pill.isShown)
            assertNull(name, tick(pill, skip, engine, blocked = true))
            assertFalse(name, pill.isShown)
            engine.seekTo(200_000L)
            idle()
            assertNull(name, tick(pill, skip, engine))
            assertFalse(name, pill.isShown)
            engine.release()
        }
    }

    @Test
    fun `an intro set to skip by itself counts down on the pill`() {
        settings().setSkipMode(SkipGroup.Intro, SkipMode.Auto)
        for ((name, engine) in engines(atMs = 100_000L)) {
            val pill = pill()
            val offer = tick(pill, chaptered(), engine)
            assertTrue(name, offer is SkipOffer.Counting)
            assertTrue(name, pill.isShown)
            assertTrue(name, pill.isCounting)
            engine.release()
        }
    }
}
