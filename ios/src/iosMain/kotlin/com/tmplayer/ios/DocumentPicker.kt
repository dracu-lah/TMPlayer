package com.tmplayer.ios

import android.net.Uri
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTType
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject

/**
 * Android's document picker (ActivityResultContracts.OpenDocument, GetContent) as iOS's: Files,
 * with every place the viewer has there (the device, iCloud Drive, other apps' providers). The
 * file chosen is copied into the app's own folder by iOS, so the answer is a file:// address the
 * app can read like any other file, as upstream reads the content:// one it gets on Android
 * (SubtitleFiles, loading a subtitle file). Backing out answers null, as Android's does.
 */
internal object DocumentPicker {
    // The picker's delegate, held while the picker is up: UIKit keeps only a weak reference.
    private var showing: Delegate? = null

    /** Opens Files for one document of [mimeTypes] (the wildcard type for any); [answer] gets it, or null. */
    fun open(mimeTypes: Array<String>, answer: (Uri?) -> Unit) {
        val top = topController()
        if (top == null) {
            answer(null)
            return
        }
        val types = if (mimeTypes.isEmpty() || mimeTypes.any { it == "*/*" }) {
            listOf(UTTypeItem)
        } else {
            mimeTypes.mapNotNull { UTType.typeWithMIMEType(it) }.ifEmpty { listOf(UTTypeItem) }
        }
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = types, asCopy = true)
        picker.allowsMultipleSelection = false
        val delegate = Delegate { url ->
            showing = null
            answer(url?.path?.let { Uri.fromFile(java.io.File(it)) })
        }
        showing = delegate
        picker.delegate = delegate
        top.presentViewController(picker, animated = true, completion = null)
    }

    private fun topController(): UIViewController? {
        var top = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return null
        while (true) top = top.presentedViewController ?: break
        return top
    }

    private class Delegate(private val answer: (NSURL?) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
            answer(didPickDocumentsAtURLs.firstOrNull() as? NSURL)
        }

        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = answer(null)
    }
}
