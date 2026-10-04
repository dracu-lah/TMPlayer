package com.tmplayer.desktop.os

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.tmplayer.platform.Logger
import java.awt.Frame
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Windows System Media Transport Controls: names, numbers and the mapping from what the
 * player says to what SMTC is told. Kept apart from the COM plumbing so it can be tested on any OS.
 *
 * Every GUID, vtable slot and enum value here is taken from the Windows SDK's `windows.media.idl`
 * (`windows.media.h`) and `systemmediatransportcontrolsinterop.h`, cross checked against Wine's
 * copies of the same IDL. The TypedEventHandler IID is derived from the other three by the WinRT
 * parameterized interface rule, and a test holds [BUTTON_HANDLER_IID] to that derivation.
 */
object SmtcMapping {

    /** `ISystemMediaTransportControlsInterop`, the Win32 entry: `GetForWindow(HWND, REFIID, void**)`. */
    const val INTEROP_IID = "ddb0472d-c911-4a1f-86d9-dc3d71a95f5a"

    /** `Windows.Media.ISystemMediaTransportControls`, the default interface of the runtime class. */
    const val SMTC_IID = "99fa3ff4-1742-42a6-902e-087d41f965ec"

    /** `Windows.Media.ISystemMediaTransportControlsButtonPressedEventArgs`. */
    const val BUTTON_ARGS_IID = "b7f47116-a56f-4dc8-9e11-92031f4a87c2"

    /** `Windows.Media.ISystemMediaTransportControlsDisplayUpdater`; reached through a getter, never asked for. */
    const val DISPLAY_UPDATER_IID = "8abbc53e-fa55-4ecf-ad8e-c984e5dd1550"

    /** `Windows.Media.IVideoDisplayProperties`; reached through a getter, never asked for. */
    const val VIDEO_PROPERTIES_IID = "5609fdb1-5d2d-4872-8170-45dee5bc2f5c"

    /** The open generic `Windows.Foundation.TypedEventHandler<TSender, TResult>`. */
    const val TYPED_EVENT_HANDLER_PIID = "9de1c534-6ae1-11e0-84e1-18a905bcc53f"

    /**
     * `TypedEventHandler<SystemMediaTransportControls, SystemMediaTransportControlsButtonPressedEventArgs>`,
     * the interface the ButtonPressed handler implements.
     */
    const val BUTTON_HANDLER_IID = "0557e996-7b23-5bae-aa81-ea0d671143a4"

    /** `IUnknown`. */
    const val IUNKNOWN_IID = "00000000-0000-0000-c000-000000000046"

    /** `IAgileObject`: the handler may be called from any thread, which it can. */
    const val IAGILE_OBJECT_IID = "94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90"

    const val RUNTIME_CLASS = "Windows.Media.SystemMediaTransportControls"

    /** The WinRT signature the button handler's IID is derived from. */
    val BUTTON_HANDLER_SIGNATURE: String =
        "pinterface({$TYPED_EVENT_HANDLER_PIID};" +
            "rc($RUNTIME_CLASS;{$SMTC_IID});" +
            "rc(Windows.Media.SystemMediaTransportControlsButtonPressedEventArgs;{$BUTTON_ARGS_IID}))"

    /** WinRT's namespace for parameterized interface IIDs. */
    private const val PINTERFACE_NAMESPACE = "11f47ad5-7b73-42c0-abae-878b1e16adee"

    // Windows.Media.MediaPlaybackStatus
    const val STATUS_CLOSED = 0
    const val STATUS_CHANGING = 1
    const val STATUS_STOPPED = 2
    const val STATUS_PLAYING = 3
    const val STATUS_PAUSED = 4

    // Windows.Media.MediaPlaybackType
    const val TYPE_VIDEO = 2

    // Windows.Media.SystemMediaTransportControlsButton
    const val BUTTON_PLAY = 0
    const val BUTTON_PAUSE = 1
    const val BUTTON_STOP = 2
    const val BUTTON_RECORD = 3
    const val BUTTON_FAST_FORWARD = 4
    const val BUTTON_REWIND = 5
    const val BUTTON_NEXT = 6
    const val BUTTON_PREVIOUS = 7

