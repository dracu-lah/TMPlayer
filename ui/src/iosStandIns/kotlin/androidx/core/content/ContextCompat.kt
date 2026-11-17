package androidx.core.content

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.res.ResourceFiles
import android.graphics.drawable.Drawable
import android.net.Uri
import java.io.File

object ContextCompat {
    const val RECEIVER_EXPORTED = Context.RECEIVER_EXPORTED
    const val RECEIVER_NOT_EXPORTED = Context.RECEIVER_NOT_EXPORTED
    fun registerReceiver(context: Context, receiver: BroadcastReceiver?, filter: IntentFilter, flags: Int): Intent? = null
    fun checkSelfPermission(context: Context, permission: String): Int = context.checkSelfPermission(permission)
    fun startForegroundService(context: Context, intent: Intent) {
        context.startForegroundService(intent)
    }
    fun getExternalFilesDirs(context: Context, type: String?): Array<File?> = context.getExternalFilesDirs(type)

    /** A colour of :app's by its R.color id, from values/ or a colour list's default; Android throws on an unknown id. */
    fun getColor(context: Context, id: Int): Int {
        val reference = "@" + (ResourceFiles.reference(id) ?: throw IllegalArgumentException("No colour for resource $id"))
        return ResourceFiles.color(reference) ?: ResourceFiles.colorStateList(reference)?.defaultColor
            ?: throw IllegalArgumentException("No colour for $reference")
    }

    /** A colour list of :app's (res/color) or a plain colour as one, by its R.color id. */
    fun getColorStateList(context: Context, id: Int): ColorStateList? =
        ResourceFiles.reference(id)?.let { ResourceFiles.colorStateList("@$it") }

    /** A drawable of :app's by its R.drawable id, read from its XML. */
    fun getDrawable(context: Context, id: Int): Drawable? =
        ResourceFiles.reference(id)?.takeIf { it.startsWith("drawable/") }?.let { Drawable.fromReference("@$it") }
}

/** A file shared as file:// on iOS, which the share sheet takes as it is. */
object FileProvider {
    fun getUriForFile(context: Context, authority: String, file: File): Uri = Uri.fromFile(file)
}
