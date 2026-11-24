package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Which code may use the live Telegram client without its owner (HX88). A job that writes to
 * Telegram, or runs past a switch, takes its session through [Td.sessionFor], bound to its
 * profile; a session taken any other way, or a helper that acts on whatever client is live
 * (`Td.deleteFile`, `Td.cancelDownload...`, `Td.myId`, `Td.logOut`, `Td.localDownloadedBytes`,
 * `Td.currentFileId`, `Td.trimStorage`, `Td.dropStalledDownload`, `Td.clearPicturesAndPreviews`), could be the next profile's.
 * Every source set of :core, :ui, :desktop and :app but the tests is scanned; each use left is
 * listed below with how many there are and why they are safe. A new one fails here until it
 * goes through [Td.sessionFor] or is listed with its reason.
 */
class TdSessionPolicyTest {

    private val uses = Regex("""awaitConnectedSessionOrNull\(|awaitConnectedSession\(\)|awaitAuthorizedSession\(\)|\b[Tt]d\.(deleteFile|cancelDownload\w*|myId|downloadFileInBackground|logOut|localDownloadedBytes|currentFileId|trimStorage|dropStalledDownload|clearPicturesAndPreviews)\(""")

    private val screenRead = "a read for the screen on screen, in the shell keyed on the open profile"
    private val desktopScreen = "a press or a read on the desktop's screen of the open profile; the window's state is built again on a switch (B2)"

