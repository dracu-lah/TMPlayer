package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the television player's More menu offers, and the key table it can show. */
class PlayerMenuTest {

    @Test
    fun `the menu carries the phone overflow's actions and the remote keys, in a fixed order`() {
        assertEquals(
            listOf(
                PlayerMenuEntry.LockScreen,
                PlayerMenuEntry.Speed,
                PlayerMenuEntry.MarkWatched,
                PlayerMenuEntry.SleepTimer,
                PlayerMenuEntry.PictureInPicture,
                PlayerMenuEntry.VolumeBoost,
                PlayerMenuEntry.NightMode,
                PlayerMenuEntry.EvenVolume,
                PlayerMenuEntry.AbLoop,
                PlayerMenuEntry.StartOver,
                PlayerMenuEntry.AddBookmark,
                PlayerMenuEntry.Bookmarks,
                PlayerMenuEntry.SaveToDownloads,
                PlayerMenuEntry.OpenInAnotherApp,
                PlayerMenuEntry.PlaybackDetails,
                PlayerMenuEntry.RemoteKeys,
            ),
            PlayerMenu.tvEntries(
                pictureInPicture = true,
                saveToDownloads = true,
                markWatched = true,
                bookmarks = true,
            ),
        )
    }

    @Test
    fun `the frame steps are offered only while paused, after the repeat line`() {
        val paused = PlayerMenu.tvEntries(pictureInPicture = true, paused = true)
        val at = paused.indexOf(PlayerMenuEntry.AbLoop)
        assertEquals(
            listOf(PlayerMenuEntry.NextFrame, PlayerMenuEntry.PreviousFrame),
            paused.subList(at + 1, at + 3),
        )
        assertFalse(PlayerMenuEntry.NextFrame in PlayerMenu.tvEntries(pictureInPicture = true))
        assertFalse(PhoneMenuEntry.NextFrame in PlayerMenu.phoneEntries(true, true, true, true))
        assertTrue(PhoneMenuEntry.PreviousFrame in PlayerMenu.phoneEntries(true, true, true, true, paused = true))
    }

    @Test
    fun `the chapters page comes only with two or more chapters, in the Playback group before the steps`() {
        assertFalse(PlayerMenuEntry.Chapters in PlayerMenu.tvEntries(pictureInPicture = true, chapters = true))
        assertFalse(PhoneMenuEntry.Chapters in PlayerMenu.phoneEntries(true, true, true, true, chapters = true))
        val tv = PlayerMenu.tvEntries(pictureInPicture = true, chapters = true, chapterList = true)
        assertEquals(tv.indexOf(PlayerMenuEntry.Chapters) + 1, tv.indexOf(PlayerMenuEntry.NextChapter))
        assertEquals(PlayerMenuGroup.Playback, PlayerMenu.groupOf(PlayerMenuEntry.Chapters))
        val phone = PlayerMenu.phoneEntries(true, true, true, true, chapters = true, chapterList = true)
        assertEquals(phone.indexOf(PhoneMenuEntry.Chapters) + 1, phone.indexOf(PhoneMenuEntry.NextChapter))
        assertEquals("Chapters", PhoneMenuEntry.Chapters.label())
        // A video with no chapters keeps the menu exactly as it was.
        assertEquals(
            PlayerMenu.tvEntries(pictureInPicture = true),
            PlayerMenu.tvEntries(pictureInPicture = true, chapters = false, chapterList = false),
        )
    }

    @Test
    fun `the chapter steps come only with a chaptered file, after the repeat line`() {
        assertFalse(PlayerMenuEntry.NextChapter in PlayerMenu.tvEntries(pictureInPicture = true))
        val tv = PlayerMenu.tvEntries(pictureInPicture = true, chapters = true)
        assertEquals(tv.indexOf(PlayerMenuEntry.AbLoop) + 1, tv.indexOf(PlayerMenuEntry.NextChapter))
        assertFalse(PhoneMenuEntry.PreviousChapter in PlayerMenu.phoneEntries(true, true, true, true))
        assertTrue(PhoneMenuEntry.PreviousChapter in PlayerMenu.phoneEntries(true, true, true, true, chapters = true))
    }

    @Test
    fun `lock the screen is the first line on the phone and the television alike`() {
        assertEquals(PhoneMenuEntry.LockScreen, PlayerMenu.phoneEntries(false, false, false, false).first())
        assertEquals(PlayerMenuEntry.LockScreen, PlayerMenu.tvEntries(pictureInPicture = false, openInAnotherApp = false).first())
        assertEquals("Lock the screen", PhoneMenuEntry.LockScreen.label())
        assertTrue(RemoteKeys.ROWS.any { it.first == "Hold OK, screen locked" })
    }

