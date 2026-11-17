package org.json

// org.json on iOS, following the reference implementation the desktop build links (org.json
// 20240303) for what core calls: opt* coerce numbers and numeric strings and fall back on a
// missing key or a null, get* throw JSONException instead, put with a null value removes the key,
// and a whole Double is written without ".0". Objects keep insertion order, as Android's do.

class JSONException(message: String?, cause: Throwable? = null) : RuntimeException(message, cause)

private object JsonNull {
    override fun toString(): String = "null"
    override fun equals(other: Any?): Boolean = other == null || other === this
    override fun hashCode(): Int = 0
}

private fun isNull(value: Any?): Boolean = value == null || value === JsonNull

private fun wrap(value: Any?): Any? = when (value) {
    null -> JsonNull
    is JSONObject, is JSONArray, is String, is Boolean, is Int, is Long, is Short, is Byte -> value
    is Double -> value.also { if (it.isNaN() || it.isInfinite()) throw JSONException("JSON does not allow non-finite numbers.") }
    is Float -> value.toDouble().also { if (it.isNaN() || it.isInfinite()) throw JSONException("JSON does not allow non-finite numbers.") }
    is Number -> value
    is Char -> value.toString()
    is Map<*, *> -> JSONObject(value)
    is Collection<*> -> JSONArray(value)
    is Array<*> -> JSONArray(value.toList())
    is Enum<*> -> value.name
    else -> if (value === JsonNull) value else value.toString()
}

private fun toLong(value: Any?): Long? = when (value) {
    is Number -> if (value is Double || value is Float) value.toDouble().takeIf { !it.isNaN() }?.toLong() else value.toLong()
    is String -> value.trim().let { it.toLongOrNull() ?: it.toDoubleOrNull()?.takeIf { d -> !d.isNaN() }?.toLong() }
    else -> null
}

private fun toDouble(value: Any?): Double? = when (value) {
    is Number -> value.toDouble()
    is String -> value.trim().toDoubleOrNull()
    else -> null
}

private fun toBoolean(value: Any?): Boolean? = when (value) {
    is Boolean -> value
    is String -> when {
        value.equals("true", ignoreCase = true) -> true
        value.equals("false", ignoreCase = true) -> false
        else -> null
    }
    else -> null
}

class JSONObject() {
    private val map = LinkedHashMap<String, Any>()

    constructor(source: String) : this(JSONTokener(source))

    constructor(tokener: JSONTokener) : this() {
        val value = tokener.nextValue()
        if (value !is JSONObject) throw JSONException("A JSONObject text must begin with '{'")
        map.putAll(value.map)
    }

    constructor(source: Map<*, *>) : this() {
        for ((key, value) in source) if (key != null && value != null) map[key.toString()] = wrap(value)!!
    }

    fun length(): Int = map.size
    fun isEmpty(): Boolean = map.isEmpty()
    fun has(name: String): Boolean = map.containsKey(name)
    fun isNull(name: String): Boolean = isNull(map[name])
    fun keys(): Iterator<String> = map.keys.toList().iterator()
    fun keySet(): MutableSet<String> = map.keys
    fun names(): JSONArray? = if (map.isEmpty()) null else JSONArray(map.keys.toList())

    fun opt(name: String?): Any? = name?.let { map[it] }

    fun get(name: String): Any = map[name] ?: throw JSONException("JSONObject[\"$name\"] not found.")

    fun put(name: String, value: Any?): JSONObject {
        if (value == null) map.remove(name) else map[name] = wrap(value)!!
        return this
    }

    fun putOpt(name: String?, value: Any?): JSONObject {
        if (name != null && value != null) put(name, value)
        return this
    }

    fun remove(name: String): Any? = map.remove(name)

    fun optString(name: String): String = optString(name, "")
    fun optString(name: String, fallback: String): String = map[name].let { if (isNull(it)) fallback else it.toString() }
    fun optInt(name: String): Int = optInt(name, 0)
    fun optInt(name: String, fallback: Int): Int = toLong(map[name])?.toInt() ?: fallback
    fun optLong(name: String): Long = optLong(name, 0L)
    fun optLong(name: String, fallback: Long): Long = toLong(map[name]) ?: fallback
    fun optDouble(name: String): Double = optDouble(name, Double.NaN)
    fun optDouble(name: String, fallback: Double): Double = toDouble(map[name]) ?: fallback
    fun optBoolean(name: String): Boolean = optBoolean(name, false)
    fun optBoolean(name: String, fallback: Boolean): Boolean = toBoolean(map[name]) ?: fallback
    fun optJSONObject(name: String): JSONObject? = map[name] as? JSONObject
    fun optJSONArray(name: String): JSONArray? = map[name] as? JSONArray

