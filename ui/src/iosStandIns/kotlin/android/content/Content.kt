package android.content

// android.content on iOS, for Android's phone screens and their data classes compiled from :app
// (docs/IOS.md, "Android's phone screens on iOS"). A Context is the app's own folders,
// clipboard and link opener; what has no iOS meaning (receivers, other apps' activities) does
// nothing or reports itself unavailable, as an Android device without that feature would.

import android.content.pm.PackageManager
import android.net.Uri
import java.io.File
import java.lang.SecurityException
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.numberWithBool
import platform.Foundation.NSUserDomainMask
import kotlinx.cinterop.useContents
import platform.UIKit.UIApplication
import platform.UIKit.popoverPresentationController
import platform.UIKit.UIPasteboard
import kotlin.reflect.KClass

private fun folder(base: ULong, child: String? = null): File {
    val url = NSFileManager.defaultManager.URLsForDirectory(base, NSUserDomainMask).first() as NSURL
    val root = File(requireNotNull(url.path))
    return (if (child == null) root else File(root, child)).also {
        it.mkdirs()
        keepOutOfBackups(url)
    }
}

private val leftOutOfBackups = HashSet<String>()
private val backupLock = platform.Foundation.NSLock()

/**
 * Android's manifest says allowBackup="false": nothing of the app's (the Telegram session, the
 * settings, gigabytes of downloads) goes into a backup. iOS backs Application Support and Documents
 * up to iCloud unless a folder says otherwise, so each root says so once.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun keepOutOfBackups(url: NSURL) {
    val path = url.path ?: return
    backupLock.lock()
    val first = try { leftOutOfBackups.add(path) } finally { backupLock.unlock() }
    if (!first) return
    url.setResourceValue(NSNumber.numberWithBool(true), forKey = NSURLIsExcludedFromBackupKey, error = null)
}

/** The app's Documents folder, for android.os.Environment's shared storage. */
object ContextOnIos {
    fun documents(): File = folder(NSDocumentDirectory)
}

open class ActivityNotFoundException(message: String? = null) : RuntimeException(message)

abstract class Context {
    open val applicationContext: Context get() = this
    open val packageName: String get() = NSBundle.mainBundle.bundleIdentifier ?: "org.tmplayer.ios"

    /** Application Support: kept, backed up unless excluded, never purged. */
    open val filesDir: File get() = folder(NSApplicationSupportDirectory)
    open val noBackupFilesDir: File get() = folder(NSApplicationSupportDirectory, "nobackup")
    open val dataDir: File get() = filesDir

    /** Caches: iOS may empty it when space runs short, as Android may empty cacheDir. */
    open val cacheDir: File get() = folder(NSCachesDirectory)
    open val externalCacheDir: File? get() = cacheDir
    open val codeCacheDir: File get() = cacheDir

    /** Documents, the folder the Files app shows: the nearest thing to Android's shared storage. */
    open fun getExternalFilesDir(type: String?): File? = folder(NSDocumentDirectory, type)
    open fun getExternalFilesDirs(type: String?): Array<File?> = arrayOf(getExternalFilesDir(type))
    open fun getDir(name: String, mode: Int): File = File(filesDir, "app_$name").also { it.mkdirs() }
    open fun getDatabasePath(name: String): File = File(File(filesDir, "databases").also { it.mkdirs() }, name)

    open val contentResolver: ContentResolver get() = ContentResolver.Shared
    open val packageManager: PackageManager get() = PackageManager.Shared
    open val resources: android.content.res.Resources get() = android.content.res.Resources.Shared

    /** What the app is, Android's: its name for the system's lists. */
    open val applicationInfo: android.content.pm.ApplicationInfo get() = android.content.pm.ApplicationInfo.Shared

    /** A colour list of :app's by its R.color id, Android 6's. */
    fun getColorStateList(id: Int): android.content.res.ColorStateList = resources.getColorStateList(id)

    /** A colour of :app's by its R.color id, Android 6's. */
    fun getColor(id: Int): Int = resources.getColor(id)

    /** A drawable of :app's by its R.drawable id, Android 5's. */
    fun getDrawable(id: Int): android.graphics.drawable.Drawable? = androidx.core.content.ContextCompat.getDrawable(this, id)