    @Test
    fun `the repeat line says what choosing it does next`() {
        assertEquals("Repeat: set the start (A)", PlayerMenu.loopLabel(null))
        assertEquals("Repeat: set the end (B)", PlayerMenu.loopLabel(AbLoop(10_000)))
        assertEquals("Stop repeating", PlayerMenu.loopLabel(AbLoop(10_000, 20_000)))
    }

    @Test
    fun `the episode steps are left to the Episodes button and the media keys`() {
        assertEquals(PlayerMenuEntry.LockScreen, PlayerMenu.tvEntries(pictureInPicture = true).first())
        assertFalse(PlayerMenuEntry.entries.any { it.name.contains("Episode") })
    }

    @Test
    fun `mark as watched is only offered for a video that came from a message`() {
        assertFalse(PlayerMenuEntry.MarkWatched in PlayerMenu.tvEntries(pictureInPicture = true, saveToDownloads = true))
        assertTrue(PlayerMenuEntry.MarkWatched in PlayerMenu.tvEntries(pictureInPicture = true, markWatched = true))
    }

    @Test
    fun `bookmarks are only offered for a video that came from a message`() {
        assertFalse(PlayerMenuEntry.AddBookmark in PlayerMenu.tvEntries(pictureInPicture = true, markWatched = true))
        assertTrue(PlayerMenuEntry.Bookmarks in PlayerMenu.tvEntries(pictureInPicture = true, bookmarks = true))
        assertFalse(PhoneMenuEntry.AddBookmark in PlayerMenu.phoneEntries(true, true, true, true))
    }

    @Test
    fun `save to downloads is only offered for a video that is not a download already`() {
        assertFalse(PlayerMenuEntry.SaveToDownloads in PlayerMenu.tvEntries(pictureInPicture = true))
        assertTrue(PlayerMenuEntry.SaveToDownloads in PlayerMenu.tvEntries(pictureInPicture = true, saveToDownloads = true))
    }

    @Test
    fun `open in another app is left off a video that may not be saved`() {
        assertFalse(
            PlayerMenuEntry.OpenInAnotherApp in
                PlayerMenu.tvEntries(pictureInPicture = true, openInAnotherApp = false),
        )
        assertTrue(PlayerMenuEntry.OpenInAnotherApp in PlayerMenu.tvEntries(pictureInPicture = true))
    }

    @Test
    fun `picture in picture is only offered where the device can do it`() {
        val entries = PlayerMenu.tvEntries(
            pictureInPicture = false,
            saveToDownloads = true,
            markWatched = true,
            paused = true,
            bookmarks = true,
            chapters = true,
            chapterList = true,
            stereo = true,
            pictureShape = true,
            reportStutter = true,
            copyLinkAtTime = true,
            otherEngine = true,
            decoder = true,
            repeatVideo = true,
            markIntro = true,
            removeIntroMark = true,
            rotatePicture = true,
            playerVolume = true,
            addToList = true,
            loadSubtitleFile = true,
            passthroughOff = true,
        )
        assertFalse(PlayerMenuEntry.PictureInPicture in entries)
        assertEquals(PlayerMenuEntry.entries.size - 1, entries.size)
    }

    @Test
    fun `every entry is reachable on a device with picture in picture`() {
        assertEquals(
            PlayerMenuEntry.entries.toSet(),
            PlayerMenu.tvEntries(
                pictureInPicture = true,
                saveToDownloads = true,
                markWatched = true,
                paused = true,
                bookmarks = true,
                chapters = true,
                chapterList = true,
                stereo = true,
                pictureShape = true,
                reportStutter = true,
                copyLinkAtTime = true,
                otherEngine = true,
                decoder = true,
                repeatVideo = true,
                markIntro = true,
                removeIntroMark = true,
                rotatePicture = true,
                playerVolume = true,
                addToList = true,
                loadSubtitleFile = true,
                passthroughOff = true,
            ).toSet(),
        )
    }

