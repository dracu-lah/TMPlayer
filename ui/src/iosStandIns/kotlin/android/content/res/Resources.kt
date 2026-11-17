package android.content.res

class Configuration {
    var screenWidthDp: Int = 0
    var screenHeightDp: Int = 0
    var smallestScreenWidthDp: Int = 0
    var orientation: Int = ORIENTATION_PORTRAIT
    var uiMode: Int = UI_MODE_TYPE_NORMAL
    var fontScale: Float = 1f
    var densityDpi: Int = 160

    companion object {
        const val ORIENTATION_PORTRAIT = 1
        const val ORIENTATION_LANDSCAPE = 2
        const val UI_MODE_TYPE_MASK = 0x0f
        const val UI_MODE_TYPE_NORMAL = 0x01
        const val UI_MODE_TYPE_TELEVISION = 0x04
        const val UI_MODE_NIGHT_MASK = 0x30
        const val UI_MODE_NIGHT_YES = 0x20
        const val UI_MODE_NIGHT_NO = 0x10
    }
}

class Resources private constructor() {
    val configuration: Configuration get() = Configuration()
    fun getString(id: Int): String = ""

    private fun reference(id: Int): String =
        "@" + (ResourceFiles.reference(id) ?: throw NotFoundException("Resource ID #0x${id.toString(16)}"))

    /** A dimension of :app's in pixels, Android's. */
    fun getDimension(id: Int): Float = ResourceFiles.dimension(reference(id)) ?: throw NotFoundException(reference(id))

    /** Rounded to the nearest pixel, at least one for a size that is not zero, Android's. */
    fun getDimensionPixelSize(id: Int): Int {
        val value = getDimension(id)
        val rounded = (value + 0.5f).toInt()
        return if (rounded == 0 && value > 0f) 1 else rounded
    }

    /** Truncated to whole pixels, Android's. */
    fun getDimensionPixelOffset(id: Int): Int = getDimension(id).toInt()

    fun getColor(id: Int, theme: Any? = null): Int =
        ResourceFiles.color(reference(id)) ?: ResourceFiles.colorStateList(reference(id))?.defaultColor ?: throw NotFoundException(reference(id))

    fun getColorStateList(id: Int, theme: Any? = null): ColorStateList =
        ResourceFiles.colorStateList(reference(id)) ?: throw NotFoundException(reference(id))

    /** Android's exception for an id the app does not have. */
    class NotFoundException(message: String) : RuntimeException(message)

    /** The screen's density and size, one dp being one iOS point. */
    val displayMetrics: android.util.DisplayMetrics by lazy { android.util.DisplayMetrics() }

    companion object {
        internal val Shared = Resources()
    }
}
