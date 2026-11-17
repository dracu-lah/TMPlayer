package android.util

import android.content.res.XmlNode

/** A layout element's attributes, Android's, as the inflater hands them to a View's constructor. */
interface AttributeSet {
    fun getAttributeCount(): Int

    fun getAttributeName(index: Int): String

    fun getAttributeValue(index: Int): String

    fun getAttributeValue(namespace: String?, name: String): String?

    val attributeCount: Int get() = getAttributeCount()
}

/**
 * The attributes of one element of :app's layout XML, with those of its `style` under them, as
 * Android's View constructors see them: an attribute written on the element wins over the style's.
 */
class XmlAttributes(val node: XmlNode) : AttributeSet {
    private val all: Map<String, String> = styleItems(node.attributes["style"]) + node.attributes
    private val entries = all.entries.toList()

    override fun getAttributeCount(): Int = entries.size

    override fun getAttributeName(index: Int): String = entries[index].key.substringAfter(':')

    override fun getAttributeValue(index: Int): String = entries[index].value

    override fun getAttributeValue(namespace: String?, name: String): String? {
        val prefix = when (namespace) {
            ANDROID_NAMESPACE -> "android"
            APP_NAMESPACE, null -> "app"
            else -> null
        }
        return if (prefix != null) all["$prefix:$name"] else all[name] ?: all.entries.firstOrNull { it.key.substringAfter(':') == name }?.value
    }

    companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val APP_NAMESPACE = "http://schemas.android.com/apk/res-auto"

        // A style's parents are few; the bound only stops a style that names itself.
        private const val MAX_STYLE_DEPTH = 8

        /**
         * The items of the app's style [reference] names (`@style/Name`), its parents' first, as
         * Android merges them: the parent is the `parent` attribute when there is one, or else the
         * name before the last dot. A platform parent (`android:...`) adds nothing here, as the
         * stand-in Views already start from the platform's defaults.
         */
        private fun styleItems(reference: String?): Map<String, String> {
            val name = reference?.trim()?.takeIf { it.startsWith("@style/") }?.substringAfter('/') ?: return emptyMap()
            val out = LinkedHashMap<String, String>()
            fun add(style: String, depth: Int) {
                if (depth > MAX_STYLE_DEPTH) return
                val node = android.content.res.ResourceFiles.value("style", style) ?: return
                val parent = when (val declared = node.attributes["parent"]?.trim()) {
                    null -> style.substringBeforeLast('.', "")
                    else -> declared.removePrefix("@style/").takeUnless { it.startsWith("android:") || it.startsWith("@android:") }.orEmpty()
                }
                if (parent.isNotEmpty()) add(parent, depth + 1)
                for (item in node.children) {
                    if (item.name != "item") continue
                    val key = item.attributes["name"] ?: continue
                    out[key] = item.text.trim()
                }
            }
            add(name, 0)
            return out
        }
    }
}

/** An android: attribute by name. */
fun AttributeSet.android(name: String): String? = getAttributeValue(XmlAttributes.ANDROID_NAMESPACE, name)

/** An app: attribute (a View's own, declare-styleable) by name. */
fun AttributeSet.app(name: String): String? = getAttributeValue(XmlAttributes.APP_NAMESPACE, name)
