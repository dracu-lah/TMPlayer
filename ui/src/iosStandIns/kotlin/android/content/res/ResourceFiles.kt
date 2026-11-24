package android.content.res

import platform.Foundation.NSBundle
import platform.UIKit.UIScreen

// Android's resources on iOS: :app's res/ folders (layout, values, color, drawable) copied into the
// app bundle as they are (iosApp/project.yml), read the way Android reads them. Only what Android's
// Views need: layouts, colours and colour lists, dimensions, strings, drawables.

/** One element of an Android XML file. */
class XmlNode(val name: String, val attributes: Map<String, String>, val children: List<XmlNode>, val text: String) {
    /** An attribute by its name without the namespace prefix ("layout_width" for "android:layout_width"). */
    fun attribute(local: String): String? = attributes[local] ?: attributes.entries.firstOrNull { it.key.substringAfter(':') == local }?.value
}

/** A small reader of Android's resource XML: elements, attributes, text; comments and the prolog skipped. */
object AndroidXml {
    fun parse(text: String): XmlNode? = Reader(text).root()

    private class Reader(private val s: String) {
        private var i = 0

        fun root(): XmlNode? {
            skipMisc()
            return if (i < s.length && s[i] == '<') element() else null
        }

        private fun skipMisc() {
            while (i < s.length) {
                when {
                    s.startsWith("<?", i) -> i = s.indexOf("?>", i).let { if (it < 0) s.length else it + 2 }
                    s.startsWith("<!--", i) -> i = s.indexOf("-->", i).let { if (it < 0) s.length else it + 3 }
                    s.startsWith("<!", i) -> i = s.indexOf('>', i).let { if (it < 0) s.length else it + 1 }
                    s[i].isWhitespace() -> i++
                    else -> return
                }
            }
        }

        private fun element(): XmlNode {
            i++ // <
            val nameStart = i
            while (i < s.length && !s[i].isWhitespace() && s[i] != '>' && s[i] != '/') i++
            val name = s.substring(nameStart, i)
            val attributes = LinkedHashMap<String, String>()
            while (i < s.length) {
                while (i < s.length && s[i].isWhitespace()) i++
                if (s[i] == '/') {
                    i = s.indexOf('>', i) + 1
                    return XmlNode(name, attributes, emptyList(), "")
                }
                if (s[i] == '>') {
                    i++
                    break
                }
                val keyStart = i
                while (i < s.length && s[i] != '=' && !s[i].isWhitespace()) i++
                val key = s.substring(keyStart, i)
                while (i < s.length && s[i] != '"' && s[i] != '\'') i++
                val quote = s[i]
                val valueStart = ++i
                i = s.indexOf(quote, i)
                attributes[key] = decode(s.substring(valueStart, i))
                i++
            }
            val children = ArrayList<XmlNode>()
            val text = StringBuilder()
            while (i < s.length) {
                when {
                    s.startsWith("<!--", i) -> i = s.indexOf("-->", i) + 3
                    s.startsWith("<![CDATA[", i) -> {
                        val end = s.indexOf("]]>", i)
                        text.append(s, i + 9, end)
                        i = end + 3
                    }
                    s.startsWith("</", i) -> {
                        i = s.indexOf('>', i) + 1
                        return XmlNode(name, attributes, children, decode(text.toString()))
                    }
                    s[i] == '<' -> children.add(element())
                    else -> {
                        val next = s.indexOf('<', i).let { if (it < 0) s.length else it }
                        text.append(s, i, next)
                        i = next
                    }
                }
            }
            return XmlNode(name, attributes, children, decode(text.toString()))
        }

        private fun decode(raw: String): String =
            raw.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    }
}

/**
 * :app's resources in the bundle, by name. Android addresses them by the ids R generates; on iOS the
 * Views and the inflater ask by name, and ids are stable numbers kept per name ([idOf]).
 */
