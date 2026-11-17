package android.view

import android.content.Context
import android.content.res.ResourceFiles
import android.content.res.XmlNode
import android.util.AttributeSet
import android.util.XmlAttributes
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Space
import android.widget.TextView

/**
 * Android's LayoutInflater on iOS: :app's layout XML into Views, each made with its attributes and
 * placed in its parent with the LayoutParams the parent makes of them. Kotlin on iOS has no
 * reflection, so a View class of the app's own is made through [register], by its full name.
 */
open class LayoutInflater(val context: Context) {
    /** A layout by its resource id, through the names the shell installs (R.layout). */
    fun inflate(resource: Int, root: ViewGroup?): View = inflate(resource, root, root != null)

    fun inflate(resource: Int, root: ViewGroup?, attachToRoot: Boolean): View {
        val name = layoutName(resource) ?: throw IllegalArgumentException("No layout for resource $resource")
        return inflate(name, root, attachToRoot)
    }

    /** A layout by its name ("player_controls"). */
    fun inflate(layout: String, root: ViewGroup?, attachToRoot: Boolean): View {
        val node = ResourceFiles.xml("layout/$layout") ?: throw IllegalArgumentException("No layout named $layout in the bundle")
        if (node.name == "merge") {
            val parent = root ?: throw IllegalArgumentException("<merge /> needs a root to attach to")
            inflateChildren(node, parent)
            return parent
        }
        val view = create(node, root)
        if (view is ViewGroup) inflateChildren(node, view)
        if (root != null) {
            val params = root.generateLayoutParams(XmlAttributes(node))
            if (attachToRoot) root.addView(view, params) else view.layoutParams = params
        } else if (view.layoutParams == null) {
            view.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.size(node.attribute("layout_width")),
                ViewGroup.LayoutParams.size(node.attribute("layout_height")),
            )
        }
        return if (root != null && attachToRoot) root else view
    }

    private fun inflateChildren(parent: XmlNode, into: ViewGroup) {
        for (child in parent.children) {
            when (child.name) {
                "include" -> {
                    val layout = child.attribute("layout")?.substringAfter('/') ?: continue
                    val included = inflate(layout, into, false)
                    child.attribute("id")?.let { included.id = ResourceFiles.idOf(it.substringAfter('/')) }
                    child.attribute("visibility")?.let { included.visibility = if (it == "gone") View.GONE else if (it == "invisible") View.INVISIBLE else View.VISIBLE }
                    if (child.attribute("layout_width") != null) included.layoutParams = into.generateLayoutParams(XmlAttributes(child))
                    into.addView(included)
                }
                "requestFocus", "tag" -> Unit
                else -> {
                    val view = create(child, into)
                    if (view is ViewGroup) inflateChildren(child, view)
                    into.addView(view, into.generateLayoutParams(XmlAttributes(child)))
                }
            }
        }
    }

    private fun create(node: XmlNode, parent: ViewGroup?): View {
        val attrs: AttributeSet = XmlAttributes(node)
        val name = if (node.name == "view") node.attribute("class") ?: "View" else node.name
        factories[name]?.let { return it(context, attrs) }
        return when (name) {
            "View" -> View(context, attrs)
            "FrameLayout" -> FrameLayout(context, attrs)
            "LinearLayout" -> LinearLayout(context, attrs)
            "TextView" -> TextView(context, attrs)
            "Button" -> Button(context, attrs)
            "ImageView" -> ImageView(context, attrs)
            "ImageButton" -> ImageButton(context, attrs)
            "ProgressBar" -> ProgressBar(context, attrs)
            "Space" -> Space(context, attrs)
            "HorizontalScrollView" -> HorizontalScrollView(context, attrs)
            "ScrollView" -> android.widget.ScrollView(context, attrs)
            else -> {
                android.util.Log.w("LayoutInflater", "No View class registered for $name; a plain FrameLayout stands in")
                FrameLayout(context, attrs)
            }
        }
    }

    fun cloneInContext(newContext: Context): LayoutInflater = LayoutInflater(newContext)

    companion object {
        private val factories = HashMap<String, (Context, AttributeSet) -> View>()

        /** Teaches the inflater a View class of the app's own, by the name a layout gives it. */
        fun register(className: String, make: (Context, AttributeSet) -> View) {
            factories[className] = make
        }

        /** A layout's name for :app's R.layout id, from the table the shell installs. */
        var layoutName: (Int) -> String? = { id -> ResourceFiles.reference(id)?.takeIf { it.startsWith("layout/") }?.substringAfter('/') }

        fun from(context: Context): LayoutInflater = LayoutInflater(context)
    }
}