    @Test
    fun `rotate picture follows the shapes and player volume sits above the boost, neither on a cast`() {
        val tv = PlayerMenu.tvEntries(pictureInPicture = true, pictureShape = true, stereo = true, rotatePicture = true, playerVolume = true)
        assertEquals(tv.indexOf(PlayerMenuEntry.PictureShape) + 1, tv.indexOf(PlayerMenuEntry.RotatePicture))
        assertEquals(tv.indexOf(PlayerMenuEntry.RotatePicture) + 1, tv.indexOf(PlayerMenuEntry.Stereo))
        assertEquals(tv.indexOf(PlayerMenuEntry.VolumeBoost) - 1, tv.indexOf(PlayerMenuEntry.PlayerVolume))
        val phone = PlayerMenu.phoneEntries(true, true, true, true, pictureShape = true, rotatePicture = true, playerVolume = true)
        assertEquals(phone.indexOf(PhoneMenuEntry.VolumeBoost) - 1, phone.indexOf(PhoneMenuEntry.PlayerVolume))
        assertTrue(phone.indexOf(PhoneMenuEntry.RotatePicture) > phone.indexOf(PhoneMenuEntry.PictureShape))
        // A cast: the activity passes false for both.
        val cast = PlayerMenu.tvEntries(pictureInPicture = true)
        assertFalse(PlayerMenuEntry.RotatePicture in cast)
        assertFalse(PlayerMenuEntry.PlayerVolume in cast)
    }

    @Test
    fun `3D video sits by the picture's own lines and only for a picture`() {
        assertFalse(PlayerMenuEntry.Stereo in PlayerMenu.tvEntries(pictureInPicture = true))
        val tv = PlayerMenu.tvEntries(pictureInPicture = true, stereo = true)
        assertEquals(tv.indexOf(PlayerMenuEntry.SleepTimer) + 1, tv.indexOf(PlayerMenuEntry.Stereo))
        val shaped = PlayerMenu.tvEntries(pictureInPicture = true, stereo = true, pictureShape = true)
        assertEquals(shaped.indexOf(PlayerMenuEntry.PictureShape) + 1, shaped.indexOf(PlayerMenuEntry.Stereo))
        val phone = PlayerMenu.phoneEntries(true, true, true, true, pictureShape = true, stereo = true)
        assertEquals(phone.indexOf(PhoneMenuEntry.PictureShape) + 1, phone.indexOf(PhoneMenuEntry.Stereo))
        assertFalse(PhoneMenuEntry.Stereo in PlayerMenu.phoneEntries(true, true, true, true))
    }

    @Test
    fun `the phone's panel reads in a fixed order and carries no start over`() {
        assertEquals(
            listOf(
                PhoneMenuEntry.LockScreen,
                PhoneMenuEntry.PictureInPicture,
                PhoneMenuEntry.Speed,
                PhoneMenuEntry.VolumeBoost,
                PhoneMenuEntry.NightMode,
                PhoneMenuEntry.PassthroughOff,
                PhoneMenuEntry.EvenVolume,
                PhoneMenuEntry.SleepTimer,
                PhoneMenuEntry.AbLoop,
                PhoneMenuEntry.NextChapter,
                PhoneMenuEntry.PreviousChapter,
                PhoneMenuEntry.NextFrame,
                PhoneMenuEntry.PreviousFrame,
                PhoneMenuEntry.SaveFrame,
                PhoneMenuEntry.AddBookmark,
                PhoneMenuEntry.Bookmarks,
                PhoneMenuEntry.OpenInAnotherApp,
                PhoneMenuEntry.CopyLinkAtTime,
                PhoneMenuEntry.LoadSubtitleFile,
                PhoneMenuEntry.SaveToDownloads,
                PhoneMenuEntry.MarkWatched,
                PhoneMenuEntry.AddToList,
                PhoneMenuEntry.ReportStutter,
                PhoneMenuEntry.ReportWrongPicture,
                PhoneMenuEntry.PlaybackDetails,
            ),
            PlayerMenu.phoneEntries(
                pictureInPicture = true,
                openInAnotherApp = true,
                saveToDownloads = true,
                markWatched = true,
                paused = true,
                bookmarks = true,
                chapters = true,
                saveFrame = true,
                reportStutter = true,
                copyLinkAtTime = true,
                addToList = true,
                passthroughOff = true,
            ),
        )
        assertEquals(PhoneMenuEntry.entries.toSet(), PlayerMenu.phoneEntries(true, true, true, true, paused = true, bookmarks = true, chapters = true, chapterList = true, saveFrame = true, pictureShape = true, stereo = true, zoomed = true, reportStutter = true, copyLinkAtTime = true, otherEngine = true, repeatVideo = true, markIntro = true, removeIntroMark = true, rotatePicture = true, playerVolume = true, brightness = true, addToList = true, airPlay = true, decoder = true, passthroughOff = true).toSet())
    }

