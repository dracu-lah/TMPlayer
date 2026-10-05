package com.tmplayer.i18n

/**
 * The part of ICU MessageFormat the catalogs use, parsed without ICU4J (which would add megabytes
 * to every package for three constructs):
 *
 * - `{name}`, a value put in as it is (a number goes through the locale's number format);
 * - `{count, plural, =0 {...} one {# video} other {# videos}}`, with an optional `offset:n`, exact
 *   `=n` cases first and then the CLDR category of the language, falling back to `other`;
 * - `{kind, select, audio {...} other {...}}`.
 *
 * Quoting follows ICU's default: `''` is one apostrophe, and an apostrophe directly before a `{`,
 * `}` or (inside a plural) `#` starts quoted literal text up to the next lone apostrophe. Any other
 * apostrophe is just an apostrophe, so "Can't" and "l'application" need no escaping.
 */
object Icu {

    /** What the formatter expects for an argument, which also decides its Kotlin type. */
    enum class ArgKind { VALUE, PLURAL, SELECT }

    sealed interface Part
    data class Text(val text: String) : Part
    data class Value(val name: String) : Part
    /** `#` inside a plural: the number, less the offset, in the locale's format. */
    data object Pound : Part
    data class Plural(val name: String, val offset: Int, val cases: Map<String, List<Part>>) : Part
    data class Select(val name: String, val cases: Map<String, List<Part>>) : Part

    class Pattern(val parts: List<Part>) {
        /** Every argument in order of first appearance, with the strongest kind it is used as. */
        val args: Map<String, ArgKind> by lazy {
            val out = LinkedHashMap<String, ArgKind>()
            fun walk(parts: List<Part>) {
                for (part in parts) when (part) {
                    is Value -> out.putIfAbsent(part.name, ArgKind.VALUE)
                    is Plural -> {
                        out[part.name] = ArgKind.PLURAL
                        part.cases.values.forEach(::walk)
                    }
                    is Select -> {
                        out[part.name] = ArgKind.SELECT
                        part.cases.values.forEach(::walk)
                    }
                    is Text, Pound -> Unit
                }
            }
            walk(parts)
            out
        }

        /** Every piece of literal text, for checks and pseudo-localization. */
        fun texts(): List<String> {
            val out = mutableListOf<String>()
            fun walk(parts: List<Part>) {
                for (part in parts) when (part) {
                    is Text -> out += part.text
                    is Plural -> part.cases.values.forEach(::walk)
                    is Select -> part.cases.values.forEach(::walk)
                    else -> Unit
                }
            }
            walk(parts)
            return out
        }

        /** The same pattern with every literal text passed through [transform]. */
        fun mapText(transform: (String) -> String): Pattern {
            fun walk(parts: List<Part>): List<Part> = parts.map { part ->
                when (part) {
                    is Text -> Text(transform(part.text))
                    is Plural -> part.copy(cases = part.cases.mapValues { walk(it.value) })
                    is Select -> part.copy(cases = part.cases.mapValues { walk(it.value) })
                    else -> part
                }
            }
            return Pattern(walk(parts))
        }
    }

    class SyntaxException(message: String, val source: String, val position: Int) :
        IllegalArgumentException("$message at $position in \"$source\"")

    /** Parses [source], throwing [SyntaxException] for anything outside the subset. */
    fun parse(source: String): Pattern = Parser(source).run {
        val parts = message()
        if (pos < source.length) fail("Unmatched }")
        Pattern(parts)
    }

    val PLURAL_KEYS = setOf("zero", "one", "two", "few", "many", "other")

    private class Parser(val src: String) {
        var pos = 0
        /** How many plurals enclose the current position; `#` is special only inside one. */
        var pluralDepth = 0

        fun fail(message: String): Nothing = throw SyntaxException(message, src, pos)

        /** Text and arguments up to an unquoted `}` or the end. */
        fun message(): List<Part> {
            val inPlural = pluralDepth > 0
            val parts = mutableListOf<Part>()
            val text = StringBuilder()
            fun flush() {
                if (text.isNotEmpty()) {
                    parts += Text(text.toString())
                    text.clear()
                }
            }
            while (pos < src.length) {
                val c = src[pos]
                when {
                    c == '\'' -> quoted(text, inPlural)
                    c == '{' -> {
                        flush()
                        parts += argument()
                    }
                    c == '}' -> break
                    c == '#' && inPlural -> {
                        flush()
                        parts += Pound
                        pos++
                    }
                    else -> {
                        text.append(c)
                        pos++
                    }
                }
            }
            flush()
            return parts
        }

        fun quoted(text: StringBuilder, inPlural: Boolean) {
            val next = src.getOrNull(pos + 1)
            when {
                next == '\'' -> {
                    text.append('\'')
                    pos += 2
                }
                next == '{' || next == '}' || (inPlural && next == '#') -> {
                    pos++
                    while (true) {
                        if (pos >= src.length) fail("Unterminated quote")
                        val c = src[pos]
                        if (c == '\'') {
                            if (src.getOrNull(pos + 1) == '\'') {
                                text.append('\'')
                                pos += 2
                                continue
                            }
                            pos++
                            break
                        }
                        text.append(c)
                        pos++
                    }
                }
                else -> {
                    text.append('\'')
                    pos++
                }
            }
        }

        fun skipSpace() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        fun identifier(what: String): String {
            skipSpace()
            val start = pos
            while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
            if (start == pos) fail("Expected $what")
            return src.substring(start, pos)
        }

        fun expect(c: Char) {
            skipSpace()
            if (src.getOrNull(pos) != c) fail("Expected '$c'")
            pos++
        }

        fun argument(): Part {
            expect('{')
            val name = identifier("an argument name")
            if (!name.first().isLetter()) fail("Argument names start with a letter")
            skipSpace()
            if (src.getOrNull(pos) == '}') {
                pos++
                return Value(name)
            }
            expect(',')
            val type = identifier("plural or select")
            expect(',')
            val part = when (type) {
                "plural" -> {
                    skipSpace()
                    var offset = 0
                    if (src.startsWith("offset:", pos)) {
                        pos += "offset:".length
                        offset = identifier("an offset").toIntOrNull() ?: fail("Offset is not a number")
                    }
                    Plural(name, offset, cases(plural = true))
                }
                "select" -> Select(name, cases(plural = false))
                else -> fail("Unsupported argument type '$type'")
            }
            expect('}')
            return part
        }

        fun cases(plural: Boolean): Map<String, List<Part>> {
            val cases = LinkedHashMap<String, List<Part>>()
            while (true) {
                skipSpace()
                if (pos >= src.length) fail("Unterminated argument")
                if (src[pos] == '}') break
                val key = if (plural && src[pos] == '=') {
                    pos++
                    "=" + (identifier("a number").toIntOrNull() ?: fail("Exact case is not a number"))
                } else {
                    identifier("a case keyword")
                }
                if (plural && !key.startsWith("=") && key !in PLURAL_KEYS) fail("Unknown plural category '$key'")
                if (key in cases) fail("Duplicate case '$key'")
                expect('{')
                if (plural) pluralDepth++
                cases[key] = message()
                if (plural) pluralDepth--
                if (src.getOrNull(pos) != '}') fail("Unterminated case '$key'")
                pos++
            }
            if ("other" !in cases) fail("Missing 'other' case")
            return cases
        }
    }
}
