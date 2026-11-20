package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every notification, media session, cast item, widget and shortcut of the app goes through
 * [ProfileNotifications] and [ProfileMedia], or follows the profile's lock by its own stated rule
 * (HX88), so a PIN profile's titles never reach the shade, the lock screen, the media controls or
 * the launcher while it is hidden. Every source set under app/src but the tests is scanned (the
 * regular build's cast, dev, vlc, the flavours). A hit passes only when it routes through the
 * wrapper, or carries a `profile-notifications:` marker on its line or one of the two above with
 * the rule it follows; a new direct post fails here until it does.
 */
class ProfileNotificationsPolicyTest {

    /** Posting, or naming what plays: each needs the wrapper or its own marked rule. */
    private val direct = listOf(
        Regex("""NotificationCompat\.Builder\("""),
        Regex("""\bNotification\.Builder\("""),
        Regex("""\.notify\("""),
        Regex("""\bstartForeground\("""),
        Regex("""NotificationManagerCompat"""),
        Regex("""\bMediaSession\.Builder\("""),
        Regex("""\bMediaSession\("""),
        Regex("""MediaSessionCompat"""),
        Regex("""MediaLibrarySession"""),
        Regex("""MediaBrowserService"""),
        Regex("""MediaLibraryService"""),
        Regex("""(?<!ProfileMedia)\.setMetadata\("""),
        Regex("""\.setMediaMetadata\("""),
        Regex("""MediaMetadataCompat"""),
        Regex("""MediaInfo\.Builder\("""),
        Regex("""CastMediaOptions\.Builder\("""),
        Regex("""\.setCastMediaOptions\("""),
        Regex("""\bRemoteViews\("""),
        Regex("""setDynamicShortcuts|ShortcutManager|ShortcutInfo"""),
        // The launcher: the TV's Play next row and channels, and the home screen widget.
        Regex("""contentResolver\.(insert|update|delete|bulkInsert)\("""),
        Regex("""requestChannelBrowsable"""),
        Regex("""\b(updateAppWidget|partiallyUpdateAppWidget)\("""),
    )

    /** A notification's own words: fine in a file that posts through the wrapper, else marked. */
    private val content = listOf(Regex("""\.setContentTitle\("""))

    /** The choke point itself, the one file whose posts are the wrapper's. */
    private val wrapper = "com/tmplayer/data/ProfileNotifications.kt"

    private fun appSrc(): File = listOf(File("src"), File("app/src")).first { File(it, "main").isDirectory }

    /**
     * Every source set but the tests (main, cast, nocast, vlc, novlc, dev, debug, preview, promo
     * and its shared folder, fdroid), and :ui's synthetic account, which :app's debug build uses.
     */
    private fun sourceRoots(): List<File> {
        val app = appSrc().listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith("test") && !it.name.startsWith("androidTest") }
            .flatMap { set -> listOf(File(set, "java"), File(set, "kotlin"), File(set, "shared")).filter { it.isDirectory } }
        val ui = listOf(File(appSrc().absoluteFile.parentFile.parentFile, "ui/src/synthetic/kotlin")).filter { it.isDirectory }
        return app + ui
    }

    private fun marked(lines: List<String>, index: Int): Boolean =
        (maxOf(0, index - 2)..index).any { lines[it].contains("profile-notifications:") }

    private fun code(line: String): Boolean {
        val t = line.trim()
        return !(t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("import "))
    }

    @Test
    fun `nothing posts, names what plays or fills a launcher surface around the choke point`() {
        val found = ArrayList<String>()
        for (root in sourceRoots()) {
            for (file in root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }) {
                val path = file.relativeTo(root).invariantSeparatorsPath
                if (path == wrapper) continue
                val lines = file.readLines()
                val routes = lines.any { it.contains("ProfileNotifications.") }
                lines.forEachIndexed { index, line ->
                    if (!code(line)) return@forEachIndexed
                    val hit = direct.any { it.containsMatchIn(line) } || (!routes && content.any { it.containsMatchIn(line) })
                    if (hit && !marked(lines, index)) found += "${root.parentFile.name}/$path:${index + 1}: ${line.trim()}"
                }
            }
        }
        assertEquals(
            "route these through ProfileNotifications or ProfileMedia (HX88), or mark each with `// profile-notifications: <its rule>`",
            emptyList<String>(),
            found,
        )
    }

    @Test
    fun `the scan reaches every source set, the cast build's included`() {
        val sets = sourceRoots().map { it.parentFile.name }.toSet()
        assertTrue("main is scanned", "main" in sets)
        assertTrue("the regular build's Google Cast is scanned", "cast" in sets)
        assertTrue("the promo build's shared folder is scanned", sourceRoots().any { it.name == "shared" && it.parentFile.name == "promo" })
        assertTrue(":ui's synthetic account is scanned", sourceRoots().any { it.path.endsWith("synthetic/kotlin") })
        assertTrue(File(appSrc(), "main/java/$wrapper").isFile)
    }

    @Test
    fun `a direct post without a marker is caught`() {
        val lines = listOf("    fun f() {", "        manager.notify(1, n)", "    }")
        assertTrue(direct.any { it.containsMatchIn(lines[1]) } && !marked(lines, 1))
        val ok = listOf("        // profile-notifications: a reason", "        manager.notify(1, n)")
        assertTrue(marked(ok, 1))
    }
}
