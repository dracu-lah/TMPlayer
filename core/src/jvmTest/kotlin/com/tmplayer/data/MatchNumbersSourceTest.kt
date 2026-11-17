package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A regex match read as a number uses the `OrNull` form, read off the sources (issue 29, a
 * phone that crashed on every start; the likely cause was Sentry's NumberFormatException in
 * MediaName on a Galaxy S25, fixed in 8b71115).
 *
 * On Android `\d` matches every decimal digit of every script, a mathematical bold "𝟎𝟐" too, and
 * `toInt()` refuses those, so the read throws there. On the JVM, the web and iOS `\d` is 0 to 9
 * only, so no test here can feed such a name to the parser and see it throw: the JVM tests
 * passed while phones crashed. Only the sources can say it, so this test reads them: a group
 * value, a match's text, or a name taken from `destructured` is never parsed with a throwing
 * `toInt()`, `toLong()`, `toShort()` or `toByte()`.
 */
class MatchNumbersSourceTest {

    private val root = listOf(File(".."), File(".")).first { File(it, "settings.gradle.kts").isFile }

    private val dirs = listOf(
        "core/src/commonMain", "core/src/pureMain", "core/src/jvmSharedMain", "core/src/webMain",
        "app/src/main", "ui/src/shared", "desktop/src/main",
    )

    private fun sources(): Sequence<File> = dirs.asSequence()
        .map { File(root, it) }
        .filter { it.isDirectory }
        .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" && "/build/" !in it.path } }

    private val throwing = """\??\.to(?:Int|Long|Short|Byte)\(\)"""

    /** A group or a match's text parsed in place. */
    private val direct = listOf(
        Regex("""groupValues\s*\[\s*[0-9]+\s*]$throwing"""),
        Regex("""groups\s*\[\s*[0-9]+\s*]\??\.value$throwing"""),
        Regex("""\b(?:find|matchEntire|matchAt)\([^()]*\)\??\.value$throwing"""),
        Regex("""groupValues\??(?:\.drop\([0-9]+\))?\??\.map\s*\{\s*it$throwing"""),
    )

    private val destructuring = Regex("""val\s*\(([^)]*)\)\s*=\s*[\w.?]+\.destructured\b""")

    /** What [text] parses with a throwing call, as "file:line: the line". */
    private fun offences(name: String, text: String): List<String> {
        val lines = text.lines()
        val found = mutableListOf<String>()
        lines.forEachIndexed { i, line ->
            if (direct.any { it.containsMatchIn(line) }) found += "$name:${i + 1}: ${line.trim()}"
            val names = destructuring.find(line)?.groupValues?.get(1)?.split(',')?.map { it.trim() }
                ?.filter { it.isNotEmpty() && it != "_" }.orEmpty()
            if (names.isEmpty()) return@forEachIndexed
            // The names are used in the lines just after, as they are everywhere in this code.
            val after = lines.subList(i, minOf(lines.size, i + DESTRUCTURED_REACH))
            for (n in names) {
                val read = Regex("""\b${Regex.escape(n)}(?:\.\w+\([^()]*\))*$throwing""")
                after.forEachIndexed { k, l -> if (read.containsMatchIn(l)) found += "$name:${i + k + 1}: ${l.trim()}" }
            }
        }
        return found.distinct()
    }

    @Test
    fun `no regex match is read as a number with a call that throws`() {
        val files = sources().toList()
        assertTrue("the sources were found under ${root.absolutePath}", files.size > 100)
        val found = files.flatMap { offences(it.relativeTo(root).invariantSeparatorsPath, it.readText()) }
            .filterNot { line -> pending.any { line.startsWith("$it:") } }
        assertEquals(
            "use toIntOrNull() or toLongOrNull() and skip the entry (on Android \\d matches digits that toInt() refuses):\n" +
                found.joinToString("\n"),
            emptyList<String>(),
            found,
        )
    }

    @Test
    fun `the check sees each shape it is meant to`() {
        val sample = """
            val a = m.groupValues[2].toLong()
            val b = m.groups[1]?.value?.toInt()
            val c = YEAR.find(text)?.value?.toInt()
            val d = IPV4.matchEntire(host)?.groupValues?.drop(1)?.map { it.toInt() }
            val (seconds, fraction) = match.destructured
            val e = fraction.padEnd(3, '0').take(3).toInt()
            val f = m.groupValues[2].toLongOrNull()
            val g = seconds.toLongOrNull()
        """.trimIndent()
        val found = offences("Sample.kt", sample).map { it.substringBefore(": ") }
        assertEquals(listOf("Sample.kt:1", "Sample.kt:2", "Sample.kt:3", "Sample.kt:4", "Sample.kt:6"), found)
    }

    /**
     * Files known to break the rule, left to the work that owns them; take a file off once fixed.
     * ExternalSubtitle.microDvdToSrt reads `{(\d+)}` with toLong(), which a 20 digit frame number
     * throws on every platform and a mathematical digit on Android (the subtitle work, 2026-11-17).
     */
    private val pending = setOf("app/src/main/java/com/tmplayer/player/ExternalSubtitle.kt")

    private companion object {
        const val DESTRUCTURED_REACH = 8
    }
}