    fun getString(name: String): String = get(name) as? String ?: throw JSONException("JSONObject[\"$name\"] is not a string.")
    fun getInt(name: String): Int = toLong(get(name))?.toInt() ?: throw JSONException("JSONObject[\"$name\"] is not an int.")
    fun getLong(name: String): Long = toLong(get(name)) ?: throw JSONException("JSONObject[\"$name\"] is not a long.")
    fun getDouble(name: String): Double = toDouble(get(name)) ?: throw JSONException("JSONObject[\"$name\"] is not a double.")
    fun getBoolean(name: String): Boolean = toBoolean(get(name)) ?: throw JSONException("JSONObject[\"$name\"] is not a Boolean.")
    fun getJSONObject(name: String): JSONObject = get(name) as? JSONObject ?: throw JSONException("JSONObject[\"$name\"] is not a JSONObject.")
    fun getJSONArray(name: String): JSONArray = get(name) as? JSONArray ?: throw JSONException("JSONObject[\"$name\"] is not a JSONArray.")

    fun toMap(): Map<String, Any?> = map.mapValues { (_, v) -> unwrap(v) }

    override fun toString(): String = StringBuilder().also { write(it, 0, 0) }.toString()
    fun toString(indentFactor: Int): String = StringBuilder().also { write(it, indentFactor, 0) }.toString()

    internal fun write(out: StringBuilder, indent: Int, depth: Int) {
        out.append('{')
        var first = true
        for ((key, value) in map) {
            if (!first) out.append(',')
            first = false
            newline(out, indent, depth + 1)
            out.append(quote(key)).append(':')
            if (indent > 0) out.append(' ')
            writeValue(out, value, indent, depth + 1)
        }
        if (!first) newline(out, indent, depth)
        out.append('}')
    }

    companion object {
        val NULL: Any = JsonNull

        fun quote(text: String?): String = buildString {
            if (text == null) {
                append("\"\"")
                return@buildString
            }
            append('"')
            var previous = 0.toChar()
            for (c in text) {
                when {
                    c == '"' || c == '\\' -> append('\\').append(c)
                    c == '/' && previous == '<' -> append("\\/")
                    c == '\b' -> append("\\b")
                    c == '\t' -> append("\\t")
                    c == '\n' -> append("\\n")
                    c == '\u000C' -> append("\\f")
                    c == '\r' -> append("\\r")
                    c < ' ' || c in '\u0080'..'\u009F' || c in ' '..'⃿' ->
                        append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    else -> append(c)
                }
                previous = c
            }
            append('"')
        }

        fun numberToString(number: Number): String = when (number) {
            is Double, is Float -> {
                val text = number.toDouble().toString()
                if ('.' in text && 'e' !in text && 'E' !in text) text.trimEnd('0').trimEnd('.') else text
            }
            else -> number.toString()
        }
    }
}

class JSONArray() : Iterable<Any?> {
    private val list = ArrayList<Any>()

    constructor(source: String) : this(JSONTokener(source))

    constructor(tokener: JSONTokener) : this() {
        val value = tokener.nextValue()
        if (value !is JSONArray) throw JSONException("A JSONArray text must start with '['")
        list.addAll(value.list)
    }

    constructor(source: Collection<*>) : this() {
        source.forEach { list += wrap(it)!! }
    }

    fun length(): Int = list.size
    fun isEmpty(): Boolean = list.isEmpty()
    fun isNull(index: Int): Boolean = isNull(list.getOrNull(index))
    fun opt(index: Int): Any? = list.getOrNull(index)
    fun get(index: Int): Any = list.getOrNull(index) ?: throw JSONException("JSONArray[$index] not found.")

    fun put(value: Any?): JSONArray = apply { list += wrap(value)!! }

    fun put(index: Int, value: Any?): JSONArray = apply {
        if (index < 0) throw JSONException("JSONArray[$index] not found.")
        while (list.size < index) list += JsonNull
        if (index == list.size) list += wrap(value)!! else list[index] = wrap(value)!!
    }

    fun remove(index: Int): Any? = if (index in list.indices) list.removeAt(index) else null