    /**
     * A GUID's text form as the 16 bytes a `GUID` struct holds in memory: Data1 (4 bytes), Data2
     * and Data3 (2 each) little endian, then Data4's 8 bytes as written. Braces are allowed.
     */
    fun guidBytes(text: String): ByteArray {
        val s = text.trim().removePrefix("{").removeSuffix("}")
        val parts = s.split('-')
        require(parts.size == 5 && parts.map { it.length } == listOf(8, 4, 4, 4, 12)) { "not a GUID: $text" }
        require(s.replace("-", "").all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "not a GUID: $text" }
        val hex = { str: String -> ByteArray(str.length / 2) { str.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }
        return hex(parts[0]).reversedArray() + hex(parts[1]).reversedArray() + hex(parts[2]).reversedArray() +
            hex(parts[3]) + hex(parts[4])
    }

    /** The inverse of [guidBytes], lower case, no braces. */
    fun guidText(bytes: ByteArray): String {
        require(bytes.size == 16) { "a GUID is 16 bytes, not ${bytes.size}" }
        val hex = { from: Int, to: Int, reverse: Boolean ->
            val slice = bytes.copyOfRange(from, to).let { if (reverse) it.reversedArray() else it }
            slice.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
        return "${hex(0, 4, true)}-${hex(4, 6, true)}-${hex(6, 8, true)}-${hex(8, 10, false)}-${hex(10, 16, false)}"
    }

    /**
     * The IID WinRT gives a parameterized interface: a version 5 UUID (SHA-1) of [signature] in
     * WinRT's namespace, which is how `TypedEventHandler<A, B>` gets its IID.
     */
    fun pinterfaceIid(signature: String): String {
        val ns = guidText(guidBytes(PINTERFACE_NAMESPACE)).replace("-", "")
        val nsBigEndian = ByteArray(16) { ns.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val hash = MessageDigest.getInstance("SHA-1").digest(nsBigEndian + signature.toByteArray(Charsets.UTF_8))
        val b = hash.copyOf(16)
        b[6] = ((b[6].toInt() and 0x0f) or 0x50).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        val h = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }

    /** `MediaPlaybackStatus` for a snapshot; nothing playing is Closed, which hides the panel's controls. */
    fun playbackStatus(now: SmtcState?): Int = when {
        now == null -> STATUS_CLOSED
        now.playing -> STATUS_PLAYING
        else -> STATUS_PAUSED
    }

    /**
     * Sends a pressed button to [callbacks]. Play and Pause arrive separately (the media key's
     * toggle is turned into one of them by Windows from the PlaybackStatus it was last told).
     * Returns false for a button TMPlayer never enables.
     */
    fun dispatch(button: Int, callbacks: MediaSessionCallbacks): Boolean {
        when (button) {
            BUTTON_PLAY -> callbacks.onPlay()
            BUTTON_PAUSE -> callbacks.onPause()
            BUTTON_STOP -> callbacks.onStop()
            BUTTON_NEXT -> callbacks.onNext()
            BUTTON_PREVIOUS -> callbacks.onPrevious()
            else -> return false
        }
        return true
    }
}

/** What SMTC is showing, so a repeated update with the same values sends nothing. */
data class SmtcState(
    val title: String,
    val playing: Boolean,
    val canGoNext: Boolean,
    val canGoPrevious: Boolean,
)

/** An HRESULT that said failure. */
internal class SmtcException(what: String, hr: Int) :
    RuntimeException("$what failed: 0x%08x".format(hr))

/**
 * The COM side: one `SystemMediaTransportControls` for the app's window, reached through
 * `ISystemMediaTransportControlsInterop.GetForWindow`, with a ButtonPressed handler written by hand
 * (an object whose first field points at a four entry vtable of JNA callbacks).
 *
 * All calls run on one thread of its own, initialized for the multithreaded apartment, so COM
 * never runs on the UI thread and never sees two callers at once. Windows hands SMTC one object
 * per window, so there is one of these per process; sessions come and go over it.
 */
internal class SmtcBinding private constructor(private val worker: ExecutorService) {

    private lateinit var controls: Pointer
    private lateinit var updater: Pointer
    private var token = 0L

    /** Who the buttons go to now; null between players. */
    @Volatile
    var listener: MediaSessionCallbacks? = null

    @Volatile
    private var broken = false

    /** False once a COM call has failed and the binding has turned itself off. */
    val healthy: Boolean get() = !broken

    // The handler object and everything it points at stays reachable for the life of the process:
    // SMTC may hold a reference past any session, and a collected callback would crash the app.
    private val handler = ButtonHandler()

    /**
     * Runs [block] on the COM thread. A failure logs once and turns the binding off for good,
     * after which every call is a no op: the player carries on without the panel.
     */
    fun post(block: SmtcBinding.() -> Unit) {
        if (broken) return
        runCatching {
            worker.execute {
                if (broken) return@execute
                try {
                    block()
                } catch (t: Throwable) {
                    broken = true
                    Logger.w(TAG, "SMTC stopped: ${t.message}")
                }
            }
        }
    }

    /** What the panel shows now; touched on the COM thread only. */
    private var shown: SmtcState? = null

    /** Puts [state] on the panel, or takes the panel away when [state] is null. COM thread only. */
    fun render(state: SmtcState?) {
        val before = shown
        if (state == before) return
        // Recorded first: should a call below fail, the binding is off for good anyway.
        shown = state
        if (state == null) {
            check(controls.com(PUT_PLAYBACK_STATUS, SmtcMapping.STATUS_CLOSED), "put_PlaybackStatus")
            check(controls.com(PUT_IS_ENABLED, FALSE), "put_IsEnabled")
            return
        }
        if (before == null) {
            check(controls.com(PUT_IS_ENABLED, TRUE), "put_IsEnabled")
            check(controls.com(PUT_IS_PLAY_ENABLED, TRUE), "put_IsPlayEnabled")
            check(controls.com(PUT_IS_PAUSE_ENABLED, TRUE), "put_IsPauseEnabled")
            check(controls.com(PUT_IS_STOP_ENABLED, TRUE), "put_IsStopEnabled")
        }
        if (before == null || before.canGoNext != state.canGoNext) {
            check(controls.com(PUT_IS_NEXT_ENABLED, flag(state.canGoNext)), "put_IsNextEnabled")
        }
        if (before == null || before.canGoPrevious != state.canGoPrevious) {
            check(controls.com(PUT_IS_PREVIOUS_ENABLED, flag(state.canGoPrevious)), "put_IsPreviousEnabled")
        }
        if (before == null || before.playing != state.playing) {
            check(controls.com(PUT_PLAYBACK_STATUS, SmtcMapping.playbackStatus(state)), "put_PlaybackStatus")
        }
        if (before == null || before.title != state.title) setTitle(state.title)
    }

    private fun setTitle(title: String) {
        check(updater.com(UPDATER_PUT_TYPE, SmtcMapping.TYPE_VIDEO), "put_Type")
        val out = PointerByReference()
        check(updater.com(UPDATER_GET_VIDEO_PROPERTIES, out), "get_VideoProperties")
        val video = out.value ?: throw IllegalStateException("get_VideoProperties returned no object")
        try {
            withHString(title) { check(video.com(VIDEO_PUT_TITLE, it), "put_Title") }
        } finally {
            video.release()
        }
        check(updater.com(UPDATER_UPDATE), "Update")
    }

    private fun init(hwnd: Pointer) {
        val hr = Combase.INSTANCE.RoInitialize(RO_INIT_MULTITHREADED)
        // S_OK, S_FALSE (already), or RPC_E_CHANGED_MODE (initialized differently, still usable).
        if (hr < 0 && hr != RPC_E_CHANGED_MODE) throw SmtcException("RoInitialize", hr)
        val factory = PointerByReference()
        withHString(SmtcMapping.RUNTIME_CLASS) {
            check(Combase.INSTANCE.RoGetActivationFactory(it, guid(SmtcMapping.INTEROP_IID), factory), "RoGetActivationFactory")
        }
        val interop = factory.value ?: throw IllegalStateException("RoGetActivationFactory returned no object")
        val out = PointerByReference()
        try {
            check(interop.com(INTEROP_GET_FOR_WINDOW, hwnd, guid(SmtcMapping.SMTC_IID), out), "GetForWindow")
        } finally {
            interop.release()
        }
        controls = out.value ?: throw IllegalStateException("GetForWindow returned no object")
        val upd = PointerByReference()
        check(controls.com(GET_DISPLAY_UPDATER, upd), "get_DisplayUpdater")
        updater = upd.value ?: throw IllegalStateException("get_DisplayUpdater returned no object")
        val tok = LongByReference()
        check(controls.com(ADD_BUTTON_PRESSED, handler.self, tok), "add_ButtonPressed")
        token = tok.value
        // Off until a video plays, so the panel never shows TMPlayer with nothing in it.
        check(controls.com(PUT_IS_ENABLED, FALSE), "put_IsEnabled")
    }

    /**
     * `TypedEventHandler<SystemMediaTransportControls, SystemMediaTransportControlsButtonPressedEventArgs>`
     * by hand: QueryInterface, AddRef, Release, Invoke. Never freed (see [handler]), so the
     * reference count is kept only because COM expects the calls to answer.
     */
    private inner class ButtonHandler {
        private val refs = AtomicInteger(1)
        private val handlerIid = guidBytes(SmtcMapping.BUTTON_HANDLER_IID)
        private val unknownIid = guidBytes(SmtcMapping.IUNKNOWN_IID)
        private val agileIid = guidBytes(SmtcMapping.IAGILE_OBJECT_IID)

        private val queryInterface = object : QueryInterfaceFn {
            override fun invoke(self: Pointer?, riid: Pointer?, ppv: Pointer?): Int {
                if (ppv == null) return E_POINTER
                val iid = riid?.getByteArray(0, 16)
                if (iid != null && (iid.contentEquals(handlerIid) || iid.contentEquals(unknownIid) || iid.contentEquals(agileIid))) {
                    ppv.setPointer(0, self)
                    refs.incrementAndGet()
                    return S_OK
                }
                ppv.setPointer(0, null)
                return E_NOINTERFACE
            }
        }
        private val addRef = object : RefFn {
            override fun invoke(self: Pointer?): Int = refs.incrementAndGet()
        }
        private val release = object : RefFn {
            override fun invoke(self: Pointer?): Int = refs.decrementAndGet().coerceAtLeast(0)
        }
        private val invoke = object : InvokeFn {
            override fun invoke(self: Pointer?, sender: Pointer?, args: Pointer?): Int {
                try {
                    val target = listener ?: return S_OK
                    val button = IntByReference()
                    if (args == null || args.com(ARGS_GET_BUTTON, button) < 0) return S_OK
                    SmtcMapping.dispatch(button.value, target)
                } catch (t: Throwable) {
                    // Nothing may escape into Windows' thread.
                    Logger.w(TAG, "button handler: ${t.message}")
                }
                return S_OK
            }
        }

        private val vtable = Memory(4L * Native.POINTER_SIZE).apply {
            listOf(queryInterface, addRef, release, invoke).forEachIndexed { i, fn ->
                setPointer(i.toLong() * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(fn))
            }
        }

        /** The object COM is handed: one field, the vtable pointer. */
        val self: Pointer = Memory(Native.POINTER_SIZE.toLong()).apply { setPointer(0, vtable) }
    }

    interface QueryInterfaceFn : StdCallLibrary.StdCallCallback {
        fun invoke(self: Pointer?, riid: Pointer?, ppv: Pointer?): Int
    }

    interface RefFn : StdCallLibrary.StdCallCallback {
        fun invoke(self: Pointer?): Int
    }

    interface InvokeFn : StdCallLibrary.StdCallCallback {
        fun invoke(self: Pointer?, sender: Pointer?, args: Pointer?): Int
    }

    @Suppress("FunctionName")
    interface Combase : StdCallLibrary {
        fun RoInitialize(initType: Int): Int
        fun RoGetActivationFactory(activatableClassId: Pointer, iid: Pointer, factory: PointerByReference): Int
        fun WindowsCreateString(source: WString, length: Int, string: PointerByReference): Int
        fun WindowsDeleteString(string: Pointer?): Int

        companion object {
            val INSTANCE: Combase by lazy { Native.load("combase", Combase::class.java) }
        }
    }

    companion object {
        private const val TAG = "Smtc"

        private const val S_OK = 0
        private const val E_NOINTERFACE = 0x80004002.toInt()
        private const val E_POINTER = 0x80004003.toInt()
        private const val RPC_E_CHANGED_MODE = 0x80010106.toInt()
        private const val RO_INIT_MULTITHREADED = 1
        private const val TRUE: Byte = 1
        private const val FALSE: Byte = 0

        // Vtable slots. IUnknown is 0 to 2 (QueryInterface, AddRef, Release); IInspectable adds
        // 3 to 5 (GetIids, GetRuntimeClassName, GetTrustLevel); each interface's own methods
        // follow in IDL order, a property's getter before its setter.

        /** ISystemMediaTransportControlsInterop::GetForWindow. */
        private const val INTEROP_GET_FOR_WINDOW = 6

        // ISystemMediaTransportControls
        private const val PUT_PLAYBACK_STATUS = 7
        private const val GET_DISPLAY_UPDATER = 8
        private const val PUT_IS_ENABLED = 11
        private const val PUT_IS_PLAY_ENABLED = 13
        private const val PUT_IS_STOP_ENABLED = 15
        private const val PUT_IS_PAUSE_ENABLED = 17
        private const val PUT_IS_PREVIOUS_ENABLED = 25
        private const val PUT_IS_NEXT_ENABLED = 27
        private const val ADD_BUTTON_PRESSED = 32

        // ISystemMediaTransportControlsDisplayUpdater
        private const val UPDATER_PUT_TYPE = 7
        private const val UPDATER_GET_VIDEO_PROPERTIES = 13
        private const val UPDATER_UPDATE = 17

        /** IVideoDisplayProperties::put_Title. */
        private const val VIDEO_PUT_TITLE = 7

        /** ISystemMediaTransportControlsButtonPressedEventArgs::get_Button. */
        private const val ARGS_GET_BUTTON = 6

        private const val IUNKNOWN_RELEASE = 2

        @Volatile
        private var shared: SmtcBinding? = null

        @Volatile
        private var failed = false

        /**
         * The process's binding for [window], made on first use. Null (logged once) when SMTC
         * cannot be reached: not Windows, no native window yet, an old Windows, or any HRESULT
         * that says no.
         */
        @Synchronized
        fun forWindow(window: Frame): SmtcBinding? {
            if (!OsInfo.isWindows || failed) return null
            shared?.let { return it }
            val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "tmplayer-smtc").apply { isDaemon = true } }
            val made = runCatching {
                val hwnd = NativeFullscreen.windowsHandle(window)?.pointer
                    ?: throw IllegalStateException("the window has no native handle yet")
                val binding = SmtcBinding(worker)
                worker.submit { binding.init(hwnd) }.get(INIT_TIMEOUT_S, TimeUnit.SECONDS)
                binding
            }
            return made.fold(
                onSuccess = { b ->
                    Logger.i(TAG, "SMTC ready")
                    shared = b
                    b
                },
                onFailure = { t ->
                    failed = true
                    worker.shutdownNow()
                    Logger.w(TAG, "SMTC unavailable: ${(t.cause ?: t).message}")
                    null
                },
            )
        }

        private const val INIT_TIMEOUT_S = 5L

        private fun flag(on: Boolean): Byte = if (on) TRUE else FALSE

        private fun check(hr: Int, what: String) {
            if (hr < 0) throw SmtcException(what, hr)
        }

        private fun guidBytes(text: String) = SmtcMapping.guidBytes(text)

        private fun guid(text: String): Pointer = Memory(16).apply { write(0, guidBytes(text), 0, 16) }

        private inline fun <T> withHString(text: String, block: (Pointer) -> T): T {
            val out = PointerByReference()
            check(Combase.INSTANCE.WindowsCreateString(WString(text), text.length, out), "WindowsCreateString")
            // An empty string is the null HSTRING, which every API takes as "".
            val h = out.value ?: Pointer.NULL
            try {
                return block(h)
            } finally {
                Combase.INSTANCE.WindowsDeleteString(out.value)
            }
        }

        /** Calls the COM method in vtable slot [index] of the object [this] points at. */
        private fun Pointer.com(index: Int, vararg args: Any?): Int {
            val vtable = getPointer(0)
            val fn = Function.getFunction(vtable.getPointer(index.toLong() * Native.POINTER_SIZE), Function.ALT_CONVENTION)
            return fn.invokeInt(arrayOf<Any?>(this, *args))
        }

        private fun Pointer.release() {
            runCatching { com(IUNKNOWN_RELEASE) }
        }
    }
}

/**
 * The Windows now playing entry: the media flyout by the volume control, the lock screen, and
 * the keyboard's media keys, through SMTC. Only play, pause, stop, next and previous; SMTC's
 * timeline (seeking from the flyout) is not wired.
 */
internal class SmtcMediaSession(
    private val binding: SmtcBinding,
    private val callbacks: MediaSessionCallbacks,
) : MediaSession {

    /** The last state this session asked for, so a timer tick with nothing new sends nothing. */
    @Volatile
    private var asked: SmtcState? = null

    @Volatile
    private var released = false

    /** False once SMTC has failed and this session (like every other) went quiet. */
    val healthy: Boolean get() = binding.healthy

    override fun update(
        title: String,
        durationMs: Long,
        positionMs: Long,
        playing: Boolean,
        artUrl: String?,
        canGoNext: Boolean,
        canGoPrevious: Boolean,
    ) {
        if (released) return
        val next = SmtcState(title, playing, canGoNext, canGoPrevious)
        if (next == asked) return
        asked = next
        binding.post {
            // The newest player takes the panel; one closing a moment later cannot clear it.
            listener = callbacks
            render(next)
        }
    }

    override fun clear() {
        if (released) return
        asked = null
        binding.post { if (listener === callbacks) render(null) }
    }

    override fun release() {
        if (released) return
        released = true
        asked = null
        binding.post {
            if (listener === callbacks) {
                listener = null
                render(null)
            }
        }
    }

    companion object {
        /** A session over the process's SMTC binding, or null when SMTC cannot be had. */
        fun start(window: Frame, callbacks: MediaSessionCallbacks): SmtcMediaSession? =
            SmtcBinding.forWindow(window)?.let { SmtcMediaSession(it, callbacks) }
    }
}
