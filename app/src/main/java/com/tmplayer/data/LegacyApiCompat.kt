package com.tmplayer.data

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

// The app installs down to Android 6 (API 23) for the older televisions, the Fire TV Stick 4K on
// Fire OS 6 among them. These are the Android 7 and 8 calls the app makes in more than one place,
// each behind a plain API level check that lint's NewApi check can follow. R8 moves the newer call
// into a class of its own, so the caller still verifies and runs compiled on the old device, and
// the check itself costs one comparison.

/**
 * A notification builder on [channelId]. Android 7 and older have no channels, so there the id
 * is not used and the channel's [importance] (a `NotificationManager.IMPORTANCE_*`) becomes the
 * notification's own priority and sound: a quiet channel stays quiet and the rest still ping.
 */
fun notificationBuilder(context: Context, channelId: String, importance: Int): Notification.Builder =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Notification.Builder(context, channelId)
    } else {
        @Suppress("DEPRECATION")
        Notification.Builder(context).apply {
            setPriority(OldAndroid.priorityFor(importance))
            if (OldAndroid.soundFor(importance)) setDefaults(Notification.DEFAULT_SOUND)
        }
    }

/**
 * Starts a service that will call startForeground. `startForegroundService` arrived with Android
 * 8's background limits; before them a plain `startService` is the same thing.
 */
fun Context.startForegroundServiceCompat(intent: Intent) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
}

/** Leaves the foreground and takes the notification down with it, the flag being Android 7's. */
fun Service.stopForegroundRemoving() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
    } else {
        @Suppress("DEPRECATION")
        stopForeground(true)
    }
}

/**
 * Picture in picture as the player asks for it: a device with the feature and an Android with
 * `PictureInPictureParams`. Android 7's televisions report the feature without the latter, and the
 * menu entry is then left out rather than doing nothing.
 */
@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.O)
fun Context.hasPictureInPicture(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

/**
 * Whether a scale or a move set on the views around [videoView] carries the video with it, which
 * the player's pinch zoom is (PictureZoom). A TextureView is drawn with the rest of the window and
 * always follows. A SurfaceView's frames go to a surface of their own, placed by the window
 * manager: Android 7 moved that surface in step with the view's transformed bounds ("simple
 * translations or scales of a SurfaceView no longer create rendering artifacts"), while on
 * Android 6 the view's scale is ignored and the picture would stay put under a moved hole. There
 * the pinch keeps stepping the picture shape instead.
 */
fun videoFollowsTransforms(videoView: android.view.View?): Boolean =
    videoView is android.view.TextureView ||
        (videoView != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
