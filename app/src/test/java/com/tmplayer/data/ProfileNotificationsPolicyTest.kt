package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every notification and media session of the app goes through [ProfileNotifications] and
 * [ProfileMedia] (HX88), so a PIN profile's titles never reach the shade, the lock screen or the
 * media controls while it is hidden. A new direct post fails here until it is routed, or listed
 * below with its reason.
 */
class ProfileNotificationsPolicyTest {

    private val patterns = listOf(
        Regex("""NotificationCompat\.Builder\("""),
        Regex("""\bNotification\.Builder\("""),
        Regex("""\.notify\("""),
        Regex("""\bstartForeground\("""),
        Regex("""\bMediaSession\.Builder\("""),
        Regex("""\bMediaSession\("""),
        Regex("""(?<!ProfileMedia)\.setMetadata\("""),
        Regex("""NotificationManagerCompat"""),
    )

    /** File, the pattern's text, and why it may. */
    private val allowed = mapOf(
        "com/tmplayer/data/ProfileNotifications.kt" to "the choke point itself",
        "com/tmplayer/data/LegacyApiCompat.kt" to "the builder factory: builds, posts nothing",
        "com/tmplayer/player/PlayerActivity.kt" to "the Media3 session: its metadata is private while the profile is hidden (sessionMetadata, ProfileUnlock.hidden)",
        "com/tmplayer/cast/DlnaCastService.kt" to "the cast's framework session is made here; its metadata goes through ProfileMedia",
    )

    private fun sources(): List<File> {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun `no notification or media session is posted around the choke point`() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val found = ArrayList<String>()
        for (file in sources()) {
            val path = file.relativeTo(root).invariantSeparatorsPath
            if (path in allowed) continue
            file.readLines().forEachIndexed { index, raw ->
                val line = raw.trim()
                if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) return@forEachIndexed
                if (patterns.any { it.containsMatchIn(line) }) found += "$path:${index + 1}: $line"
            }
        }
        assertEquals("route these through ProfileNotifications or ProfileMedia (HX88), or list them with a reason", emptyList<String>(), found)
    }

    @Test
    fun `every listed exception still exists, so the list cannot rot`() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        for (path in allowed.keys) assertTrue("$path is gone: take it off the list", File(root, path).isFile)
    }
}