    @Test
    fun `AirPlay is an iOS line after picture in picture and never on by default`() {
        assertFalse(PhoneMenuEntry.AirPlay in PlayerMenu.phoneEntries(true, true, true, true))
        val withIt = PlayerMenu.phoneEntries(true, true, true, true, airPlay = true)
        assertEquals(withIt.indexOf(PhoneMenuEntry.PictureInPicture) + 1, withIt.indexOf(PhoneMenuEntry.AirPlay))
        assertEquals("AirPlay", PhoneMenuEntry.AirPlay.label())
        // Without picture in picture the line still stands, right after the lock.
        assertEquals(1, PlayerMenu.phoneEntries(false, true, true, true, airPlay = true).indexOf(PhoneMenuEntry.AirPlay))
    }

    @Test
    fun `reset zoom is offered under the shape only while the picture is zoomed`() {
        assertFalse(PhoneMenuEntry.ResetZoom in PlayerMenu.phoneEntries(true, true, true, true, pictureShape = true))
        assertFalse(PhoneMenuEntry.ResetZoom in PlayerMenu.phoneEntries(true, true, true, true, zoomed = true))
        val zoomed = PlayerMenu.phoneEntries(true, true, true, true, pictureShape = true, zoomed = true)
        assertEquals(zoomed.indexOf(PhoneMenuEntry.PictureShape) + 1, zoomed.indexOf(PhoneMenuEntry.ResetZoom))
        assertEquals("Reset zoom", PhoneMenuEntry.ResetZoom.label())
    }

    @Test
    fun `picture shape is in the phone's panel for any picture, before the speed`() {
        assertFalse(PhoneMenuEntry.PictureShape in PlayerMenu.phoneEntries(true, true, true, true))
        val picture = PlayerMenu.phoneEntries(true, true, true, true, pictureShape = true)
        // After the lock and picture in picture, the other lines about the screen itself.
        assertEquals(2, picture.indexOf(PhoneMenuEntry.PictureShape))
        assertEquals(3, picture.indexOf(PhoneMenuEntry.Speed))
        assertEquals("Picture shape (Fill)", PhoneMenuEntry.PictureShape.label(shape = VideoScale.Crop))
    }

    @Test
    fun `the television's panel offers every shape as a page, for a picture only`() {
        val all = PlayerMenu.tvEntries(pictureInPicture = true, saveToDownloads = true, markWatched = true, paused = true, bookmarks = true, chapters = true)
        assertFalse(PlayerMenuEntry.PictureShape in all)
        val shaped = PlayerMenu.tvEntries(pictureInPicture = true, pictureShape = true)
        assertEquals(shaped.indexOf(PlayerMenuEntry.SleepTimer) + 1, shaped.indexOf(PlayerMenuEntry.PictureShape))
        assertTrue(PlayerMenuEntry.Speed in all)
    }

    @Test
    fun `the phone's and the television's lines share the panel's names`() {
        // The panel maps both by name onto one set of lines, so a new entry needs a line there.
        val names = (PhoneMenuEntry.entries.map { it.name } + PlayerMenuEntry.entries.map { it.name }).toSet()
        assertTrue(names.containsAll(listOf("LockScreen", "PictureShape", "Speed", "Stereo", "SleepTimer", "Bookmarks")))
    }

    @Test
    fun `save a frame is left off a video from a chat that restricts saving`() {
        assertFalse(PhoneMenuEntry.SaveFrame in PlayerMenu.phoneEntries(true, false, true, true))
        assertTrue(PhoneMenuEntry.SaveFrame in PlayerMenu.phoneEntries(true, true, true, true, saveFrame = true))
        assertEquals("Save a frame", PhoneMenuEntry.SaveFrame.label())
    }

    @Test
    fun `the phone overflow leaves out what the device or the video cannot do`() {
        val bare = PlayerMenu.phoneEntries(
            pictureInPicture = false,
            openInAnotherApp = false,
            saveToDownloads = false,
            markWatched = false,
        )
        assertEquals(
            listOf(
                PhoneMenuEntry.LockScreen,
                PhoneMenuEntry.Speed,
                PhoneMenuEntry.VolumeBoost,
                PhoneMenuEntry.NightMode,
                PhoneMenuEntry.EvenVolume,
                PhoneMenuEntry.SleepTimer,
                PhoneMenuEntry.AbLoop,
                PhoneMenuEntry.LoadSubtitleFile,
                PhoneMenuEntry.PlaybackDetails,
            ),
            bare,
        )
    }