    /** :app's assets folder, bundled into the app as assets/. */
    open val assets: android.content.res.AssetManager get() = android.content.res.AssetManager.Shared
    open val theme: Any? get() = null

    open fun getString(id: Int): String = ""
    open fun getString(id: Int, vararg args: Any?): String = ""

    open fun getSystemService(name: String): Any? = when (name) {
        CLIPBOARD_SERVICE -> ClipboardManager.Shared
        CONNECTIVITY_SERVICE -> android.net.ConnectivityManager.Shared
        POWER_SERVICE -> android.os.PowerManager.Shared
        ACTIVITY_SERVICE -> android.app.ActivityManager.Shared
        AUDIO_SERVICE -> android.media.AudioManager.Shared
        DISPLAY_SERVICE -> android.hardware.display.DisplayManager.Shared
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    open fun <T : Any> getSystemService(type: KClass<T>): T? = when (type) {
        android.hardware.display.DisplayManager::class -> android.hardware.display.DisplayManager.Shared as T
        android.media.AudioManager::class -> android.media.AudioManager.Shared as T
        android.net.ConnectivityManager::class -> android.net.ConnectivityManager.Shared as T
        android.os.PowerManager::class -> android.os.PowerManager.Shared as T
        else -> null
    }

    /** Told of the system's memory levels and configuration changes; only an Application hears them on iOS. */
    open fun registerComponentCallbacks(callback: ComponentCallbacks) = Unit

    open fun unregisterComponentCallbacks(callback: ComponentCallbacks) = Unit

    /**
     * Opens what iOS can open for an intent: a web or app link through UIApplication. Anything
     * else (another app's activity, a settings screen) is not there to start, as on a device
     * without it.
     */
    open fun startActivity(intent: Intent) {
        if (ActivityHost.start(intent)) return
        // A share, through Android's chooser or straight: iOS's share sheet, which also offers
        // Save Video, Save to Files and AirDrop.
        val share = if (intent.action == Intent.ACTION_CHOOSER) intent.rawExtra(Intent.EXTRA_INTENT) as? Intent ?: intent else intent
        if (share.action == Intent.ACTION_SEND || share.action == Intent.ACTION_SEND_MULTIPLE) {
            ShareSheet.show(share)
            return
        }
        // A file of the app's opened in another app (Android's "Open with" chooser over
        // ACTION_VIEW): the share sheet with that file, whose app row is iOS's "Open in".
        val viewed = share.data
        if (share.action == Intent.ACTION_VIEW && viewed?.scheme == "file") {
            ShareSheet.show(Intent(Intent.ACTION_SEND).setType(share.type ?: "*/*").putExtra(Intent.EXTRA_STREAM, viewed))
            return
        }
        val target = intent.data?.toString()
        if (intent.action == Intent.ACTION_VIEW && target != null) {
            val url = NSURL.URLWithString(target) ?: throw ActivityNotFoundException(target)
            UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
            return
        }
        throw ActivityNotFoundException("No activity for ${intent.action}")
    }

    open fun startActivity(intent: Intent, options: android.os.Bundle?) = startActivity(intent)
    open fun sendBroadcast(intent: Intent) = Unit
    open fun startService(intent: Intent): ComponentName? = ServiceHost.start(intent)
    open fun startForegroundService(intent: Intent): ComponentName? = ServiceHost.start(intent)
    open fun stopService(intent: Intent): Boolean = false
    open fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? = null
    open fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter, flags: Int): Intent? = null
    open fun unregisterReceiver(receiver: BroadcastReceiver) = Unit
    /** Granted, except notifications, which iOS answers for ([android.app.IosNotifications]). */
    open fun checkSelfPermission(permission: String): Int =
        if (permission == android.Manifest.permission.POST_NOTIFICATIONS && !android.app.IosNotifications.allowed) {
            PackageManager.PERMISSION_DENIED
        } else {
            PackageManager.PERMISSION_GRANTED
        }
    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences = SharedPreferences.named(name)
    open fun grantUriPermission(toPackage: String, uri: Uri, modeFlags: Int) = Unit
    open fun revokeUriPermission(uri: Uri, modeFlags: Int) = Unit

