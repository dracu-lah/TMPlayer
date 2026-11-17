// Kotlin/Native has no @Synchronized: on iOS the annotation compiles (a stand in) and locks
// nothing. :core and :ui compile, for iOS only, copies of upstream's files that use it, each
// `@Synchronized fun` rewritten as the same function holding `synchronized(this)` around its body,
// which on the JVM is what the annotation does. Upstream's files stay as they are
// (docs/IOS.md, "Chosen instead, for now").
//
// Applied with apply(from = ...); the build reads the rewrite back as
// `extra["withRealLocks"] as (String) -> String`.

/** Index just past the string, char or comment that starts at [start], or [start] if none does. */
fun skipLiteral(text: String, start: Int): Int {
    if (text.startsWith("//", start)) return text.indexOf('\n', start).let { if (it < 0) text.length else it }
    if (text.startsWith("/*", start)) return text.indexOf("*/", start + 2).let { if (it < 0) text.length else it + 2 }
    if (text.startsWith("\"\"\"", start)) {
        var i = start + 3
        while (i < text.length && !text.startsWith("\"\"\"", i)) {
            i = if (text.startsWith("\${", i)) closing(text, i + 1) + 1 else i + 1
        }
        return minOf(text.length, i + 3)
    }
    val quote = text[start]
    if (quote != '"' && quote != '\'') return start
    var i = start + 1
    while (i < text.length && text[i] != quote) {
        i = when {
            text[i] == '\\' -> i + 2
            quote == '"' && text.startsWith("\${", i) -> closing(text, i + 1) + 1
            else -> i + 1
        }
    }
    return minOf(text.length, i + 1)
}

/** Index of the bracket that closes the one at [open], skipping strings and comments. */
fun closing(text: String, open: Int): Int {
    val pairs = mapOf('(' to ')', '[' to ']', '{' to '}')
    val stack = ArrayList<Char>()
    var i = open
    while (i < text.length) {
        val skipped = skipLiteral(text, i)
        if (skipped != i) {
            i = skipped
            continue
        }
        val c = text[i]
        when {
            c in pairs.keys -> stack += pairs.getValue(c)
            stack.isNotEmpty() && c == stack.last() -> {
                stack.removeAt(stack.size - 1)
                if (stack.isEmpty()) return i
            }
        }
        i++
    }
    error("No closing bracket for the one at $open")
}

/** Where an expression body that starts at [start] ends: a line break outside brackets, not continued. */
fun expressionEnd(text: String, start: Int): Int {
    // A next line that carries on with an operator; a comment's opening is not a division.
    val continuesLine = Regex("""^\s*(\.|\?\.|\?:|&&|\|\||\+|-|\*(?!/)|/(?![/*])|:|as\b|is\b|in\b)""")
    var i = start
    while (i < text.length) {
        val skipped = skipLiteral(text, i)
        if (skipped != i) {
            i = skipped
            continue
        }
        val c = text[i]
        if (c == '(' || c == '[' || c == '{') {
            i = closing(text, i) + 1
            continue
        }
        if (c == '\n') {
            val before = text.substring(start, i).trimEnd()
            val operatorAtEnd = before.isEmpty() || before.last() in "=+-*/.,(&|:>?"
            val after = text.substring(i + 1).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            if (!operatorAtEnd && !continuesLine.containsMatchIn(after)) return i
        }
        i++
    }
    return text.length
}

/** [source] with every `@Synchronized fun` holding `synchronized(this)` for its whole body. */
fun withRealLocks(source: String): String {
    val annotation = Regex("""@Synchronized\b[ \t]*""")
    val out = StringBuilder()
    var from = 0
    while (true) {
        val found = annotation.find(source, from) ?: break
        // Only the annotation itself, first on its line: not a mention in a comment or KDoc.
        val lineStart = source.lastIndexOf('\n', found.range.first) + 1
        if (source.substring(lineStart, found.range.first).isNotBlank()) {
            out.append(source, from, found.range.last + 1)
            from = found.range.last + 1
            continue
        }
        out.append(source, from, found.range.first)
        val declaration = found.range.last + 1
        val fn = Regex("""\bfun\b""").find(source, declaration) ?: error("@Synchronized without a function")
        val parameters = closing(source, source.indexOf('(', fn.range.last))
        var body = parameters + 1
        while (body < source.length && source[body] != '{' && source[body] != '=') body++
        out.append(source, declaration, body)
        if (source[body] == '{') {
            val end = closing(source, body)
            out.append("{ return synchronized(this) {").append(source, body + 1, end).append("} }")
            from = end + 1
        } else {
            val end = expressionEnd(source, body + 1)
            out.append("= synchronized(this) {").append(source, body + 1, end).append(" }")
            from = end
        }
    }
    out.append(source, from, source.length)
    return out.toString()
}

extra["withRealLocks"] = { source: String -> withRealLocks(source) }
