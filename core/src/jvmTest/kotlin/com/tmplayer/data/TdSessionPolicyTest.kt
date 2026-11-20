package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Which code may take a Telegram session without its owner (HX88). A job that writes to Telegram,
 * or runs past a switch, takes its session through [Td.sessionFor], bound to its profile; a
 * session taken any other way could be the next profile's client. The waits left below read for
 * the screen on screen, or for a player that closes before a switch, and are listed with why.
 * A new one fails here until it goes through [Td.sessionFor] or is listed with its reason.
 */
class TdSessionPolicyTest {

    private val wait = Regex("""awaitConnectedSessionOrNull\(|awaitConnectedSession\(\)|awaitAuthorizedSession\(\)""")

    /** File, how many waits it has, and why each is safe. */
    private val allowed = mapOf(
        "core/src/commonMain/kotlin/com/tmplayer/data/Td.kt" to (3 to "the waits themselves, and sessionFor over them"),
        "core/src/commonMain/kotlin/com/tmplayer/data/AccountSwitcher.kt" to (1 to "who signed in: checked against the client generation it came from"),
        "core/src/commonMain/kotlin/com/tmplayer/data/AccountSessions.kt" to (1 to "the Devices page's read; its ends go through sessionFor"),
        "core/src/commonMain/kotlin/com/tmplayer/data/ChatSubtitlesTd.kt" to (1 to "subtitles for the video on screen, a read; its answers are keyed by the switch counter"),
        "core/src/commonMain/kotlin/com/tmplayer/data/PageQueries.kt" to (1 to "a chat grid's read; its cache is stamped and dropped on a switch"),
        "core/src/commonMain/kotlin/com/tmplayer/data/ChatRepository.kt" to (1 to "Saved Messages' id, a read"),
        "core/src/commonMain/kotlin/com/tmplayer/player/StreamWarmup.kt" to (1 to "warming the next video's bytes, a read that the player's closing stops"),
        "ui/src/shared/kotlin/com/tmplayer/ui/browse/TopicFinder.kt" to (2 to "a screen's read, in the shell keyed on the open profile"),
        "ui/src/shared/kotlin/com/tmplayer/ui/browse/HomeViewModel.kt" to (2 to "Home's reads, its snapshot stamped"),
        "ui/src/shared/kotlin/com/tmplayer/ui/browse/ChatInfoLoad.kt" to (1 to "a screen's read"),
        "ui/src/shared/kotlin/com/tmplayer/ui/browse/MoreVideosUi.kt" to (2 to "a screen's read"),
        "ui/src/shared/kotlin/com/tmplayer/ui/browse/BrowseViewModel.kt" to (22 to "the chat browser's reads and the sponsored signals of the screen on screen; the view models are per profile"),
        "ui/src/shared/kotlin/com/tmplayer/data/Thumbnails.kt" to (1 to "a picture's read"),
        "ui/src/shared/kotlin/com/tmplayer/ui/browse/GlobalSearchViewModel.kt" to (2 to "search, a read"),
        "ui/src/shared/kotlin/com/tmplayer/ui/browse/LinkFlow.kt" to (2 to "opening a link the viewer pressed, a read"),
        "ui/src/shared/kotlin/com/tmplayer/ui/browse/WholeSeries.kt" to (1 to "a series' read"),
        "ui/src/shared/kotlin/com/tmplayer/ui/browse/AddChatsViewModel.kt" to (1 to "the picker's video counts, a read"),
        "app/src/main/java/com/tmplayer/App.kt" to (1 to "the storage trim after sign in, of the open profile's own cache"),
        "app/src/main/java/com/tmplayer/player/AndroidPlaybackTestEnv.kt" to (1 to "the Playback test, a read the viewer started and watches"),
        "app/src/main/java/com/tmplayer/player/ExternalPlayer.kt" to (1 to "a video handed to another player, a read; the stream stops on a switch"),
        "app/src/main/java/com/tmplayer/player/ExternalPlayRouter.kt" to (1 to "a local path's read"),
        "app/src/main/java/com/tmplayer/player/ExternalStreams.kt" to (1 to "the stream's read; it stops on a switch"),
        "app/src/main/java/com/tmplayer/player/PlayerActivity.kt" to (12 to "the player's reads; it closes before a switch (BeforeSwitch) and its writes carry its owner"),
        "app/src/dev/java/com/tmplayer/dev/NameDumpReceiver.kt" to (1 to "the dev build's name dump, a read"),
    )

    private fun root(): File {
        val here = File("").absoluteFile
        return if (File(here, "settings.gradle.kts").isFile) here else here.parentFile
    }

    @Test
    fun `no session is taken around Td sessionFor but the listed reads`() {
        val root = root()
        val dirs = listOf("core/src/commonMain", "ui/src/shared") +
            File(root, "app/src").listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith("test") }.map { "app/src/${it.name}" }
        val counts = HashMap<String, Int>()
        for (dir in dirs) {
            File(root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val n = file.readLines().count { line ->
                    val t = line.trim()
                    !t.startsWith("//") && !t.startsWith("*") && wait.containsMatchIn(t) && !t.contains("fun await")
                }
                if (n > 0) counts[file.relativeTo(root).invariantSeparatorsPath] = n
            }
        }
        val unexpected = counts.filter { (path, n) -> allowed[path]?.first != n }
            .map { (path, n) -> "$path: $n (allowed ${allowed[path]?.first ?: 0})" }
        val gone = allowed.keys.filter { it !in counts }
        assertEquals("route new waits through Td.sessionFor, or list them with their reason (HX88)", emptyList<String>(), unexpected)
        assertEquals("listed but gone: take them off the list", emptyList<String>(), gone)
    }
}