    companion object {
        const val MODE_PRIVATE = 0
        const val CLIPBOARD_SERVICE = "clipboard"
        const val CONNECTIVITY_SERVICE = "connectivity"
        const val ACCESSIBILITY_SERVICE = "accessibility"
        const val NOTIFICATION_SERVICE = "notification"
        const val TELEPHONY_SERVICE = "phone"
        const val STORAGE_SERVICE = "storage"
        const val WINDOW_SERVICE = "window"
        const val UI_MODE_SERVICE = "uimode"
        const val ACTIVITY_SERVICE = "activity"
        const val POWER_SERVICE = "power"
        const val AUDIO_SERVICE = "audio"
        const val DISPLAY_SERVICE = "display"
        const val JOB_SCHEDULER_SERVICE = "jobscheduler"
        const val LOCALE_SERVICE = "locale"
        const val RECEIVER_EXPORTED = 2
        const val RECEIVER_NOT_EXPORTED = 4
    }
}

open class ContextWrapper(base: Context?) : Context() {
    open val baseContext: Context? = base
    override val applicationContext: Context get() = baseContext?.applicationContext ?: this
}

class ComponentName(val packageName: String, val className: String)

/** What an app is told when the system's configuration changes or memory runs short, Android's. */
interface ComponentCallbacks {
    fun onConfigurationChanged(newConfig: android.content.res.Configuration)

    fun onLowMemory()
}

/** [ComponentCallbacks] with the memory levels: iOS's memory warnings arrive as the running levels. */
interface ComponentCallbacks2 : ComponentCallbacks {
    fun onTrimMemory(level: Int)

    companion object {
        const val TRIM_MEMORY_RUNNING_MODERATE = 5
        const val TRIM_MEMORY_RUNNING_LOW = 10
        const val TRIM_MEMORY_RUNNING_CRITICAL = 15
        const val TRIM_MEMORY_UI_HIDDEN = 20
        const val TRIM_MEMORY_BACKGROUND = 40
        const val TRIM_MEMORY_MODERATE = 60
        const val TRIM_MEMORY_COMPLETE = 80
    }
}

/** android.content.Intent: an action, a target and extras, read back by whoever handles it. */
open class Intent(var action: String? = null, var data: Uri? = null) {
    constructor(context: Context, target: KClass<*>) : this(null as String?) {
        component = ComponentName(context.packageName, target.simpleName.orEmpty())
    }

    constructor(action: String, uri: Uri?, context: Context, target: KClass<*>) : this(action, uri) {
        component = ComponentName(context.packageName, target.simpleName.orEmpty())
    }

    var type: String? = null
    var flags: Int = 0
    var `package`: String? = null
    var component: ComponentName? = null
    var clipData: ClipData? = null
    val categories = LinkedHashSet<String>()
    private val extras = LinkedHashMap<String, Any?>()

    fun setAction(value: String?): Intent = apply { action = value }
    fun setData(value: Uri?): Intent = apply { data = value }
    fun setType(value: String?): Intent = apply { type = value }
    fun setDataAndType(value: Uri?, mime: String?): Intent = apply { data = value; type = mime }
    fun setPackage(value: String?): Intent = apply { `package` = value }
    fun setFlags(value: Int): Intent = apply { flags = value }
    fun addFlags(value: Int): Intent = apply { flags = flags or value }
    fun addCategory(category: String): Intent = apply { categories += category }
    fun setClassName(packageName: String, className: String): Intent = apply { component = ComponentName(packageName, className) }

