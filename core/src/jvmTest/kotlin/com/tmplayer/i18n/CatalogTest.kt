package com.tmplayer.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The catalogs on disk, checked the way the app will read them: English is the source of truth,
 * every other file may only translate its keys, with the same arguments, in valid ICU, and no
 * file may carry an em or en dash. Runs over whatever locale files exist, so a translation pass
 * that breaks one fails here and not on somebody's television.
 */
class CatalogTest {

    // Gradle runs the tests from the module directory.
    private val dir = File("src/commonMain/resources/i18n")
    private val englishFile = File(dir, "en.json")
    private val english: Map<String, String> = Translator.parse(englishFile.readText())

    private fun catalogs(): List<File> = dir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }

    @Test
    fun `the english catalog is on the classpath and matches the source`() {
        assertEquals(english, Translator.parse(Translator.resource("en")!!))
    }

    @Test
    fun `every english message is valid icu`() {
        for ((key, text) in english) {
            runCatching { Icu.parse(text) }.onFailure { fail("en $key: ${it.message}") }
        }
    }

    @Test
    fun `keys are lower snake case under a namespace`() {
        val shape = Regex("[a-z][a-z0-9]*(\\.[a-z][a-z0-9_]*)+")
        for (key in english.keys) assertTrue("bad key $key", shape.matches(key))
    }

    @Test
    fun `the generated accessors cover the catalog exactly`() {
        assertEquals(english.keys.sorted(), CATALOG_KEYS)
        val members = Strings::class.java.declaredMethods.map { it.name }.toSet()
        for ((key, text) in english) {
            val words = key.split('.', '_')
            val name = words.first() + words.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
            val args = Icu.parse(text).args
            val method = if (args.isEmpty()) "get" + name.replaceFirstChar(Char::uppercaseChar) else name
            assertTrue("no accessor $method for $key", method in members)
            if (args.isNotEmpty()) {
                val arity = Strings::class.java.declaredMethods.first { it.name == method }.parameterCount
                assertEquals("$key arguments", args.size, arity)
            }
        }
    }

    @Test
    fun `every catalog translates only english keys with the same arguments`() {
        val reference = english.mapValues { Icu.parse(it.value).args }
        for (file in catalogs()) {
            val tag = file.nameWithoutExtension
            assertTrue("$tag is not a shipped language", tag in Languages.tags)
            val entries = Translator.parse(file.readText())
            for ((key, text) in entries) {
                val expected = reference[key] ?: throw AssertionError("$tag has $key, which English does not")
                val pattern = runCatching { Icu.parse(text) }.getOrElse { throw AssertionError("$tag $key: ${it.message}") }
                assertEquals("$tag $key arguments", expected.keys, pattern.args.keys)
                assertEquals("$tag $key argument kinds", expected, pattern.args)
                // Every plural has a case for each category the language has: Russian without
                // "few" would say "2 видео" in the form meant for 5. Extra cases are harmless.
                val needed = PluralRules.categories(tag)
                for (plural in plurals(pattern.parts)) {
                    val missing = needed - plural.cases.keys
                    assertTrue("$tag $key has no case for $missing", missing.isEmpty())
                }
            }
        }
    }

    private fun plurals(parts: List<Icu.Part>): List<Icu.Plural> = parts.flatMap { part ->
        when (part) {
            is Icu.Plural -> listOf(part) + part.cases.values.flatMap(::plurals)
            is Icu.Select -> part.cases.values.flatMap(::plurals)
            else -> emptyList()
        }
    }

    @Test
    fun `no catalog carries an em or en dash`() {
        for (file in catalogs()) {
            for ((key, text) in Translator.parse(file.readText())) {
                assertTrue("${file.name} $key has a dash", '\u2014' !in text && '\u2013' !in text)
            }
        }
    }

    @Test
    fun `no catalog has an empty message or stray whitespace`() {
        for (file in catalogs()) {
            for ((key, text) in Translator.parse(file.readText())) {
                assertTrue("${file.name} $key is empty", text.isNotBlank())
                assertEquals("${file.name} $key has stray whitespace", text.trim(), text)
            }
        }
    }

    @Test
    fun `english keys are sorted so diffs stay small`() {
        // org.json does not keep the file's order, so the order is read off the lines.
        var namespace = ""
        val keys = mutableListOf<String>()
        for (line in englishFile.readLines()) {
            Regex("^  \"([^\"]+)\": \\{").find(line)?.let { namespace = it.groupValues[1] }
            Regex("^    \"([^\"]+)\": \"").find(line)?.let { keys += "$namespace.${it.groupValues[1]}" }
        }
        assertEquals(english.keys.sorted(), keys.sorted())
        assertEquals(keys.sorted(), keys)
    }
}
