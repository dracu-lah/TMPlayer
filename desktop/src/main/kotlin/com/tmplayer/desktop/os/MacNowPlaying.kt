package com.tmplayer.desktop.os

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.tmplayer.platform.Logger
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * macOS Now Playing (the Control Center tile, the keyboard's media keys, AirPods): the mapping from
 * what the player says to what `MPNowPlayingInfoCenter` is told, and from the remote commands to
 * [MediaSessionCallbacks]. Kept apart from the Objective-C plumbing so it is tested on any OS.
 */
object MacNowPlayingMapping {
    /** The values `nowPlayingInfo` is built from; null seconds are left out. */
    data class Info(
        val title: String,
        val durationSec: Double?,
        val elapsedSec: Double,
        /** 0 while paused, else the playback speed: the center moves the elapsed time on by itself. */
        val rate: Double,
        val defaultRate: Double,
    )

    /** What [now] says, as the center wants it. */
    fun info(now: NowPlaying): Info = Info(
        title = now.title,
        durationSec = now.durationMs.takeIf { it > 0 }?.let { it / 1000.0 },
        elapsedSec = (now.positionMs.coerceAtLeast(0) / 1000.0).let { sec ->
            if (now.durationMs > 0) sec.coerceAtMost(now.durationMs / 1000.0) else sec
        },
        rate = if (now.playing) rateOf(now) else 0.0,
        defaultRate = rateOf(now),
    )

    private fun rateOf(now: NowPlaying): Double = now.rate.toDouble().takeIf { it.isFinite() && it > 0.0 } ?: 1.0

    /** `MPNowPlayingPlaybackState`: 1 playing, 2 paused, 3 stopped. */
    fun playbackState(now: NowPlaying?): Int = when {
        now == null -> STATE_STOPPED
        now.playing -> STATE_PLAYING
        else -> STATE_PAUSED
    }

    const val STATE_PLAYING = 1
    const val STATE_PAUSED = 2
    const val STATE_STOPPED = 3

    /** The remote commands TMPlayer enables, named as `MPRemoteCommandCenter` calls its properties. */
    enum class Command(val property: String) {
        Play("playCommand"),
        Pause("pauseCommand"),
        TogglePlayPause("togglePlayPauseCommand"),
        Stop("stopCommand"),
        Next("nextTrackCommand"),
        Previous("previousTrackCommand"),
        ChangePosition("changePlaybackPositionCommand"),
    }

    /** Sends a command to [callbacks]; [positionSec] is the scrubber's position for [Command.ChangePosition]. */
    fun dispatch(command: Command, callbacks: MediaSessionCallbacks, positionSec: Double = 0.0) {
        when (command) {
            Command.Play -> callbacks.onPlay()
            Command.Pause -> callbacks.onPause()
            Command.TogglePlayPause -> callbacks.onPlayPause()
            Command.Stop -> callbacks.onStop()
            Command.Next -> callbacks.onNext()
            Command.Previous -> callbacks.onPrevious()
            Command.ChangePosition -> if (positionSec.isFinite() && positionSec >= 0) callbacks.onSeekTo((positionSec * 1000).toLong())
        }
    }

    /** Whether the next and previous commands are on, from what the player can do now. */
    fun enabled(command: Command, now: NowPlaying?): Boolean = when (command) {
        Command.Next -> now?.canGoNext == true
        Command.Previous -> now?.canGoPrevious == true
        Command.ChangePosition -> now != null && now.durationMs > 0
        else -> now != null
    }
}

/** What the session needs of macOS, so a fake can stand in for it in a test. */
internal interface MacNowPlayingBinding {
    /** Puts [info] on the center (or removes the entry when null) with the [state] `MPNowPlayingPlaybackState`. */
    fun show(info: MacNowPlayingMapping.Info?, state: Int)

    /** Turns a remote command on or off. */
    fun enable(command: MacNowPlayingMapping.Command, on: Boolean)

    fun close()
}

/**
 * The macOS now playing entry: one per process, over [binding]. The commands arrive from
 * [binding] on macOS's own thread, which is what [MediaSessionCallbacks] expects.
 */
internal class MacNowPlayingSession(
    private val binding: MacNowPlayingBinding,
    private val callbacks: MediaSessionCallbacks,
    private val clock: () -> Long = System::nanoTime,
) : MediaSession {

    private val lock = Any()
    private var now: NowPlaying? = null
    private var track = 0L
    private var shown: Triple<MacNowPlayingMapping.Info, Int, Boolean>? = null
    private val enabledNow = HashMap<MacNowPlayingMapping.Command, Boolean>()

    @Volatile
    private var released = false

    override fun update(
        title: String,
        durationMs: Long,
        positionMs: Long,
        playing: Boolean,
        artUrl: String?,
        canGoNext: Boolean,
        canGoPrevious: Boolean,
        rate: Float,
    ) {
        if (released) return
        synchronized(lock) {
            val before = now
            if (before == null || before.title != title) track++
            val after = NowPlaying(track, title, durationMs.coerceAtLeast(0), positionMs.coerceAtLeast(0), playing, artUrl, canGoNext, canGoPrevious, clock(), rate)
            now = after
            val info = MacNowPlayingMapping.info(after)
            val state = MacNowPlayingMapping.playbackState(after)
            // The center moves the elapsed time on by itself at the rate: a repeated tick says nothing new,
            // a new video, play or pause, a new speed, a new length and a seek do.
            val send = before == null || before.trackNumber != after.trackNumber || before.playing != playing ||
                before.rate != rate || before.durationMs != after.durationMs || before.seekedTo(after)
            if (send) binding.show(info, state)
            for (command in MacNowPlayingMapping.Command.entries) {
                val on = MacNowPlayingMapping.enabled(command, after)
                if (enabledNow[command] != on) {
                    enabledNow[command] = on
                    binding.enable(command, on)
                }
            }
        }
    }

    override fun clear() {
        if (released) return
        synchronized(lock) {
            now = null
            enabledNow.clear()
            binding.show(null, MacNowPlayingMapping.STATE_STOPPED)
            for (command in MacNowPlayingMapping.Command.entries) binding.enable(command, false)
        }
    }

    override fun release() {
        if (released) return
        clear()
        released = true
        binding.close()
    }

    companion object {
        /** Binds to macOS's Now Playing; throws when the frameworks cannot be loaded (the caller falls back). */
        fun start(callbacks: MediaSessionCallbacks): MacNowPlayingSession {
            val binding = MacNowPlayingJna(callbacks)
            return MacNowPlayingSession(binding, callbacks)
        }
    }
}

/**
 * `MPNowPlayingInfoCenter` and `MPRemoteCommandCenter` through the Objective-C runtime with JNA
 * (no native code of our own). Everything runs on one thread of its own, inside an autorelease pool
 * per call. Written against Apple's documentation and not run on a Mac from the Linux machine it
 * was made on: any failure ends the session quietly ([failed]), and the player goes without it.
 */
internal class MacNowPlayingJna(private val callbacks: MediaSessionCallbacks) : MacNowPlayingBinding {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "tmplayer-nowplaying").apply { isDaemon = true } }

    @Volatile
    private var failed = false

    private val objc = NativeLibrary.getInstance("objc")
    private val send: Function = objc.getFunction("objc_msgSend")
    private val mediaPlayer: NativeLibrary = NativeLibrary.getInstance("/System/Library/Frameworks/MediaPlayer.framework/MediaPlayer")
    private val system: NativeLibrary = NativeLibrary.getInstance("System")

    private lateinit var center: Pointer
    private lateinit var commands: Pointer

    // The blocks the commands call, kept for the life of the process: MediaPlayer holds them.
    private val blocks = ArrayList<Any>()

    init {
        // Loaded here so a Mac without the framework fails the start, not a later call.
        run(wait = true) {
            center = msg(cls("MPNowPlayingInfoCenter"), "defaultCenter")
            commands = msg(cls("MPRemoteCommandCenter"), "sharedCommandCenter")
            for (command in MacNowPlayingMapping.Command.entries) install(command)
        }
        if (failed) throw IllegalStateException("macOS Now Playing could not start")
    }

    override fun show(info: MacNowPlayingMapping.Info?, state: Int) = run {
        if (info == null) {
            msg(center, "setNowPlayingInfo:", Pointer.NULL)
        } else {
            val dict = msg(cls("NSMutableDictionary"), "dictionary")
            put(dict, constant("MPMediaItemPropertyTitle"), string(info.title))
            info.durationSec?.let { put(dict, constant("MPMediaItemPropertyPlaybackDuration"), number(it)) }
            put(dict, constant("MPNowPlayingInfoPropertyElapsedPlaybackTime"), number(info.elapsedSec))
            put(dict, constant("MPNowPlayingInfoPropertyPlaybackRate"), number(info.rate))
            put(dict, constant("MPNowPlayingInfoPropertyDefaultPlaybackRate"), number(info.defaultRate))
            msg(center, "setNowPlayingInfo:", dict)
        }
        msg(center, "setPlaybackState:", state.toLong())
    }

    override fun enable(command: MacNowPlayingMapping.Command, on: Boolean) = run {
        msg(msg(commands, command.property), "setEnabled:", if (on) 1 else 0)
    }

    override fun close() {
        run {
            for (command in MacNowPlayingMapping.Command.entries) msg(msg(commands, command.property), "setEnabled:", 0)
            msg(center, "setNowPlayingInfo:", Pointer.NULL)
        }
        worker.shutdown()
    }

    /** Runs [block] on the worker inside an autorelease pool; a failure ends the binding, never the app. */
    private fun run(wait: Boolean = false, block: () -> Unit) {
        if (failed) return
        val task = Runnable {
            if (failed) return@Runnable
            val pool = msg(msg(cls("NSAutoreleasePool"), "alloc"), "init")
            try {
                block()
            } catch (t: Throwable) {
                failed = true
                Logger.w(TAG, "Now Playing stopped: ${t.message}")
            } finally {
                runCatching { msg(pool, "drain") }
            }
        }
        runCatching { if (wait) worker.submit(task).get() else worker.execute(task) }
    }

    // One command: its block calls the callbacks on macOS's thread.
    private fun install(command: MacNowPlayingMapping.Command) {
        val invoke = object : BlockInvoke {
            override fun invoke(block: Pointer?, event: Pointer?): Long {
                try {
                    val position = if (command == MacNowPlayingMapping.Command.ChangePosition && event != null) {
                        send.invokeDouble(arrayOf<Any?>(event, sel("positionTime")))
                    } else {
                        0.0
                    }
                    MacNowPlayingMapping.dispatch(command, callbacks, position)
                } catch (t: Throwable) {
                    Logger.w(TAG, "command ${command.name}: ${t.message}")
                }
                // MPRemoteCommandHandlerStatusSuccess
                return 0L
            }
        }
        blocks.add(invoke)
        val block = globalBlock(invoke)
        blocks.add(block)
        msg(msg(commands, command.property), "addTargetWithHandler:", block)
    }

    /** `void *(^)(id)` as a global block: isa, flags (global), reserved, the function, and a descriptor. */
    private fun globalBlock(invoke: BlockInvoke): Memory {
        val descriptor = Memory(16).apply {
            setLong(0, 0L)
            setLong(8, BLOCK_SIZE)
        }
        val literal = Memory(BLOCK_SIZE)
        literal.setPointer(0, system.getGlobalVariableAddress("_NSConcreteGlobalBlock"))
        literal.setInt(8, BLOCK_IS_GLOBAL)
        literal.setInt(12, 0)
        literal.setPointer(16, CallbackReference.getFunctionPointer(invoke))
        literal.setPointer(24, descriptor)
        blocks.add(descriptor)
        return literal
    }

    interface BlockInvoke : Callback {
        fun invoke(block: Pointer?, event: Pointer?): Long
    }

    // The Objective-C runtime, as plain calls.

    private fun cls(name: String): Pointer = objc.getFunction("objc_getClass")
        .invokePointer(arrayOf<Any?>(name)) ?: throw IllegalStateException("no class $name")

    private fun sel(name: String): Pointer = objc.getFunction("sel_registerName")
        .invokePointer(arrayOf<Any?>(name)) ?: throw IllegalStateException("no selector $name")

    /** `[receiver selector:args]` returning an object. */
    private fun msg(receiver: Pointer, selector: String, vararg args: Any?): Pointer {
        val all = arrayOf<Any?>(receiver, sel(selector), *args)
        return send.invokePointer(all) ?: Pointer.NULL
    }

    private fun put(dict: Pointer, key: Pointer, value: Pointer) {
        msg(dict, "setObject:forKey:", value, key)
    }

    private fun string(text: String): Pointer = msg(cls("NSString"), "stringWithUTF8String:", text)

    private fun number(value: Double): Pointer = msg(cls("NSNumber"), "numberWithDouble:", value)

    /** An exported `NSString *` constant of MediaPlayer (the key's name is the symbol's). */
    private fun constant(symbol: String): Pointer =
        mediaPlayer.getGlobalVariableAddress(symbol).getPointer(0) ?: throw IllegalStateException("no constant $symbol")

    companion object {
        private const val TAG = "MacNowPlaying"
        private const val BLOCK_IS_GLOBAL = 1 shl 28
        private const val BLOCK_SIZE = 32L
    }
}