    /** File, how many uses it has, and why they are safe. */
    private val allowed = mapOf(
        "app/src/dev/java/com/tmplayer/dev/NameDumpReceiver.kt" to (1 to "the dev build's name dump, a read"),
        "app/src/main/java/com/tmplayer/App.kt" to (2 to "the storage trim after sign in, of the open profile's own cache, and the cache trim at start"),
        "app/src/main/java/com/tmplayer/MainActivity.kt" to (1 to "a press in the shell, keyed on the open profile"),
        "app/src/main/java/com/tmplayer/data/DownloadFinisher.kt" to (2 to "a finished download's move, in the download service as its owner, checked (OwnedSessions.ensure) before each delete"),
        "app/src/main/java/com/tmplayer/data/DownloadService.kt" to (8 to "a download's cancels and bytes on the live client: the service stops every download of a profile on a switch, and each request carries its stamp"),
        "app/src/main/java/com/tmplayer/data/FolderExport.kt" to (3 to "the removal of a saved copy, checked against its owner right before the delete"),
        "app/src/main/java/com/tmplayer/data/LegacyDownloads.kt" to (3 to "the one-time move of old downloads after sign in, for the open profile, checked before the delete"),
        "app/src/main/java/com/tmplayer/data/LocalDownloads.kt" to (4 to "deleteAnyKind runs in the background for Remove after watching, as the owner of the mark, and checks it (OwnedSessions.ensure) after the lookup and before the delete; the other uses are the Downloads screen's presses"),
        "app/src/main/java/com/tmplayer/data/RoomOnDisk.kt" to (6 to "Free up space and its measuring, pressed on the screen of the open profile"),
        "app/src/main/java/com/tmplayer/data/StorageSplit.kt" to (4 to "the storage figures of the open profile, a read for the screen on screen"),
        "app/src/main/java/com/tmplayer/player/AndroidPlaybackTestEnv.kt" to (1 to "the Playback test, a read the viewer started and watches"),
        "app/src/main/java/com/tmplayer/player/ExternalPlayRouter.kt" to (2 to "a local path's read for the video handed over"),
        "app/src/main/java/com/tmplayer/player/ExternalPlayer.kt" to (1 to "a video handed to another player, a read; its stream stops on a switch"),
        "app/src/main/java/com/tmplayer/player/ExternalStreams.kt" to (1 to "the stream's read; it stops on a switch"),
        "app/src/main/java/com/tmplayer/player/PlayerActivity.kt" to (26 to "the player's reads and its own downloads' cancels; it closes before a switch (BeforeSwitch) and its writes carry its owner"),
        "app/src/main/java/com/tmplayer/ui/browse/QueueDownloads.kt" to (5 to "a press on the screen of the open profile"),
        "app/src/main/java/com/tmplayer/ui/downloads/DownloadsScreen.kt" to (10 to "presses on the Downloads screen of the open profile"),
        // The sign out moved from SettingsScreen to the shell host (WP-02); its one Td.logOut is the same
        // branch as before: no accounts file opened, so there is no other profile to mix up. With profiles
        // it goes through ProfileFlow.signOutOpen (AccountSwitcher.signOutProfile).
        "app/src/main/java/com/tmplayer/ui/settings/SignOutHost.kt" to (1 to "the sign out without profiles (no accounts file opened); with them it goes through ProfileFlow.signOutOpen"),
        "core/src/commonMain/kotlin/com/tmplayer/data/AccountSessions.kt" to (2 to "the Devices page's read, and its sign outs' wait, kept as before and then checked against the profile (ownSession)"),
        "core/src/commonMain/kotlin/com/tmplayer/data/AccountSwitcher.kt" to (3 to "who signed in, checked against the client generation it came from; the log out of the profile being signed out, under the switcher's lock"),
        "core/src/commonMain/kotlin/com/tmplayer/data/ChatRepository.kt" to (7 to "the chat list's reads and this account's own id, for the screen on screen"),
        "core/src/commonMain/kotlin/com/tmplayer/data/ChatSubtitlesTd.kt" to (1 to "subtitles for the video on screen, a read; answers keyed by the switch counter"),
        "core/src/commonMain/kotlin/com/tmplayer/data/OfflineDownloads.kt" to (1 to "a download row's bytes read for the open profile's rows, which a switch forgets"),
        "core/src/commonMain/kotlin/com/tmplayer/data/PageQueries.kt" to (1 to "a chat grid's read; its cache is stamped and dropped on a switch"),
        "core/src/commonMain/kotlin/com/tmplayer/data/ResumeSync.kt" to (2 to "this account's id, read on the owner's own session (sessionFor) inside the owned run"),
        "core/src/commonMain/kotlin/com/tmplayer/data/SavedMessagesBackup.kt" to (1 to "this account's id, read on the owner's session (sessionFor)"),
        "core/src/commonMain/kotlin/com/tmplayer/data/Td.kt" to (6 to "the waits themselves, and sessionFor over them"),
        "core/src/commonMain/kotlin/com/tmplayer/data/WatchCacheRules.kt" to (4 to "the watch cache's claims, sweep and clear over the open profile's own folder; a sweep or clear stops when the open profile changes (stamp checked before the walk and each delete)"),
        "core/src/commonMain/kotlin/com/tmplayer/player/StreamWarmup.kt" to (3 to "warming the next video's bytes, a read the player's closing stops"),
        "core/src/commonMain/kotlin/com/tmplayer/player/TdByteWindow.kt" to (4 to "the player's byte window on its own client, a read that closes with the player"),
        "desktop/src/main/kotlin/com/tmplayer/desktop/DesktopDownloads.kt" to (11 to "the desktop's download queue: each request carries its stamp and rows (DownloadStamps), its fetch takes Td.sessionFor, its cancels and byte reads are of rows a switch stops (stopForSwitch), and TDLib's copy is deleted only while its owner may act"),
        "desktop/src/main/kotlin/com/tmplayer/desktop/DesktopStorage.kt" to (1 to "the housekeeping trim after sign in, of the open profile's own cache; a switch builds the window that runs it again"),
        "desktop/src/main/kotlin/com/tmplayer/desktop/DownloadIndex.kt" to (3 to "a download's delete and its bytes, pressed on the Downloads page of the open profile, or Remove after watching for the profile the mark was made for"),
        "desktop/src/main/kotlin/com/tmplayer/desktop/player/PlayerMedia.kt" to (20 to "the desktop player's reads (Play all and Shuffle read the chat's next videos, WP-32) and its own downloads' cancels; it closes before a switch (BeforeSwitch, PlayerHost) and its writes carry its owner"),
        "desktop/src/main/kotlin/com/tmplayer/desktop/player/PlayerScreen.kt" to (3 to "the desktop player's reads; it closes before a switch and its resume saves carry the owner it opened under"),
        "desktop/src/main/kotlin/com/tmplayer/desktop/ui/DetailPane.kt" to (1 to desktopScreen),
        "desktop/src/main/kotlin/com/tmplayer/desktop/ui/Downloads.kt" to (13 to "presses on the desktop's Downloads page of the open profile"),
        "desktop/src/main/kotlin/com/tmplayer/desktop/ui/GridSelection.kt" to (3 to desktopScreen),
        "desktop/src/main/kotlin/com/tmplayer/desktop/ui/MediaGrid.kt" to (1 to desktopScreen),
        "desktop/src/main/kotlin/com/tmplayer/desktop/ui/SignOut.kt" to (1 to "the sign out without profiles (no accounts file opened); with them it goes through AccountSwitcher.signOutProfile"),
        "desktop/src/main/kotlin/com/tmplayer/desktop/ui/StorageSettings.kt" to (2 to "Free up space and the storage figures of the open profile, on its screen"),

        "ui/src/shared/kotlin/com/tmplayer/data/Thumbnails.kt" to (3 to "pictures for the screen on screen, a read"),
        "ui/src/shared/kotlin/com/tmplayer/ui/browse/AddChatsViewModel.kt" to (1 to screenRead),
        "ui/src/shared/kotlin/com/tmplayer/ui/browse/BrowseViewModel.kt" to (17 to "the chat browser's reads and the sponsored signals of the screen on screen; its view models are per profile"),
        "ui/src/shared/kotlin/com/tmplayer/ui/browse/ChatInfoLoad.kt" to (1 to screenRead),
        "ui/src/shared/kotlin/com/tmplayer/ui/browse/GlobalSearchViewModel.kt" to (2 to screenRead),
        "ui/src/shared/kotlin/com/tmplayer/ui/browse/HomeViewModel.kt" to (3 to "Home's reads, its snapshot stamped"),
        "ui/src/shared/kotlin/com/tmplayer/ui/browse/LinkFlow.kt" to (2 to "a link the viewer pressed, a read"),
        "ui/src/shared/kotlin/com/tmplayer/ui/browse/MoreVideosUi.kt" to (2 to screenRead),
        "ui/src/shared/kotlin/com/tmplayer/ui/browse/TopicFinder.kt" to (2 to screenRead),
        "ui/src/shared/kotlin/com/tmplayer/ui/browse/WholeSeries.kt" to (1 to screenRead),
        "ui/src/shared/kotlin/com/tmplayer/ui/settings/SyncChannelScreen.kt" to (2 to "this account's id on the page on screen; its writes take sessionFor and run as their owner"),
    )

