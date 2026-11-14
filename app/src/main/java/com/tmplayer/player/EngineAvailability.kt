package com.tmplayer.player

import android.content.Context
import com.tmplayer.BuildConfig
import com.tmplayer.data.DeviceFacts
import com.tmplayer.data.PlayerEngine
import java.io.File

/**
 * Whether the VLC engine can run here (PR2): asked of [EngineSelector], the one place that
 * decides engines, with this as the only input about the library.
 *
 * True only when all of these hold, none of which loads anything (VLC is never loaded at app
 * start, nor to draw Settings):
 *  - the build has libVLC ([VlcRuntime.BUNDLED], a `-PtmVlc=true` build, never F-Droid's),
 *  - its native libraries were installed for this device's ABI (the app's native folder),
 *  - it has not failed to load on this device in this version ([markLoadFailed]).
 */
object EngineAvailability {

    @Volatile
    private var failedThisRun = false

    /** True when Settings may show VLC as a choice, and the selector may pick it. */
    val vlcOffered: Boolean
        get() {
            if (!VlcRuntime.BUNDLED || failedThisRun) return false
            val present = librariesPresent
            val input = EngineInput(EngineChoice.Automatic, DeviceFacts(), vlcAvailable = present)
            return EngineSelector.toOffer(input, PlayerEngine.ExoPlayer) == PlayerEngine.Vlc
        }

    private val librariesPresent: Boolean by lazy {
        val context = com.tmplayer.data.AndroidStorage.appContext ?: return@lazy false
        runCatching { present(context) }.getOrDefault(false)
    }

    private fun present(context: Context): Boolean {
        if (failedMark(context).isFile) return false
        val dir = context.applicationInfo.nativeLibraryDir?.let(::File) ?: return false
        return VlcRuntime.libraries.isNotEmpty() && VlcRuntime.libraries.all { File(dir, it).isFile }
    }

    /**
     * libVLC did not start ([VlcRuntime.backend] returned null): VLC is not offered again in this
     * version on this device. A new version tries once more, as its library may differ.
     */
    fun markLoadFailed(context: Context) {
        failedThisRun = true
        runCatching {
            val mark = failedMark(context)
            mark.parentFile?.mkdirs()
            mark.writeText(BuildConfig.VERSION_NAME)
        }
    }

    private fun failedMark(context: Context): File =
        File(context.filesDir, "engine/vlc-failed-${BuildConfig.VERSION_CODE}")
}