    @Test
    fun `the key table names every key the player answers, each once`() {
        val keys = RemoteKeys.ROWS.map { it.first }
        assertEquals(keys.size, keys.toSet().size)
        listOf("OK", "Play / Pause", "Next / Previous", "Back", "CC (if your remote has it)", "Hold OK, controls down", "OK on the time", "0 to 9", "Up, after a long jump").forEach {
            assertTrue("missing $it", it in keys)
        }
        assertTrue(RemoteKeys.ROWS.all { (key, does) -> key.isNotBlank() && does.isNotBlank() })
    }

    @Test
    fun `the viewer's two reports go together, last before the details on the phone and before the keys on the television, and never on a cast`() {
        val phone = PlayerMenu.phoneEntries(true, true, true, true, reportStutter = true)
        assertEquals(PhoneMenuEntry.ReportStutter, phone[phone.size - 3])
        assertEquals(PhoneMenuEntry.ReportWrongPicture, phone[phone.size - 2])
        assertEquals(PhoneMenuEntry.PlaybackDetails, phone.last())
        val noCast = PlayerMenu.phoneEntries(true, true, true, true)
        assertFalse(PhoneMenuEntry.ReportStutter in noCast)
        assertFalse(PhoneMenuEntry.ReportWrongPicture in noCast)

        val tv = PlayerMenu.tvEntries(pictureInPicture = true, reportStutter = true)
        assertEquals(PlayerMenuEntry.ReportStutter, tv[tv.size - 3])
        assertEquals(PlayerMenuEntry.ReportWrongPicture, tv[tv.size - 2])
        assertEquals(PlayerMenuEntry.RemoteKeys, tv.last())
        val tvNoCast = PlayerMenu.tvEntries(pictureInPicture = true)
        assertFalse(PlayerMenuEntry.ReportStutter in tvNoCast)
        assertFalse(PlayerMenuEntry.ReportWrongPicture in tvNoCast)
    }

    @Test
    fun `the phone entry has its words`() {
        assertEquals(com.tmplayer.i18n.L.playerReportStutter, PhoneMenuEntry.ReportStutter.label())
        assertEquals(com.tmplayer.i18n.L.playerReportWrongPicture, PhoneMenuEntry.ReportWrongPicture.label())
        assertEquals("Picture looks wrong", com.tmplayer.i18n.L.playerReportWrongPicture)
    }

    @Test
    fun `copy link at this time sits after open in another app on both, and only when asked`() {
        val phone = PlayerMenu.phoneEntries(true, true, true, true, copyLinkAtTime = true)
        assertEquals(phone.indexOf(PhoneMenuEntry.OpenInAnotherApp) + 1, phone.indexOf(PhoneMenuEntry.CopyLinkAtTime))
        assertFalse(PhoneMenuEntry.CopyLinkAtTime in PlayerMenu.phoneEntries(true, true, true, true))
        val tv = PlayerMenu.tvEntries(pictureInPicture = true, copyLinkAtTime = true)
        assertEquals(tv.indexOf(PlayerMenuEntry.OpenInAnotherApp) + 1, tv.indexOf(PlayerMenuEntry.CopyLinkAtTime))
        assertFalse(PlayerMenuEntry.CopyLinkAtTime in PlayerMenu.tvEntries(pictureInPicture = true))
        assertEquals(com.tmplayer.i18n.L.playerCopyLinkAtTime, PhoneMenuEntry.CopyLinkAtTime.label())
    }

