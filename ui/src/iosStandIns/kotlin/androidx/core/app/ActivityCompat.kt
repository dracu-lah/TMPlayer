package androidx.core.app

import android.app.Activity

object ActivityCompat {
    fun requestPermissions(activity: Activity, permissions: Array<String>, requestCode: Int) = activity.requestPermissions(permissions, requestCode)
    fun shouldShowRequestPermissionRationale(activity: Activity, permission: String): Boolean = false
}
