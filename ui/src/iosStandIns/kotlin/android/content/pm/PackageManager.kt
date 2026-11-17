package android.content.pm

import android.content.Intent

/** Other apps are not visible to an iOS app: nothing resolves, and the app itself is the only package. */
class PackageManager private constructor() {
    fun resolveActivity(intent: Intent, flags: Int): ResolveInfo? = null
    fun queryIntentActivities(intent: Intent, flags: Int): List<ResolveInfo> = emptyList()
    fun hasSystemFeature(name: String): Boolean = name == FEATURE_TOUCHSCREEN
    fun canRequestPackageInstalls(): Boolean = false
    fun getPackageInfo(packageName: String, flags: Int): PackageInfo = PackageInfo(packageName)
    fun getLaunchIntentForPackage(packageName: String): Intent? = null
    val packageInstaller: PackageInstaller get() = PackageInstaller()

    class NameNotFoundException(message: String? = null) : Exception(message)

    companion object {
        internal val Shared = PackageManager()
        const val PERMISSION_GRANTED = 0
        const val PERMISSION_DENIED = -1
        const val MATCH_DEFAULT_ONLY = 0x00010000
        const val FEATURE_LEANBACK = "android.software.leanback"
        const val FEATURE_TELEVISION = "android.hardware.type.television"
        const val FEATURE_TOUCHSCREEN = "android.hardware.touchscreen"
        const val FEATURE_PICTURE_IN_PICTURE = "android.software.picture_in_picture"
    }
}

class ResolveInfo

class PackageInfo(val packageName: String) {
    val versionName: String? get() = platform.Foundation.NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String
    val longVersionCode: Long get() = (platform.Foundation.NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleVersion") as? String)?.toLongOrNull() ?: 0
}

/** No app installs another on iOS. */
class PackageInstaller {
    companion object {
        const val STATUS_SUCCESS = 0
        const val STATUS_PENDING_USER_ACTION = -1
        const val STATUS_FAILURE = 1
        const val STATUS_FAILURE_BLOCKED = 2
        const val STATUS_FAILURE_ABORTED = 3
        const val STATUS_FAILURE_INVALID = 4
        const val STATUS_FAILURE_CONFLICT = 5
        const val STATUS_FAILURE_STORAGE = 6
        const val STATUS_FAILURE_INCOMPATIBLE = 7
        const val EXTRA_STATUS = "android.content.pm.extra.STATUS"
        const val EXTRA_STATUS_MESSAGE = "android.content.pm.extra.STATUS_MESSAGE"
    }
}

class ServiceInfo {
    companion object {
        const val FOREGROUND_SERVICE_TYPE_DATA_SYNC = 1
    }
}

/** An activity's manifest attributes; only the orientation constants are read. */
class ActivityInfo {
    companion object {
        const val SCREEN_ORIENTATION_UNSPECIFIED = -1
        const val SCREEN_ORIENTATION_LANDSCAPE = 0
        const val SCREEN_ORIENTATION_PORTRAIT = 1
        const val SCREEN_ORIENTATION_BEHIND = 3
        const val SCREEN_ORIENTATION_NOSENSOR = 5
        const val SCREEN_ORIENTATION_REVERSE_LANDSCAPE = 8
        const val SCREEN_ORIENTATION_REVERSE_PORTRAIT = 9
        const val SCREEN_ORIENTATION_USER = 2
        const val SCREEN_ORIENTATION_SENSOR = 4
        const val SCREEN_ORIENTATION_SENSOR_LANDSCAPE = 6
        const val SCREEN_ORIENTATION_SENSOR_PORTRAIT = 7
        const val SCREEN_ORIENTATION_FULL_SENSOR = 10
        const val SCREEN_ORIENTATION_USER_LANDSCAPE = 11
        const val SCREEN_ORIENTATION_USER_PORTRAIT = 12
        const val SCREEN_ORIENTATION_FULL_USER = 13
        const val SCREEN_ORIENTATION_LOCKED = 14
    }
}

/** The app as the system lists it, Android's; its label is the app's name from the bundle. */
class ApplicationInfo internal constructor() {
    fun loadLabel(pm: PackageManager): CharSequence =
        (platform.Foundation.NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleDisplayName") as? String)
            ?: (platform.Foundation.NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleName") as? String)
            ?: "TMPlayer"

    companion object {
        internal val Shared = ApplicationInfo()
    }
}