    @Test
    fun `try the other decoder comes only with a plan, just above the other engine (WP-25)`() {
        assertFalse(PhoneMenuEntry.Decoder in PlayerMenu.phoneEntries(true, true, true, true, otherEngine = true))
        assertFalse(PlayerMenuEntry.Decoder in PlayerMenu.tvEntries(pictureInPicture = true, otherEngine = true))
        val phone = PlayerMenu.phoneEntries(true, true, true, true, otherEngine = true, decoder = true)
        assertEquals(phone.indexOf(PhoneMenuEntry.Decoder) + 1, phone.indexOf(PhoneMenuEntry.OtherEngine))
        val tv = PlayerMenu.tvEntries(pictureInPicture = true, otherEngine = true, decoder = true)
        assertEquals(tv.indexOf(PlayerMenuEntry.Decoder) + 1, tv.indexOf(PlayerMenuEntry.OtherEngine))
        assertEquals(PlayerMenuGroup.Playback, PlayerMenu.groupOf(PlayerMenuEntry.Decoder))
        // Without the other engine it still comes before Start over, in the same group.
        val alone = PlayerMenu.tvEntries(pictureInPicture = true, decoder = true)
        assertEquals(alone.indexOf(PlayerMenuEntry.Decoder) + 1, alone.indexOf(PlayerMenuEntry.StartOver))
        // A protected chat keeps it: the decoder is TMPlayer's own.
        assertTrue(PhoneMenuEntry.Decoder in PlayerMenu.phoneEntries(true, false, true, true, decoder = true))
        assertEquals(com.tmplayer.i18n.L.playerDecoderOther, PhoneMenuEntry.Decoder.label())
    }

    @Test
    fun `try the other engine sits above open in another app on the phone, in Playback ahead of start over on the television`() {
        val phone = PlayerMenu.phoneEntries(true, true, true, true, otherEngine = true)
        assertEquals(phone.indexOf(PhoneMenuEntry.OtherEngine) + 1, phone.indexOf(PhoneMenuEntry.OpenInAnotherApp))
        assertFalse(PhoneMenuEntry.OtherEngine in PlayerMenu.phoneEntries(true, true, true, true))
        val tv = PlayerMenu.tvEntries(pictureInPicture = true, otherEngine = true)
        assertEquals(PlayerMenuGroup.Playback, PlayerMenu.groupOf(PlayerMenuEntry.OtherEngine))
        assertEquals(tv.indexOf(PlayerMenuEntry.OtherEngine) + 1, tv.indexOf(PlayerMenuEntry.StartOver))
        assertFalse(PlayerMenuEntry.OtherEngine in PlayerMenu.tvEntries(pictureInPicture = true))
        // A protected chat leaves Open in another app off; the other engine is still TMPlayer's own player.
        assertTrue(PlayerMenuEntry.OtherEngine in PlayerMenu.tvEntries(pictureInPicture = true, openInAnotherApp = false, otherEngine = true))
        assertEquals(com.tmplayer.i18n.L.playerEngineOther, PhoneMenuEntry.OtherEngine.label())
    }

    @Test
    fun `repeat this video sits right under the A-B repeat on both, and only for a video`() {
        val phone = PlayerMenu.phoneEntries(true, true, true, true, repeatVideo = true)
        assertEquals(phone.indexOf(PhoneMenuEntry.AbLoop) + 1, phone.indexOf(PhoneMenuEntry.RepeatVideo))
        assertFalse(PhoneMenuEntry.RepeatVideo in PlayerMenu.phoneEntries(true, true, true, true))
        val tv = PlayerMenu.tvEntries(pictureInPicture = true, repeatVideo = true)
        assertEquals(tv.indexOf(PlayerMenuEntry.AbLoop) + 1, tv.indexOf(PlayerMenuEntry.RepeatVideo))
        assertFalse(PlayerMenuEntry.RepeatVideo in PlayerMenu.tvEntries(pictureInPicture = true))
        assertEquals(com.tmplayer.i18n.L.playerRepeatVideo, PhoneMenuEntry.RepeatVideo.label())
    }

    @Test
    fun `the sound switch is called Quiet loud scenes, not Night mode, wherever the menu draws it`() {
        assertEquals("Quiet loud scenes", PhoneMenuEntry.NightMode.label())
        assertEquals("Quiet loud scenes", com.tmplayer.i18n.L.playerQuietLoudScenes)
        assertEquals("Quiet loud scenes off", com.tmplayer.i18n.L.playerQuietLoudScenesOff)
        assertTrue(com.tmplayer.i18n.L.playerQuietLoudScenesOn.startsWith("Quiet loud scenes on"))
        assertFalse(com.tmplayer.i18n.L.playerDetailsQuietLoudScenes.contains("Night", ignoreCase = true))
    }

