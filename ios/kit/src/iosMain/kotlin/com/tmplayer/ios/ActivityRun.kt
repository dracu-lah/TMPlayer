package com.tmplayer.ios

import android.app.Application
import androidx.activity.ComponentActivity
import com.tmplayer.platform.Logger

/**
 * One activity moved through Android's lifecycle, each step once and in Android's order (created,
 * started, resumed, then back down), with the app's lifecycle callbacks told of each, as Android
 * tells them. A step that throws is logged and the run goes on, so a gap on iOS never takes the
 * app down (AGENTS.md, error handling).
 */
internal class ActivityRun(private val app: Application, val activity: ComponentActivity) {
    private var started = false
    private var resumed = false
    private var destroyed = false

    /** Creates the activity; false when its onCreate failed, and the shell does not show it. */
    fun create(): Boolean {
        val made = step("create") { activity.onCreate(null) }
        if (made) app.dispatchActivity(activity) { it.onActivityCreated(activity, null) }
        return made
    }

    fun start() {
        if (started || destroyed) return
        started = true
        activity.window.shellShown(true)
        step("start") { activity.onStart() }
        app.dispatchActivity(activity) { it.onActivityStarted(activity) }
    }

    fun resume() {
        if (!started || resumed || destroyed) return
        resumed = true
        step("resume") { activity.onResume() }
        app.dispatchActivity(activity) { it.onActivityResumed(activity) }
    }

    fun pause() {
        if (!resumed) return
        resumed = false
        step("pause") { activity.onPause() }
        app.dispatchActivity(activity) { it.onActivityPaused(activity) }
    }

    fun stop() {
        pause()
        if (!started) return
        started = false
        step("stop") { activity.onStop() }
        app.dispatchActivity(activity) { it.onActivityStopped(activity) }
        activity.window.shellShown(false)
    }

    fun destroy() {
        stop()
        if (destroyed) return
        destroyed = true
        step("destroy") { activity.onDestroy() }
        app.dispatchActivity(activity) { it.onActivityDestroyed(activity) }
        activity.window.shellReset()
    }

    private fun step(name: String, call: () -> Unit): Boolean = runCatching(call)
        .onFailure { Logger.w(TAG, "${activity::class.simpleName} $name failed", it) }
        .isSuccess

    private companion object {
        const val TAG = "ActivityRun"
    }
}
