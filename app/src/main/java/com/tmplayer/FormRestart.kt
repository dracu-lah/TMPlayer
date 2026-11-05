package com.tmplayer

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import com.tmplayer.data.FormFactor
import com.tmplayer.data.InterfaceChoice
import com.tmplayer.platform.Logger

/**
 * Switches the layout (HX78): saves the Interface choice and starts TMPlayer again in it.
 *
 * The layout is decided once per process ([FormFactor.isTv] is cached and read in `App.onCreate`
 * for the motion and volume defaults), so a switch needs a new process. Starting the activity
 * and exiting in the same process races the start against the exit on some Android versions, so
 * the start is handed to [FormRestartActivity] in a process of its own, which ends the old one
 * and opens the app in a fresh one (the ProcessPhoenix pattern).
 */
object FormRestart {

    private const val TAG = "tm-form"
    internal const val EXTRA_PID = "pid"
    private const val RESTART_PROCESS_SUFFIX = ":restart"

    fun switchTo(activity: Activity, choice: InterfaceChoice) {
        FormFactor.setChoice(activity, choice)
        Logger.i(TAG, "layout set to ${choice.key}, restarting")
        val started = runCatching {
            activity.startActivity(
                Intent(activity, FormRestartActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_PID, Process.myPid()),
            )
        }.isSuccess
        if (!started) {
            // The choice is saved either way; it applies on the next start.
            Logger.w(TAG, "restart could not be started; the layout changes on the next start")
            return
        }
        activity.finish()
        Runtime.getRuntime().exit(0)
    }

    /** True in [FormRestartActivity]'s process, where `App.onCreate` must not start the app. */
    fun inRestartProcess(app: Application): Boolean {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            runCatching { java.io.File("/proc/self/cmdline").readText().trim('\u0000', ' ', '\n') }.getOrNull()
        }
        return name?.endsWith(RESTART_PROCESS_SUFFIX) == true
    }
}

/**
 * Runs in the `:restart` process: ends the old app process, opens [MainActivity] in a new one and
 * goes. It draws nothing (a translucent theme) and stays out of Recents.
 */
class FormRestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val old = intent?.getIntExtra(FormRestart.EXTRA_PID, -1) ?: -1
        if (old > 0 && old != Process.myPid()) Process.killProcess(old)
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
        }
        finish()
        Runtime.getRuntime().exit(0)
    }
}