    fun optString(index: Int): String = optString(index, "")
    fun optString(index: Int, fallback: String): String = opt(index).let { if (isNull(it)) fallback else it.toString() }
    fun optInt(index: Int): Int = optInt(index, 0)
    fun optInt(index: Int, fallback: Int): Int = toLong(opt(index))?.toInt() ?: fallback
    fun optLong(index: Int): Long = optLong(index, 0L)
    fun optLong(index: Int, fallback: Long): Long = toLong(opt(index)) ?: fallback
    fun optDouble(index: Int): Double = optDouble(index, Double.NaN)
    fun optDouble(index: Int, fallback: Double): Double = toDouble(opt(index)) ?: fallback
    fun optBoolean(index: Int): Boolean = optBoolean(index, false)
    fun optBoolean(index: Int, fallback: Boolean): Boolean = toBoolean(opt(index)) ?: fallback
    fun optJSONObject(index: Int): JSONObject? = opt(index) as? JSONObject
    fun optJSONArray(index: Int): JSONArray? = opt(index) as? JSONArray

    fun getString(index: Int): String = get(index) as? String ?: throw JSONException("JSONArray[$index] is not a String.")
    fun getInt(index: Int): Int = toLong(get(index))?.toInt() ?: throw JSONException("JSONArray[$index] is not a number.")
    fun getLong(index: Int): Long = toLong(get(index)) ?: throw JSONException("JSONArray[$index] is not a number.")
    fun getDouble(index: Int): Double = toDouble(get(index)) ?: throw JSONException("JSONArray[$index] is not a number.")
    fun getBoolean(index: Int): Boolean = toBoolean(get(index)) ?: throw JSONException("JSONArray[$index] is not a boolean.")
    fun getJSONObject(index: Int): JSONObject = get(index) as? JSONObject ?: throw JSONException("JSONArray[$index] is not a JSONObject.")
    fun getJSONArray(index: Int): JSONArray = get(index) as? JSONArray ?: throw JSONException("JSONArray[$index] is not a JSONArray.")

    fun toList(): List<Any?> = list.map(::unwrap)

    override fun iterator(): Iterator<Any?> = list.toList().iterator()

    override fun toString(): String = StringBuilder().also { write(it, 0, 0) }.toString()
    fun toString(indentFactor: Int): String = StringBuilder().also { write(it, indentFactor, 0) }.toString()

    internal fun write(out: StringBuilder, indent: Int, depth: Int) {
        out.append('[')
        list.forEachIndexed { i, value ->
            if (i > 0) out.append(',')
            newline(out, indent, depth + 1)
            writeValue(out, value, indent, depth + 1)
        }
        if (list.isNotEmpty()) newline(out, indent, depth)
        out.append(']')
    }
}

private fun unwrap(value: Any?): Any? = when (value) {
    JsonNull -> null
    is JSONObject -> value.toMap()
    is JSONArray -> value.toList()
    else -> value
}

private fun newline(out: StringBuilder, indent: Int, depth: Int) {
    if (indent <= 0) return
    out.append('\n')
    repeat(indent * depth) { out.append(' ') }
}

private fun writeValue(out: StringBuilder, value: Any?, indent: Int, depth: Int) {
    when (value) {
        null, JsonNull -> out.append("null")
        is JSONObject -> value.write(out, indent, depth)
        is JSONArray -> value.write(out, indent, depth)
        is String -> out.append(JSONObject.quote(value))
        is Number -> out.append(JSONObject.numberToString(value))
        is Boolean -> out.append(value.toString())
        else -> out.append(JSONObject.quote(value.toString()))
    }
}

/** The deepest nesting JSONTokener reads; TMPlayer's own files and the services' answers stay under 20. */
private const val MAX_DEPTH = 256

/** The JSON reader: strict JSON, plus the comments and unquoted keys org.json also accepts. */
class JSONTokener(private val source: String) {
    private var i = 0

    /** How deep the objects and arrays being read are nested. */
    private var depth = 0

    /**
     * Reads one nested object or array, refusing nesting deeper than [MAX_DEPTH]: the reader recurses,
     * and on Kotlin/Native a stack that runs out ends the app rather than throw StackOverflowError as
     * the JVM's does, so a file of nothing but brackets (outside data) is refused here instead.
     */
    private inline fun <T> nested(read: () -> T): T {
        if (++depth > MAX_DEPTH) {
            depth--
            fail("Nesting too deep")
        }
        try {
            return read()
        } finally {
            depth--
        }
    }