    fun putExtra(name: String, value: String?): Intent = apply { extras[name] = value }
    fun putExtra(name: String, value: CharSequence?): Intent = apply { extras[name] = value }
    fun putExtra(name: String, value: Int): Intent = apply { extras[name] = value }
    fun putExtra(name: String, value: Long): Intent = apply { extras[name] = value }
    fun putExtra(name: String, value: Boolean): Intent = apply { extras[name] = value }
    fun putExtra(name: String, value: Float): Intent = apply { extras[name] = value }
    fun putExtra(name: String, value: Array<String>?): Intent = apply { extras[name] = value }
    fun putExtra(name: String, value: Array<Uri>?): Intent = apply { extras[name] = value }
    fun putExtra(name: String, value: IntArray?): Intent = apply { extras[name] = value }
    fun putExtra(name: String, value: LongArray?): Intent = apply { extras[name] = value }
    fun putExtra(name: String, value: Uri?): Intent = apply { extras[name] = value }
    fun putExtra(name: String, value: android.os.Bundle?): Intent = apply { extras[name] = value }
    fun putExtra(name: String, value: Intent?): Intent = apply { extras[name] = value }
    fun putExtra(name: String, value: ByteArray?): Intent = apply { extras[name] = value }
    fun getByteArrayExtra(name: String): ByteArray? = extras[name] as? ByteArray
    fun putStringArrayListExtra(name: String, value: ArrayList<String>?): Intent = apply { extras[name] = value }
    fun putParcelableArrayListExtra(name: String, value: ArrayList<out Any>?): Intent = apply { extras[name] = value }

    fun hasExtra(name: String): Boolean = name in extras

    /** An extra as it was put, whatever its type: for the iOS share sheet. */
    internal fun rawExtra(name: String): Any? = extras[name]
    fun removeExtra(name: String) {
        extras.remove(name)
    }
    fun resolveActivity(pm: PackageManager): ComponentName? = null
    fun getStringExtra(name: String): String? = extras[name]?.toString()
    fun getCharSequenceExtra(name: String): CharSequence? = extras[name] as? CharSequence
    fun getIntExtra(name: String, fallback: Int): Int = extras[name] as? Int ?: fallback
    fun getLongExtra(name: String, fallback: Long): Long = extras[name] as? Long ?: fallback
    fun getBooleanExtra(name: String, fallback: Boolean): Boolean = extras[name] as? Boolean ?: fallback
    fun getFloatExtra(name: String, fallback: Float): Float = extras[name] as? Float ?: fallback
    fun getIntArrayExtra(name: String): IntArray? = extras[name] as? IntArray
    fun getLongArrayExtra(name: String): LongArray? = extras[name] as? LongArray
    @Suppress("UNCHECKED_CAST")
    fun getStringArrayExtra(name: String): Array<String>? = extras[name] as? Array<String>
    @Suppress("UNCHECKED_CAST")
    fun getStringArrayListExtra(name: String): ArrayList<String>? = extras[name] as? ArrayList<String>
    @Suppress("UNCHECKED_CAST")
    fun <T> getParcelableExtra(name: String): T? = extras[name] as? T

    companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val ACTION_SEND = "android.intent.action.SEND"
        const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"
        const val ACTION_SENDTO = "android.intent.action.SENDTO"
        const val ACTION_MAIN = "android.intent.action.MAIN"
        const val ACTION_OPEN_DOCUMENT_TREE = "android.intent.action.OPEN_DOCUMENT_TREE"
        const val ACTION_OPEN_DOCUMENT = "android.intent.action.OPEN_DOCUMENT"
        const val ACTION_CREATE_DOCUMENT = "android.intent.action.CREATE_DOCUMENT"
        const val ACTION_GET_CONTENT = "android.intent.action.GET_CONTENT"
        const val ACTION_CHOOSER = "android.intent.action.CHOOSER"
        const val ACTION_PICK = "android.intent.action.PICK"
        const val ACTION_LOCALE_CHANGED = "android.intent.action.LOCALE_CHANGED"
        const val ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"
        const val ACTION_MEDIA_MOUNTED = "android.intent.action.MEDIA_MOUNTED"
        const val ACTION_MEDIA_UNMOUNTED = "android.intent.action.MEDIA_UNMOUNTED"
        const val ACTION_MEDIA_EJECT = "android.intent.action.MEDIA_EJECT"
        const val ACTION_MEDIA_REMOVED = "android.intent.action.MEDIA_REMOVED"
        const val ACTION_MEDIA_BAD_REMOVAL = "android.intent.action.MEDIA_BAD_REMOVAL"
        const val EXTRA_TEXT = "android.intent.extra.TEXT"
        const val EXTRA_SUBJECT = "android.intent.extra.SUBJECT"
        const val EXTRA_STREAM = "android.intent.extra.STREAM"
        const val EXTRA_EMAIL = "android.intent.extra.EMAIL"
        const val EXTRA_TITLE = "android.intent.extra.TITLE"
        const val EXTRA_MIME_TYPES = "android.intent.extra.MIME_TYPES"
        const val EXTRA_INTENT = "android.intent.extra.INTENT"
        const val EXTRA_LOCAL_ONLY = "android.intent.extra.LOCAL_ONLY"
        const val CATEGORY_DEFAULT = "android.intent.category.DEFAULT"
        const val CATEGORY_BROWSABLE = "android.intent.category.BROWSABLE"
        const val CATEGORY_OPENABLE = "android.intent.category.OPENABLE"
        const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"
        const val FLAG_ACTIVITY_NEW_TASK = 0x10000000
        const val FLAG_ACTIVITY_CLEAR_TOP = 0x04000000
        const val FLAG_ACTIVITY_SINGLE_TOP = 0x20000000
        const val FLAG_ACTIVITY_NO_HISTORY = 0x40000000
        const val FLAG_ACTIVITY_CLEAR_TASK = 0x00008000
        const val FLAG_ACTIVITY_REORDER_TO_FRONT = 0x00020000
        const val FLAG_GRANT_READ_URI_PERMISSION = 0x00000001
        const val FLAG_GRANT_WRITE_URI_PERMISSION = 0x00000002
        const val FLAG_GRANT_PERSISTABLE_URI_PERMISSION = 0x00000040
        const val FLAG_GRANT_PREFIX_URI_PERMISSION = 0x00000080