object ResourceFiles {
    /** Pixels per dp: one dp is one iOS point, as Compose draws on iOS, and a point is [density] pixels. */
    val density: Float by lazy { UIScreen.mainScreen.scale.toFloat() }

    /** Pixels per sp: the density times the user's text size (Dynamic Type), as Android's. */
    val scaledDensity: Float get() = density * HostConfiguration.fontScale

    private val folder: String? by lazy { NSBundle.mainBundle.resourcePath }
    private val xmlCache = HashMap<String, XmlNode?>()
    private val values: Map<String, Map<String, XmlNode>> by lazy { readValues() }
    private val ids = HashMap<String, Int>()
    private val names = HashMap<Int, String>()
    private val references = HashMap<Int, String>()
    private var unlisted = 0

    /** A layout's or drawable's or colour list's XML: "layout/player_controls". */
    fun xml(path: String): XmlNode? = xmlCache.getOrPut(path) {
        val base = folder ?: return@getOrPut null
        runCatching { java.io.File("$base/$path.xml").readText() }.getOrNull()?.let(AndroidXml::parse)
    }

    /**
     * :app's R by "type/name" ("id/controls_times", "color/accent", "layout/player_controls"),
     * generated from its res folder and installed as the app starts: from then on the View a layout
     * makes for `@+id/x` carries the number `R.id.x` has in the code.
     */
    fun install(table: Map<Int, String>) {
        for ((id, reference) in table) {
            references[id] = reference
            if (reference.startsWith("id/")) {
                val name = reference.substring(3)
                ids[name] = id
                names[id] = name
            }
        }
    }

    /** The "type/name" an R id of :app's stands for, once [install]ed. */
    fun reference(id: Int): String? = references[id]

    /**
     * The id kept for an `@+id/name`, the same number every time it is asked for: R's number once
     * installed, otherwise one from a range of its own, which R's never reach.
     */
    fun idOf(name: String): Int = ids.getOrPut(name) {
        val id = 0x7F0F0000 + unlisted++
        names[id] = name
        id
    }

    /** An id's name, for logs and the inspector. */
    fun nameOf(id: Int): String? = names[id]

    /** A value resource: "color" "accent", "dimen" "player_scrim_top", "string" "app_name". */
    fun value(type: String, name: String): XmlNode? = values[type]?.get(name)

    /** A colour reference or literal: "#B3101014", "@color/accent", "@android:color/white". */
    fun color(reference: String?): Int? {
        val ref = reference?.trim() ?: return null
        if (ref.startsWith("#")) return runCatching { android.graphics.Color.parseColor(ref) }.getOrNull()
        if (ref.startsWith("@android:color/")) return ANDROID_COLORS[ref.substringAfter('/')]
        if (ref.startsWith("@color/")) {
            val name = ref.substringAfter('/')
            value("color", name)?.let { return color(it.text) }
            return colorStateList(ref)?.defaultColor
        }
        return null
    }

    /** A colour list ("@color/player_control_icon", a file in res/color) or a single colour as one. */
    fun colorStateList(reference: String?): ColorStateList? {
        val ref = reference?.trim() ?: return null
        if (ref.startsWith("@color/")) {
            val node = xml("color/${ref.substringAfter('/')}")
            if (node != null && node.name == "selector") {
                val states = ArrayList<IntArray>()
                val colors = ArrayList<Int>()
                for (item in node.children) {
                    val base = color(item.attribute("color")) ?: continue
                    val alpha = item.attribute("alpha")?.toFloatOrNull()
                    colors += if (alpha != null) ((android.graphics.Color.alpha(base) * alpha).toInt() shl 24) or (base and 0xFFFFFF) else base
                    states += stateSpec(item)
                }
                return ColorStateList(states.toTypedArray(), colors.toIntArray())
            }
        }
        return color(ref)?.let { ColorStateList.valueOf(it) }
    }

