package com.tmplayer.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.SurfaceView
import android.view.View
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia

/**
 * libVLC in a build made with `-PtmVlc=true` (PR2). Nothing here runs at app start: the library
 * is loaded by the first [backend] call, which is the first video that plays in VLC, and a load
 * that fails (a wrong ABI, a 32-bit library missing, a broken install) is caught and reported as
 * null, so the player stays on ExoPlayer and [EngineAvailability] stops offering VLC.
 */
object VlcRuntime {
    const val BUNDLED: Boolean = true

    /** What `LibVLC` loads, looked for in the app's native folder before anything is loaded. */
    val libraries: List<String> = listOf("libc++_shared.so", "libvlc.so", "libvlcjni.so")

    private const val TAG = "TMPlayer"

    /** A new player over a new library instance, or null when libVLC cannot start here. Main thread. */
    fun backend(context: Context, options: VlcOptions): VlcBackend? = try {
        LibVlcBackend(context.applicationContext, options)
    } catch (e: Throwable) {
        // UnsatisfiedLinkError and LinkageError are Errors, not Exceptions: caught on purpose,
        // since a library that does not load here must only cost the VLC option.
        Log.w(TAG, "libVLC could not start: ${e.javaClass.simpleName}: ${e.message}")
        null
    }
}

/** libVLC's player behind [VlcBackend]. One per video; [release] ends the library instance with it. */
private class LibVlcBackend(private val context: Context, options: VlcOptions) : VlcBackend {

    private val library = LibVLC(context, options.arguments())
    private val player = MediaPlayer(library)
    private val main = Handler(Looper.getMainLooper())
    private val hardware = options.hardwareDecoding
    private var descriptor: ParcelFileDescriptor? = null
    private var view: SurfaceView? = null
    private var released = false

    private val sizeWatch = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
        runCatching { player.vlcVout.setWindowSize(v.width, v.height) }
    }

    override var listener: ((VlcEvent) -> Unit)? = null

    init {
        player.setEventListener { event ->
            val mapped = when (event.type) {
                MediaPlayer.Event.Opening -> VlcEvent.Opening
                MediaPlayer.Event.Buffering -> VlcEvent.Buffering(event.buffering)
                MediaPlayer.Event.Playing -> VlcEvent.Playing
                MediaPlayer.Event.Paused -> VlcEvent.Paused
                MediaPlayer.Event.Stopped -> VlcEvent.Stopped
                MediaPlayer.Event.EndReached -> VlcEvent.Ended
                MediaPlayer.Event.EncounteredError -> VlcEvent.Error
                MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected -> VlcEvent.TracksChanged
                MediaPlayer.Event.Vout -> VlcEvent.VideoOut
                else -> null
            } ?: return@setEventListener
            if (Looper.myLooper() == Looper.getMainLooper()) listener?.invoke(mapped) else main.post { listener?.invoke(mapped) }
        }
    }

    override fun open(source: EngineSource, startMs: Long) {
        closeDescriptor()
        val media = when (source) {
            is EngineSource.File -> Media(library, source.path)
            is EngineSource.Content -> {
                val fd = context.contentResolver.openFileDescriptor(Uri.parse(source.uri), "r")
                    ?: throw java.io.FileNotFoundException("No descriptor for the download")
                descriptor = fd
                Media(library, fd.fileDescriptor)
            }
            is EngineSource.Stream -> Media(library, Uri.parse(source.url))
        }
        media.setHWDecoderEnabled(hardware, false)
        if (startMs > 0) media.addOption(":start-time=${startMs / 1000.0}")
        player.media = media
        media.release()
    }

    override fun play() = player.play()

    override fun pause() = player.pause()

    override fun stop() = player.stop()

    override fun seekTo(positionMs: Long) {
        player.setTime(positionMs, false)
    }

    override val timeMs: Long get() = player.time

    override val lengthMs: Long get() = player.length

    override val isPlaying: Boolean get() = player.isPlaying

    override fun tracks(): List<VlcTrack> {
        val languages = HashMap<Int, String?>()
        player.media?.let { media ->
            runCatching {
                for (i in 0 until media.trackCount) {
                    val track = media.getTrack(i) ?: continue
                    languages[track.id] = track.language?.takeIf { it.isNotBlank() }
                }
            }
            media.release()
        }
        fun list(descriptions: Array<MediaPlayer.TrackDescription>?, type: EngineTrack.Type): List<VlcTrack> =
            descriptions.orEmpty().filter { it.id >= 0 }.map { VlcTrack(it.id, type, it.name.orEmpty(), languages[it.id] ?: languageInName(it.name)) }
        return list(player.audioTracks, EngineTrack.Type.Audio) + list(player.spuTracks, EngineTrack.Type.Subtitle)
    }

    override val audioTrack: Int get() = player.audioTrack

    override val spuTrack: Int get() = player.spuTrack

    override fun setAudioTrack(id: Int): Boolean = player.setAudioTrack(id)

    override fun setSpuTrack(id: Int): Boolean = player.setSpuTrack(id)

    override fun addSubtitle(uri: String): Boolean = player.addSlave(IMedia.Slave.Type.Subtitle, uri, true)

    override var rate: Float
        get() = player.rate
        set(value) {
            player.rate = value
        }

    override var volumePercent: Int
        get() = player.volume
        set(value) {
            player.volume = value.coerceIn(0, 100)
        }

    override fun setAspect(ratio: String?) {
        player.aspectRatio = ratio
    }

    override fun attach(view: SurfaceView?) {
        val vout = player.vlcVout
        this.view?.removeOnLayoutChangeListener(sizeWatch)
        if (vout.areViewsAttached()) vout.detachViews()
        this.view = view
        if (view == null) return
        vout.setVideoView(view)
        vout.attachViews()
        view.addOnLayoutChangeListener(sizeWatch)
        if (view.width > 0 && view.height > 0) vout.setWindowSize(view.width, view.height)
    }

    override fun setVideoTrackEnabled(enabled: Boolean) = player.setVideoTrackEnabled(enabled)

    override fun setDelays(audioUs: Long, subtitleUs: Long) {
        player.setAudioDelay(audioUs)
        player.setSpuDelay(subtitleUs)
    }

    override val videoWidth: Int get() = player.currentVideoTrack?.width ?: 0

    override val videoHeight: Int get() = player.currentVideoTrack?.height ?: 0

    override fun release() {
        if (released) return
        released = true
        listener = null
        runCatching { player.setEventListener(null) }
        runCatching { attach(null) }
        runCatching { player.release() }
        runCatching { library.release() }
        closeDescriptor()
    }

    private fun closeDescriptor() {
        runCatching { descriptor?.close() }
        descriptor = null
    }

    /** libVLC names a track "Track 1 - [English]" when the file has a language: the bracket, else null. */
    private fun languageInName(name: String?): String? =
        name?.substringAfterLast('[', "")?.substringBefore(']')?.trim()?.takeIf { it.isNotEmpty() }
}