        fun createChooser(target: Intent, title: CharSequence?): Intent = Intent(ACTION_CHOOSER).putExtra(EXTRA_INTENT, target)
    }
}

class IntentFilter(vararg actions: String) {
    val actions: MutableList<String> = actions.toMutableList()
    fun addAction(action: String) {
        actions += action
    }
    fun addDataScheme(scheme: String) = Unit
    fun addCategory(category: String) = Unit
}

abstract class BroadcastReceiver {
    abstract fun onReceive(context: Context?, intent: Intent?)
}

/** The clipboard: UIPasteboard's general pasteboard, plain text. */
class ClipboardManager private constructor() {
    fun setPrimaryClip(clip: ClipData) {
        UIPasteboard.generalPasteboard.string = clip.text?.toString()
    }

    val primaryClip: ClipData? get() = UIPasteboard.generalPasteboard.string?.let { ClipData.newPlainText("", it) }

    fun hasPrimaryClip(): Boolean = UIPasteboard.generalPasteboard.hasStrings

    companion object {
        internal val Shared = ClipboardManager()
    }
}

class ClipData private constructor(val label: CharSequence?, val text: CharSequence?, val uri: Uri?) {
    class Item(val text: CharSequence?, val uri: Uri? = null)

    fun getItemAt(index: Int): Item = Item(text, uri)
    val itemCount: Int get() = 1
    fun addItem(item: Item) = Unit
    fun addItem(resolver: ContentResolver, item: Item) = Unit

    companion object {
        fun newPlainText(label: CharSequence?, text: CharSequence?): ClipData = ClipData(label, text, null)
        fun newUri(resolver: ContentResolver?, label: CharSequence?, uri: Uri): ClipData = ClipData(label, uri.toString(), uri)
        fun newRawUri(label: CharSequence?, uri: Uri): ClipData = ClipData(label, uri.toString(), uri)
    }
}

class ContentValues {
    private val values = LinkedHashMap<String, Any?>()
    fun put(key: String, value: String?) { values[key] = value }
    fun put(key: String, value: Int?) { values[key] = value }
    fun put(key: String, value: Long?) { values[key] = value }
    fun put(key: String, value: Boolean?) { values[key] = value }
    fun putNull(key: String) { values[key] = null }
    fun get(key: String): Any? = values[key]
    fun clear() = values.clear()
}

object ContentUris {
    fun withAppendedId(base: Uri, id: Long): Uri = Uri.parse("$base/$id")
    fun parseId(uri: Uri): Long = uri.lastPathSegment?.toLongOrNull() ?: -1
}