    @Test
    fun `add to a list is offered for a video from a message, under Mark as watched on the phone and above it in the television top group`() {
        // A list keeps a name, never a copy: a chat that restricts saving (openInAnotherApp false) may be in one.
        val phone = PlayerMenu.phoneEntries(pictureInPicture = true, openInAnotherApp = false, saveToDownloads = true, markWatched = true, addToList = true)
        assertEquals(phone.indexOf(PhoneMenuEntry.MarkWatched) + 1, phone.indexOf(PhoneMenuEntry.AddToList))
        val tv = PlayerMenu.tvEntries(pictureInPicture = true, openInAnotherApp = false, saveToDownloads = true, markWatched = true, addToList = true)
        assertEquals(tv.indexOf(PlayerMenuEntry.AddToList) + 1, tv.indexOf(PlayerMenuEntry.MarkWatched))
        assertEquals("Add to a list", PhoneMenuEntry.AddToList.label())
    }

    @Test
    fun `add to a list is left off a file with no message`() {
        assertFalse(PhoneMenuEntry.AddToList in PlayerMenu.phoneEntries(true, true, true, true))
        assertFalse(PlayerMenuEntry.AddToList in PlayerMenu.tvEntries(pictureInPicture = true, markWatched = true))
    }

    @Test
    fun `load a subtitle file is a Playback line on the television, shown only where a document picker answers (WP-81)`() {
        val tv = PlayerMenu.tvEntries(pictureInPicture = true, loadSubtitleFile = true)
        assertTrue(PlayerMenuEntry.LoadSubtitleFile in tv)
        assertEquals(PlayerMenuGroup.Playback, PlayerMenu.groupOf(PlayerMenuEntry.LoadSubtitleFile))
        assertEquals(tv.indexOf(PlayerMenuEntry.StartOver) + 1, tv.indexOf(PlayerMenuEntry.LoadSubtitleFile))
        // No picker (most sticks): no line, so no press opens nothing.
        assertFalse(PlayerMenuEntry.LoadSubtitleFile in PlayerMenu.tvEntries(pictureInPicture = true))
        assertEquals(com.tmplayer.i18n.L.playerLoadSubtitleFile, PhoneMenuEntry.LoadSubtitleFile.label())
    }

    private fun everything(pictureInPicture: Boolean = true) = PlayerMenu.tvEntries(
        pictureInPicture = pictureInPicture,
        saveToDownloads = true,
        markWatched = true,
        paused = true,
        bookmarks = true,
        chapters = true,
        chapterList = true,
        stereo = true,
        pictureShape = true,
        reportStutter = true,
        copyLinkAtTime = true,
        otherEngine = true,
        decoder = true,
        repeatVideo = true,
        markIntro = true,
        removeIntroMark = true,
        rotatePicture = true,
        playerVolume = true,
        addToList = true,
        loadSubtitleFile = true,
        passthroughOff = true,
    )

    @Test
    fun `the groups come in one order and every line is in exactly one of them (WP-20)`() {
        val all = everything()
        val groups = all.map(PlayerMenu::groupOf)
        // Lock heads the top group, Remote keys ends Help; the groups never interleave.
        assertEquals(PlayerMenuEntry.LockScreen, all.first())
        assertEquals(PlayerMenuEntry.RemoteKeys, all.last())
        assertEquals(PlayerMenuGroup.entries, groups.distinct())
        assertEquals(
            listOf(PlayerMenuEntry.Speed, PlayerMenuEntry.AddToList, PlayerMenuEntry.MarkWatched, PlayerMenuEntry.SleepTimer),
            all.filter { PlayerMenu.groupOf(it) == PlayerMenuGroup.Top }.drop(1),
        )
        assertEquals(
            listOf(PlayerMenuEntry.PictureShape, PlayerMenuEntry.RotatePicture, PlayerMenuEntry.Stereo, PlayerMenuEntry.PictureInPicture),
            all.filter { PlayerMenu.groupOf(it) == PlayerMenuGroup.Picture },
        )
        assertEquals(
            listOf(PlayerMenuEntry.PlayerVolume, PlayerMenuEntry.VolumeBoost, PlayerMenuEntry.NightMode, PlayerMenuEntry.PassthroughOff, PlayerMenuEntry.EvenVolume),
            all.filter { PlayerMenu.groupOf(it) == PlayerMenuGroup.Sound },
        )
        assertEquals(
            listOf(PlayerMenuEntry.SaveToDownloads, PlayerMenuEntry.OpenInAnotherApp, PlayerMenuEntry.CopyLinkAtTime),
            all.filter { PlayerMenu.groupOf(it) == PlayerMenuGroup.Library },
        )
        assertEquals(
            listOf(PlayerMenuEntry.PlaybackDetails, PlayerMenuEntry.ReportStutter, PlayerMenuEntry.ReportWrongPicture, PlayerMenuEntry.RemoteKeys),
            all.filter { PlayerMenu.groupOf(it) == PlayerMenuGroup.Help },
        )
        // Every entry has a group, and the full menu carries every entry.
        assertEquals(PlayerMenuEntry.entries.toSet(), all.toSet())
    }