    private fun fail(message: String): Nothing = throw JSONException("$message at $i [character ${i + 1} line 1]")

    private fun skip() {
        while (i < source.length) {
            val c = source[i]
            when {
                c.isWhitespace() -> i++
                c == '/' && source.startsWith("//", i) -> i = source.indexOf('\n', i).let { if (it < 0) source.length else it + 1 }
                c == '/' && source.startsWith("/*", i) -> i = source.indexOf("*/", i + 2).let { if (it < 0) fail("Unclosed comment") else it + 2 }
                c == '#' -> i = source.indexOf('\n', i).let { if (it < 0) source.length else it + 1 }
                else -> return
            }
        }
    }

    fun nextValue(): Any {
        skip()
        if (i >= source.length) fail("End of input")
        return when (val c = source[i]) {
            '{' -> nested { readObject() }
            '[' -> nested { readArray() }
            '"', '\'' -> readString(c)
            else -> readBare()
        }
    }

    private fun readObject(): JSONObject {
        val result = JSONObject()
        i++
        skip()
        if (i < source.length && source[i] == '}') {
            i++
            return result
        }
        while (true) {
            skip()
            val key = when (val c = source.getOrNull(i)) {
                '"', '\'' -> readString(c)
                null -> fail("A JSONObject text must end with '}'")
                else -> readBare().toString()
            }
            skip()
            if (source.getOrNull(i) != ':') fail("Expected a ':' after a key")
            i++
            result.put(key, nextValue())
            skip()
            when (source.getOrNull(i)) {
                ',', ';' -> {
                    i++
                    skip()
                    if (source.getOrNull(i) == '}') {
                        i++
                        return result
                    }
                }
                '}' -> {
                    i++
                    return result
                }
                else -> fail("Expected a ',' or '}'")
            }
        }
    }

    private fun readArray(): JSONArray {
        val result = JSONArray()
        i++
        skip()
        if (source.getOrNull(i) == ']') {
            i++
            return result
        }
        while (true) {
            result.put(nextValue())
            skip()
            when (source.getOrNull(i)) {
                ',' -> {
                    i++
                    skip()
                    if (source.getOrNull(i) == ']') {
                        i++
                        return result
                    }
                }
                ']' -> {
                    i++
                    return result
                }
                else -> fail("Expected a ',' or ']'")
            }
        }
    }

    private fun readString(quote: Char): String {
        val out = StringBuilder()
        i++
        while (true) {
            if (i >= source.length) fail("Unterminated string")
            val c = source[i++]
            when {
                c == quote -> return out.toString()
                c == '\\' -> {
                    if (i >= source.length) fail("Unterminated string")
                    when (val e = source[i++]) {
                        'b' -> out.append('\b')
                        't' -> out.append('\t')
                        'n' -> out.append('\n')
                        'f' -> out.append('\u000C')
                        'r' -> out.append('\r')
                        'u' -> {
                            val hex = source.substring(i, minOf(i + 4, source.length))
                            out.append(hex.takeIf { it.length == 4 }?.toIntOrNull(16)?.toChar() ?: fail("Illegal escape."))
                            i += 4
                        }
                        '"', '\'', '\\', '/' -> out.append(e)
                        else -> fail("Illegal escape.")
                    }
                }
                c == '\n' || c == '\r' -> fail("Unterminated string")
                else -> out.append(c)
            }
        }
    }

    private fun readBare(): Any {
        val start = i
        while (i < source.length && source[i] !in ",:]}/\\\"[{;=#" && !source[i].isWhitespace()) i++
        val text = source.substring(start, i).trim()
        if (text.isEmpty()) fail("Missing value")
        return when {
            text.equals("true", ignoreCase = true) -> true
            text.equals("false", ignoreCase = true) -> false
            text.equals("null", ignoreCase = true) -> JSONObject.NULL
            else -> number(text) ?: text
        }
    }

    private fun number(text: String): Any? {
        val c = text[0]
        if (!(c.isDigit() || c == '-')) return null
        if (text.any { it == '.' || it == 'e' || it == 'E' }) return text.toDoubleOrNull()
        val asLong = text.toLongOrNull() ?: return text.toDoubleOrNull()
        return if (asLong in Int.MIN_VALUE..Int.MAX_VALUE) asLong.toInt() else asLong
    }
}