/**
 * Android's content providers have no iOS counterpart: a document tree, MediaStore or another
 * app's file is never there, so a query finds nothing and an open fails as it would for a
 * missing document. Files reached as file:// work.
 */
class ContentResolver private constructor() {
    fun openInputStream(uri: Uri): java.io.InputStream? =
        uri.filePath()?.let { java.io.FileInputStream(it) } ?: throw java.io.FileNotFoundException(uri.toString())

    fun openOutputStream(uri: Uri): java.io.OutputStream? = openOutputStream(uri, "w")

    fun openOutputStream(uri: Uri, mode: String): java.io.OutputStream? {
        val path = uri.filePath() ?: throw java.io.FileNotFoundException(uri.toString())
        val out = java.io.FileOutputStream(File(path), append = mode.contains('a'))
        val video = PhotosInbox.pending[path] ?: return out
        return PhotosInbox.Stream(out, path, video)
    }

    fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): android.database.Cursor? = null

    fun query(uri: Uri, projection: Array<String>?, queryArgs: android.os.Bundle?, cancellationSignal: Any?): android.database.Cursor? = null

    /**
     * MediaStore's pictures and videos as iOS's Photos: the new item is a file in the app's own
     * folder, named as asked, and closing the stream written to it adds it to Photos ([PhotosInbox]).
     * Anywhere else there is nothing to insert into, as before.
     */
    fun insert(uri: Uri, values: ContentValues?): Uri? {
        val video = when (uri.toString()) {
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI.toString() -> false
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI.toString() -> true
            else -> return null
        }
        return PhotosInbox.make(values?.get(android.provider.MediaStore.Images.Media.DISPLAY_NAME) as? String, video)
    }

    fun update(uri: Uri, values: ContentValues?, where: String?, selectionArgs: Array<String>?): Int = 0

    fun delete(uri: Uri, where: String?, selectionArgs: Array<String>?): Int {
        uri.filePath()?.let { PhotosInbox.pending.remove(it) }
        return if (uri.filePath()?.let { File(it).delete() } == true) 1 else 0
    }

    fun getType(uri: Uri): String? = null

    fun openFileDescriptor(uri: Uri, mode: String): android.os.ParcelFileDescriptor? =
        uri.filePath()?.let { android.os.ParcelFileDescriptor(java.io.File(it), mode) }

    fun takePersistableUriPermission(uri: Uri, modeFlags: Int) {
        throw SecurityException("No persistable permission for $uri on iOS")
    }

    fun releasePersistableUriPermission(uri: Uri, modeFlags: Int) = Unit

    val persistedUriPermissions: List<UriPermission> get() = emptyList()

    fun notifyChange(uri: Uri, observer: Any?) = Unit

    companion object {
        internal val Shared = ContentResolver()
        const val SCHEME_CONTENT = "content"
        const val SCHEME_FILE = "file"
        const val SCHEME_ANDROID_RESOURCE = "android.resource"
    }
}

class UriPermission(val uri: Uri, val isReadPermission: Boolean, val isWritePermission: Boolean, val persistedTime: Long)

private fun Uri.filePath(): String? = if (scheme == "file" || scheme == null) path else null

/**
 * Where Android's services land on iOS. The shell registers a runner per service (the downloads,
 * by class name); a start with no runner does nothing, as on a device that does not have it.
 */
object ServiceHost {
    private val runners = HashMap<String, (Intent) -> Unit>()

    fun register(serviceName: String, runner: (Intent) -> Unit) {
        runners[serviceName] = runner
    }

    fun start(intent: Intent): ComponentName? {
        val name = intent.component?.className ?: return null
        val runner = runners[name] ?: return null
        runner(intent)
        return intent.component
    }
}

/**
 * Where an intent for one of the app's own activities lands on iOS (the player, by class name).
 * The shell registers a runner per activity; [start] says whether one took the intent.
 */
object ActivityHost {
    private val runners = HashMap<String, (Intent) -> Unit>()

    fun register(activityName: String, runner: (Intent) -> Unit) {
        runners[activityName] = runner
    }

