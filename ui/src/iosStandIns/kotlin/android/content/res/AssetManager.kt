package android.content.res

import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager

/** :app's assets folder (app/src/main/assets), bundled into the iOS app as assets/. */
class AssetManager private constructor() {
    private val base: String? by lazy { NSBundle.mainBundle.resourcePath?.let { "$it/assets" } }

    /** The file's path in the bundle, or null when the asset is not there. */
    internal fun pathOf(name: String): String? {
        val root = base ?: return null
        if (name.contains("..")) return null
        val path = "$root/${name.trimStart('/')}"
        return path.takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }
    }

    /** The asset's bytes as a stream, Android's; a missing one is a FileNotFoundException. */
    fun open(fileName: String): java.io.InputStream =
        java.io.FileInputStream(java.io.File(pathOf(fileName) ?: throw java.io.FileNotFoundException(fileName)))

    /** The names in an assets folder, Android's. */
    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    fun list(path: String): Array<String> {
        val folder = pathOf(path) ?: return emptyArray()
        @Suppress("UNCHECKED_CAST")
        val names = NSFileManager.defaultManager.contentsOfDirectoryAtPath(folder, null) as? List<String> ?: return emptyArray()
        return names.sorted().toTypedArray()
    }

    companion object {
        internal val Shared = AssetManager()
    }
}
