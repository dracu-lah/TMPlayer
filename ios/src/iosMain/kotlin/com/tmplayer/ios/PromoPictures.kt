package com.tmplayer.ios

import platform.Foundation.NSBundle

/**
 * The promo fixture's pictures by name (PromoCatalog.Pictures), in a promo build: the Unsplash photos
 * of app/src/promo/res/drawable-nodpi, which the Xcode project copies into the bundle's
 * drawable-nodpi/ beside :app's own, where painterResource reads them too.
 */
object PromoPictures {
    fun bytes(name: String): ByteArray? {
        val folder = NSBundle.mainBundle.resourcePath ?: return null
        val file = java.io.File("$folder/drawable-nodpi/$name.webp")
        return if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null
    }
}