    @Test
    fun `a device without 3D, rotate or the picture lines leaves no empty group (WP-20)`() {
        val bare = PlayerMenu.tvEntries(pictureInPicture = false, openInAnotherApp = false)
        val groups = bare.map(PlayerMenu::groupOf)
        // No Picture and no Library group at all; the rest keep their order and each is non-empty.
        assertEquals(
            listOf(PlayerMenuGroup.Top, PlayerMenuGroup.Sound, PlayerMenuGroup.Playback, PlayerMenuGroup.Help),
            groups.distinct(),
        )
        // A restricted chat (no saving, no hand-over, no link) leaves no Library either; with the
        // link allowed the group is back with just that line.
        val withLink = PlayerMenu.tvEntries(pictureInPicture = false, openInAnotherApp = false, copyLinkAtTime = true)
        assertEquals(listOf(PlayerMenuEntry.CopyLinkAtTime), withLink.filter { PlayerMenu.groupOf(it) == PlayerMenuGroup.Library })
    }

    @Test
    fun `Remote keys is in Help whatever else the device offers (WP-20)`() {
        for (entries in listOf(everything(), everything(pictureInPicture = false), PlayerMenu.tvEntries(pictureInPicture = false))) {
            assertEquals(PlayerMenuGroup.Help, PlayerMenu.groupOf(PlayerMenuEntry.RemoteKeys))
            assertEquals(PlayerMenuEntry.RemoteKeys, entries.last())
        }
    }

    @Test
    fun `Page Down goes to the next group's first line, Page Up to this group's first, then the one before (WP-20)`() {
        val all = everything()
        val groups = all.map(PlayerMenu::groupOf)
        fun first(g: PlayerMenuGroup) = groups.indexOf(g)
        // From Lock: the Picture group (the top group is Lock, Speed and the frequent lines).
        assertEquals(first(PlayerMenuGroup.Picture), PlayerMenu.pageTarget(groups, 0, forward = true))
        assertEquals(first(PlayerMenuGroup.Sound), PlayerMenu.pageTarget(groups, first(PlayerMenuGroup.Picture) + 1, forward = true))
        // Last group: nowhere further down.
        assertEquals(null, PlayerMenu.pageTarget(groups, all.size - 1, forward = true))
        // Up from the middle of a group is its first line; from the first line, the group before's first.
        assertEquals(first(PlayerMenuGroup.Playback), PlayerMenu.pageTarget(groups, first(PlayerMenuGroup.Playback) + 2, forward = false))
        assertEquals(first(PlayerMenuGroup.Sound), PlayerMenu.pageTarget(groups, first(PlayerMenuGroup.Playback), forward = false))
        assertEquals(null, PlayerMenu.pageTarget(groups, 0, forward = false))
        assertEquals(null, PlayerMenu.pageTarget(groups, -1, forward = true))
        assertEquals(null, PlayerMenu.pageTarget(emptyList(), 0, forward = true))
    }

    /** WP-24: "Turn off passthrough for this video" is offered only while the sound is a bitstream, in the Sound group. */
    @Test
    fun `the passthrough line is offered only while it is asked for, beside the sound lines`() {
        assertFalse(PlayerMenuEntry.PassthroughOff in PlayerMenu.tvEntries(pictureInPicture = false))
        assertFalse(PhoneMenuEntry.PassthroughOff in PlayerMenu.phoneEntries(pictureInPicture = false, openInAnotherApp = true, saveToDownloads = false, markWatched = false))
        val tv = PlayerMenu.tvEntries(pictureInPicture = false, passthroughOff = true)
        assertEquals(PlayerMenuEntry.NightMode, tv[tv.indexOf(PlayerMenuEntry.PassthroughOff) - 1])
        assertEquals(PlayerMenuGroup.Sound, PlayerMenu.groupOf(PlayerMenuEntry.PassthroughOff))
        val phone = PlayerMenu.phoneEntries(pictureInPicture = false, openInAnotherApp = true, saveToDownloads = false, markWatched = false, passthroughOff = true)
        assertEquals(PhoneMenuEntry.NightMode, phone[phone.indexOf(PhoneMenuEntry.PassthroughOff) - 1])
    }
}
