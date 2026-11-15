package com.tmplayer.player

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.tmplayer.data.PlayerEngine
import java.io.File

/**
 * Native crash isolation for the VLC engine on Android (PR6), around the pure rules of
 * [EngineCrashes]: the mark is written while VLC is meant to be playing ([active] true: opening,
 * playing) and taken away when it is not (paused, stopped, released), so a process that dies with
 * the mark still there died with VLC running. [atStart] reads it at the next start, asks Android
 * how that process ended (Android 11 and later), and counts a VLC crash for [EngineSelector].
 *
 * Two small files in the app's own folder, nothing else: the mark and the count. Never throws.
 */
object EngineCrashWatch {

    private const val TAG = "TMPlayer"

    private fun markFile(context: Context) = File(context.filesDir, "engine/vlc-playing.txt")

    private fun countFile(context: Context) = File(context.filesDir, "engine/vlc-crashes.txt")

    /** VLC is opening or playing ([on]), or not any more. Main thread, a few bytes at most. */
    fun active(context: Context, on: Boolean) {
        runCatching {
            val file = markFile(context)
            if (!on) {
                file.delete()
                return
            }
            file.parentFile?.mkdirs()
            val mark = EngineMark(PlayerEngine.Vlc, System.currentTimeMillis(), sinceProcessStart())
            file.writeText(mark.encode())
        }
    }

    /** What this device has learned of VLC crashing ([VlcCrashes]); none for a missing or bad file. */
    fun crashes(context: Context): VlcCrashes =
        runCatching { countFile(context).takeIf { it.isFile }?.readText()?.take(64) }.getOrNull()
            .let(VlcCrashes::decode)

    /** The card offering ExoPlayer was shown for the crashes counted so far. */
    fun offered(context: Context) = write(context, crashes(context).offered())

    /**
     * At start, before [com.tmplayer.data.SafeMode] decides the run: the mark the last run left,
     * when it was a VLC crash (counted here), or null. The mark is taken away either way.
     */
    fun atStart(context: Context): EngineMark? = runCatching {
        val file = markFile(context)
        if (!file.isFile) return null
        val mark = EngineMark.decode(file.readText().take(128))
        file.delete()
        val exit = lastExit(context, mark?.startedAtMs ?: 0L)
        if (!EngineCrashes.isVlcCrash(mark, exit, System.currentTimeMillis())) return null
        write(context, crashes(context).crashed())
        Log.w(TAG, "The last run ended while VLC played (${exit.name}): Automatic leaves VLC alone now")
        mark
    }.getOrNull()

    private fun write(context: Context, crashes: VlcCrashes) {
        runCatching {
            val file = countFile(context)
            file.parentFile?.mkdirs()
            file.writeText(crashes.encode())
        }
    }

    /** How the newest process exit since [sinceMs] ended, as far as Android says. */
    private fun lastExit(context: Context, sinceMs: Long): LastExit {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return LastExit.Unknown
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return LastExit.Unknown
        val exits = runCatching { manager.getHistoricalProcessExitReasons(context.packageName, 0, 4) }.getOrNull()
            ?: return LastExit.Unknown
        val newest = exits.filter { it.timestamp >= sinceMs }.maxByOrNull { it.timestamp } ?: return LastExit.Unknown
        return when (newest.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> LastExit.NativeCrash
            ApplicationExitInfo.REASON_ANR -> LastExit.NotResponding
            ApplicationExitInfo.REASON_CRASH -> LastExit.JavaCrash
            else -> LastExit.Ended
        }
    }

    private fun sinceProcessStart(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            (SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()).coerceAtLeast(0L)
        } else {
            0L
        }
}