    /** A dimension in pixels: "48dp", "15sp", "2px", "@dimen/player_scrim_top". */
    fun dimension(reference: String?): Float? {
        val ref = reference?.trim() ?: return null
        if (ref.startsWith("@dimen/")) return dimension(value("dimen", ref.substringAfter('/'))?.text)
        return when {
            ref.endsWith("dip") -> ref.removeSuffix("dip").toFloatOrNull()?.times(density)
            ref.endsWith("dp") -> ref.removeSuffix("dp").toFloatOrNull()?.times(density)
            ref.endsWith("sp") -> ref.removeSuffix("sp").toFloatOrNull()?.times(scaledDensity)
            ref.endsWith("px") -> ref.removeSuffix("px").toFloatOrNull()
            ref.endsWith("pt") -> ref.removeSuffix("pt").toFloatOrNull()?.times(density * 160f / 72f)
            else -> ref.toFloatOrNull()
        }
    }

    /** A string reference or literal: "@string/name" in the default language, else the text as it is. */
    fun string(reference: String?): String? {
        val ref = reference ?: return null
        if (!ref.startsWith("@string/")) return ref
        return value("string", ref.substringAfter('/'))?.text?.replace("\\'", "'")?.replace("\\\"", "\"")?.replace("\\n", "\n")
    }

    /** Android's state attributes on a selector item, as state ids (negative for "false"). */
    fun stateSpec(item: XmlNode): IntArray = item.attributes.mapNotNull { (key, value) ->
        val state = STATES[key.substringAfter(':')] ?: return@mapNotNull null
        if (value == "true") state else -state
    }.toIntArray()

    private fun readValues(): Map<String, Map<String, XmlNode>> {
        val base = folder ?: return emptyMap()
        val files = runCatching { java.io.File("$base/values").listFiles()?.toList() }.getOrNull().orEmpty()
        val out = HashMap<String, HashMap<String, XmlNode>>()
        for (file in files.filter { it.name.endsWith(".xml") }) {
            val root = runCatching { AndroidXml.parse(file.readText()) }.getOrNull() ?: continue
            for (item in root.children) {
                val name = item.attributes["name"] ?: continue
                val type = if (item.name == "item") item.attributes["type"] ?: continue else item.name
                out.getOrPut(type) { HashMap() }[name] = item
            }
        }
        return out
    }

    val STATES = mapOf(
        "state_pressed" to android.R.attr.state_pressed,
        "state_focused" to android.R.attr.state_focused,
        "state_selected" to android.R.attr.state_selected,
        "state_enabled" to android.R.attr.state_enabled,
        "state_checked" to android.R.attr.state_checked,
        "state_activated" to android.R.attr.state_activated,
        "state_hovered" to android.R.attr.state_hovered,
    )

    private val ANDROID_COLORS = mapOf(
        "white" to android.graphics.Color.WHITE,
        "black" to android.graphics.Color.BLACK,
        "transparent" to android.graphics.Color.TRANSPARENT,
    )
}

/** Colours by a View's state, Android's. */
class ColorStateList(private val states: Array<IntArray>, private val colors: IntArray) {
    val defaultColor: Int get() = colorFor(IntArray(0))

    fun isStateful(): Boolean = states.size > 1 || states.any { it.isNotEmpty() }

    /** Java's isStateful(), as Kotlin also reads it. */
    val isStateful: Boolean get() = isStateful()

    fun getColorForState(stateSet: IntArray?, defaultColor: Int): Int =
        states.indices.firstOrNull { matches(states[it], stateSet ?: IntArray(0)) }?.let { colors[it] } ?: defaultColor

    private fun colorFor(stateSet: IntArray): Int = getColorForState(stateSet, colors.lastOrNull() ?: 0)

    companion object {
        fun valueOf(color: Int): ColorStateList = ColorStateList(arrayOf(IntArray(0)), intArrayOf(color))

        /** Whether a View's [stateSet] satisfies a selector item's [spec], as Android's StateSet.stateSetMatches. */
        fun matches(spec: IntArray, stateSet: IntArray): Boolean = spec.all { state ->
            if (state > 0) state in stateSet else -state !in stateSet
        }
    }
}