    fun start(intent: Intent): Boolean {
        val name = intent.component?.className ?: return false
        val runner = runners[name] ?: return false
        runner(intent)
        return true
    }
}

/** android.content.SharedPreferences over NSUserDefaults, one suite per name. */
class SharedPreferences private constructor(private val defaults: platform.Foundation.NSUserDefaults) {
    fun getBoolean(key: String, fallback: Boolean): Boolean = if (defaults.objectForKey(key) == null) fallback else defaults.boolForKey(key)
    fun getString(key: String, fallback: String?): String? = defaults.stringForKey(key) ?: fallback
    fun getInt(key: String, fallback: Int): Int = if (defaults.objectForKey(key) == null) fallback else defaults.integerForKey(key).toInt()
    fun getLong(key: String, fallback: Long): Long = if (defaults.objectForKey(key) == null) fallback else defaults.integerForKey(key)
    fun getFloat(key: String, fallback: Float): Float = if (defaults.objectForKey(key) == null) fallback else defaults.floatForKey(key)
    fun contains(key: String): Boolean = defaults.objectForKey(key) != null
    fun edit(): Editor = Editor(defaults)

    class Editor internal constructor(private val defaults: platform.Foundation.NSUserDefaults) {
        fun putBoolean(key: String, value: Boolean): Editor = apply { defaults.setBool(value, key) }
        fun putString(key: String, value: String?): Editor = apply { defaults.setObject(value, key) }
        fun putInt(key: String, value: Int): Editor = apply { defaults.setInteger(value.toLong(), key) }
        fun putLong(key: String, value: Long): Editor = apply { defaults.setInteger(value, key) }
        fun putFloat(key: String, value: Float): Editor = apply { defaults.setFloat(value, key) }
        fun remove(key: String): Editor = apply { defaults.removeObjectForKey(key) }
        fun clear(): Editor = this
        fun apply() = Unit
        fun commit(): Boolean = true
    }

    companion object {
        fun named(name: String): SharedPreferences =
            SharedPreferences(platform.Foundation.NSUserDefaults(suiteName = name) ?: platform.Foundation.NSUserDefaults.standardUserDefaults)
    }
}

/**
 * Android's ACTION_SEND and ACTION_SEND_MULTIPLE as iOS's share sheet: the files of EXTRA_STREAM
 * and the text of EXTRA_TEXT. On an iPad the sheet is a popover, anchored to the middle of the
 * window, which iOS requires.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal object ShareSheet {
    fun show(intent: Intent) {
        val items = ArrayList<Any>()
        when (val stream = intent.rawExtra(Intent.EXTRA_STREAM)) {
            is Uri -> stream.sharedUrl()?.let(items::add)
            is Collection<*> -> stream.filterIsInstance<Uri>().mapNotNullTo(items) { it.sharedUrl() }
        }
        intent.rawExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.let(items::add)
        if (items.isEmpty()) return
        val presenter = topController() ?: return
        val sheet = platform.UIKit.UIActivityViewController(activityItems = items, applicationActivities = null)
        sheet.popoverPresentationController?.let { popover ->
            val view = presenter.view
            popover.sourceView = view
            popover.sourceRect = view.bounds.centerPoint()
            popover.permittedArrowDirections = 0u
        }
        presenter.presentViewController(sheet, animated = true, completion = null)
    }

    private fun Uri.sharedUrl(): NSURL? = path?.let { NSURL.fileURLWithPath(it) }

    private fun topController(): platform.UIKit.UIViewController? {
        val scene = UIApplication.sharedApplication.connectedScenes
            .firstOrNull { it is platform.UIKit.UIWindowScene } as? platform.UIKit.UIWindowScene ?: return null
        var top = (scene.keyWindow ?: scene.windows.firstOrNull() as? platform.UIKit.UIWindow)?.rootViewController ?: return null
        while (true) top = top.presentedViewController ?: return top
    }
}

/** A one-point rectangle at the middle of [this], for a popover with no arrow. */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun kotlinx.cinterop.CValue<platform.CoreGraphics.CGRect>.centerPoint(): kotlinx.cinterop.CValue<platform.CoreGraphics.CGRect> =
    useContents { platform.CoreGraphics.CGRectMake(origin.x + size.width / 2, origin.y + size.height / 2, 1.0, 1.0) }

