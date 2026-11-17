// The rest of the Android framework that :app's screens and data classes name, on iOS. Each file
// would be a few lines; they share one so the stand in stays easy to read as a whole.
@file:Suppress("unused", "UNUSED_PARAMETER", "PropertyName", "FunctionName")

package android

object Manifest {
    object permission {
        const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
        const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
        const val READ_EXTERNAL_STORAGE = "android.permission.READ_EXTERNAL_STORAGE"
        const val WRITE_EXTERNAL_STORAGE = "android.permission.WRITE_EXTERNAL_STORAGE"
        const val READ_MEDIA_VIDEO = "android.permission.READ_MEDIA_VIDEO"
        const val REQUEST_INSTALL_PACKAGES = "android.permission.REQUEST_INSTALL_PACKAGES"
    }
}