    private fun root(): File {
        val here = File("").absoluteFile
        return if (File(here, "settings.gradle.kts").isFile) here else here.parentFile
    }

    /** Every source set of the four modules but the tests. */
    private fun sourceSets(root: File): List<File> = listOf("core", "ui", "desktop", "app").flatMap { module ->
        File(root, "$module/src").listFiles().orEmpty().filter {
            it.isDirectory && !it.name.lowercase().contains("test")
        }
    }

    private fun counted(line: String): Boolean {
        val t = line.trim()
        if (t.startsWith("//") || t.startsWith("*")) return false
        if (listOf("fun await", "fun deleteFile", "fun cancelDownload", "fun myId", "fun logOut", "fun localDownloadedBytes", "fun currentFileId", "fun trimStorage", "fun dropStalledDownload", "fun clearPicturesAndPreviews").any { it in t }) return false
        return uses.containsMatchIn(t)
    }

    @Test
    fun `no live client is used around Td sessionFor but the listed ones`() {
        val root = root()
        val counts = HashMap<String, Int>()
        for (set in sourceSets(root)) {
            set.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val n = file.readLines().count(::counted)
                if (n > 0) counts[file.relativeTo(root).invariantSeparatorsPath] = n
            }
        }
        val unexpected = counts.filter { (path, n) -> allowed[path]?.first != n }
            .map { (path, n) -> "$path: $n (allowed ${allowed[path]?.first ?: 0})" }
        val gone = allowed.keys.filter { it !in counts }
        assertEquals("route these through Td.sessionFor, or list them with their reason (HX88)", emptyList<String>(), unexpected)
        assertEquals("listed but gone: take them off the list", emptyList<String>(), gone)
    }

    @Test
    fun `the scan reaches the desktop and every source set`() {
        val sets = sourceSets(root()).map { "${it.parentFile.parentFile.name}/${it.name}" }
        assertEquals(true, "desktop/main" in sets)
        assertEquals(true, "app/cast" in sets)
        assertEquals(true, "ui/shared" in sets)
    }
}