/**
 * What [ContentResolver.insert] made for MediaStore, on its way to Photos: a file in the app's
 * temporary folder (kept after, for a Share of it), added to Photos as its stream closes, with the
 * add-only access iOS asks for the first time (Info.plist says why). A close that Photos refused
 * fails, as a write does on Android when the item cannot be stored, so the caller reports it.
 */
internal object PhotosInbox {
    /** Each file waiting to be written and added, and whether it is a video. */
    val pending = HashMap<String, Boolean>()

    fun make(displayName: String?, video: Boolean): Uri? {
        val folder = File(platform.Foundation.NSTemporaryDirectory(), "media-store").apply { mkdirs() }
        val name = displayName?.replace('/', ' ')?.trim()?.ifBlank { null } ?: if (video) "TMPlayer.mp4" else "TMPlayer.png"
        val stem = name.substringBeforeLast('.')
        val extension = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        // As MediaStore does with a name already there: " (1)", " (2)".
        var file = File(folder, name)
        var n = 1
        while (file.exists()) file = File(folder, "$stem (${n++})$extension")
        if (runCatching { java.io.FileOutputStream(file).close() }.isFailure) return null
        pending[file.path] = video
        return Uri.fromFile(file)
    }

    /** The file's stream: its close adds the file to Photos and waits for the answer. */
    class Stream(private val out: java.io.OutputStream, private val path: String, private val video: Boolean) : java.io.OutputStream() {
        override fun write(b: Int) = out.write(b)

        override fun write(buffer: ByteArray, offset: Int, length: Int) = out.write(buffer, offset, length)

        override fun close() {
            out.close()
            if (pending.remove(path) == null) return
            val why = add(path, video)
            if (why != null) throw java.io.IOException(why)
        }
    }

    /** Adds the file at [path] to Photos; null when it is there, otherwise why not. */
    private fun add(path: String, video: Boolean): String? {
        if (platform.Foundation.NSThread.isMainThread) {
            // Never waited for on the main thread, where iOS's question would have to show.
            ask { allowed -> if (allowed) store(path, video) {} }
            return null
        }
        val done = platform.darwin.dispatch_semaphore_create(0)
        var allowed = false
        ask {
            allowed = it
            platform.darwin.dispatch_semaphore_signal(done)
        }
        platform.darwin.dispatch_semaphore_wait(done, platform.darwin.DISPATCH_TIME_FOREVER)
        if (!allowed) return "Photos access was not allowed"
        var why: String? = "Photos did not answer"
        store(path, video) {
            why = it
            platform.darwin.dispatch_semaphore_signal(done)
        }
        platform.darwin.dispatch_semaphore_wait(done, platform.darwin.DISPATCH_TIME_FOREVER)
        return why
    }

    private fun ask(answer: (Boolean) -> Unit) {
        val level = platform.Photos.PHAccessLevelAddOnly
        val status = platform.Photos.PHPhotoLibrary.authorizationStatusForAccessLevel(level)
        if (status != platform.Photos.PHAuthorizationStatusNotDetermined) {
            answer(status == platform.Photos.PHAuthorizationStatusAuthorized || status == platform.Photos.PHAuthorizationStatusLimited)
            return
        }
        platform.Photos.PHPhotoLibrary.requestAuthorizationForAccessLevel(level) { granted ->
            answer(granted == platform.Photos.PHAuthorizationStatusAuthorized || granted == platform.Photos.PHAuthorizationStatusLimited)
        }
    }

    private fun store(path: String, video: Boolean, answer: (String?) -> Unit) {
        val url = platform.Foundation.NSURL.fileURLWithPath(path)
        platform.Photos.PHPhotoLibrary.sharedPhotoLibrary().performChanges(
            {
                if (video) {
                    platform.Photos.PHAssetChangeRequest.creationRequestForAssetFromVideoAtFileURL(url)
                } else {
                    platform.Photos.PHAssetChangeRequest.creationRequestForAssetFromImageAtFileURL(url)
                }
            },
        ) { success, error -> answer(if (success) null else error?.localizedDescription ?: "Photos refused the file") }
    }
}
