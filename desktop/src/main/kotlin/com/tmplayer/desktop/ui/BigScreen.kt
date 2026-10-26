package com.tmplayer.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import com.tmplayer.data.UiScale
import com.tmplayer.desktop.DesktopLayout
import androidx.compose.ui.input.key.type
import kotlin.math.roundToInt

/**
 * What the command line asks of this run: `--tv` (the TV layout, in full screen, for a media PC's
 * autostart or a Plasma Bigscreen launcher), `--desktop` (the desktop layout whatever Settings
 * says), `--fullscreen` and `--windowed`. Unknown arguments are left alone: a second launch hands
 * its arguments over (tmplayer: links, `--minimized`).
 */
data class LaunchOptions(
    /** The layout for this run, over the setting until the viewer picks one; null keeps the setting. */
    val layout: DesktopLayout? = null,
    /** Full screen at the start; null leaves it to Settings' "Start in full screen". */
    val fullscreen: Boolean? = null,
) {
    companion object {
        fun parse(args: List<String>): LaunchOptions {
            var layout: DesktopLayout? = null
            var fullscreen: Boolean? = null
            for (raw in args) {
                when (raw.trim().lowercase()) {
                    "--tv", "--big-screen", "--layout=tv" -> {
                        layout = DesktopLayout.Tv
                        if (fullscreen == null) fullscreen = true
                    }
                    "--desktop", "--layout=desktop" -> layout = DesktopLayout.Desktop
                    "--layout=auto" -> layout = DesktopLayout.Auto
                    "--fullscreen", "--full-screen" -> fullscreen = true
                    "--windowed" -> fullscreen = false
                }
            }
            return LaunchOptions(layout, fullscreen)
        }
    }
}

/**
 * Whether the window is in the big-screen mode: the TV's top bar, the larger scale, the arrows
 * moving focus everywhere and the pointer hidden while it rests. One per process, read by the
 * shell, the grids and the player; the window sets [layout] from Settings and [override] from the
 * command line.
 */
object BigScreen {
    /** The Layout setting, as `desktop.properties` holds it. */
    var layout by mutableStateOf(DesktopLayout.Auto)

    /** `--tv` or `--desktop` for this run, until the viewer picks a layout in Settings. */
    var override by mutableStateOf<DesktopLayout?>(null)

    /** The layout in effect: Auto is the desktop's on a computer. */
    val effective: DesktopLayout get() = override ?: layout

    val on: Boolean get() = effective == DesktopLayout.Tv
}

/**
 * The interface scale in the big-screen mode, for a sofa's distance: the window is made to hold
 * [ROOM_WIDTH] by [ROOM_HEIGHT] dp at the Default interface size, the viewer's Interface size
 * multiplying that, but never less than the [MIN_WIDTH] by [MIN_HEIGHT] the desktop's layouts are
 * made for, and never smaller than the desktop's own. A 1920 by 1080 television at 100 per cent is
 * 1.6 at Default and 1.8 (1067 by 600) at Larger and Largest; a 4K one at 100 per cent twice that.
 */
object TvScale {
    const val ROOM_WIDTH = 1200f
    const val ROOM_HEIGHT = 675f
    const val MIN_WIDTH = 960f
    const val MIN_HEIGHT = 600f

    fun factor(widthDp: Float, heightDp: Float, scale: UiScale): Float {
        if (widthDp <= 0f || heightDp <= 0f) return scale.factor
        val room = minOf(widthDp / ROOM_WIDTH, heightDp / ROOM_HEIGHT) * scale.factor
        val most = minOf(widthDp / MIN_WIDTH, heightDp / MIN_HEIGHT)
        // In steps of 0.05, so a window dragged a pixel wider does not lay the whole app out again.
        return ((room.coerceAtMost(most).coerceAtLeast(1f)) * 20f).roundToInt() / 20f
    }
}

/**
 * Draws [content] at the viewer's interface size, and in the big-screen mode at [TvScale]'s size
 * for the window it fills. Always the same provider, so a change of layout or of size recomposes
 * in place rather than throwing the tree's state away.
 */
@Composable
fun ProvideInterfaceScale(scale: UiScale, bigScreen: Boolean, content: @Composable () -> Unit) {
    val base = LocalDensity.current
    val factor = if (bigScreen) {
        // Read only in the big-screen mode: a desktop window being resized recomposes nothing here.
        val window = LocalWindowInfo.current.containerSize
        TvScale.factor(window.width / base.density, window.height / base.density, scale)
    } else {
        scale.factor
    }
    val scaled = remember(base.density, base.fontScale, factor) { Density(base.density * factor, base.fontScale) }
    CompositionLocalProvider(LocalDensity provides scaled, content = content)
}

/**
 * The TV bar's items, for the shell to put the remote back on: Back from a page lands on the
 * page's own item, and leaving a menu opened from the picture lands on the picture.
 */
@Stable
class TvBarFocus {
    val picture = FocusRequester()
    val search = FocusRequester()
    val home = FocusRequester()
    val chats = FocusRequester()

    /** Whether the remote is on the bar. */
    var focused by mutableStateOf(false)
        internal set

    /** Whether the bar is on screen (it is not over a chat, which brings its own bar). */
    var shown by mutableStateOf(false)
        internal set

    /**
     * Home's carousel runs up under the bar, its picture with it (the Android TV app's `underBar`):
     * the page then starts at the window's top instead of below the bar, and the bar is drawn over it.
     */
    var overArt by mutableStateOf(false)

    /** Home's rows have scrolled up under the bar, which then takes the page's ground behind it. */
    var scrolled by mutableStateOf(false)

    internal fun requesterFor(place: NavPlace?): FocusRequester = when (place) {
        NavPlace.Home -> home
        NavPlace.Chats -> chats
        NavPlace.Search -> search
        null -> picture
    }
}

/**
 * The keys a remote sends beyond the arrows and Enter, as they reach the window. A Flirc, an MCE
 * receiver, a CEC adapter or LIRC mostly send keyboard keys (Esc or Backspace for Back, the media
 * keys); some send keys the JDK has no name for, which arrive with only the system's own code
 * (Windows' virtual key, X11's key code), read here.
 */
enum class RemoteKey { Back, PlayPause, Play, Pause, Stop, Next, Previous, Rewind, FastForward, Home, Menu }

object RemoteKeys {
    /** Which system a raw code came from: the same number means different keys on each. */
    enum class System { Windows, Linux, Mac }

    /**
     * The remote key for a press the JDK could not name ([VK_UNDEFINED][UNDEFINED]), from its
     * [rawCode]: Windows' virtual key codes, and X11's key codes (the kernel's plus 8), which
     * XWayland passes on unchanged. A Mac's remotes reach the system's media controls, not keys.
     */
    fun classify(keyCode: Int, rawCode: Long, system: System): RemoteKey? {
        if (keyCode != UNDEFINED) return null
        return when (system) {
            System.Windows -> WINDOWS[rawCode]
            System.Linux -> X11[rawCode]
            System.Mac -> null
        }
    }

    const val UNDEFINED = 0

    /** VK_BROWSER_BACK, VK_BROWSER_HOME, VK_MEDIA_*, VK_PLAY and VK_APPS (winuser.h). */
    private val WINDOWS = mapOf(
        0xA6L to RemoteKey.Back,
        0xACL to RemoteKey.Home,
        0xB0L to RemoteKey.Next,
        0xB1L to RemoteKey.Previous,
        0xB2L to RemoteKey.Stop,
        0xB3L to RemoteKey.PlayPause,
        0xFAL to RemoteKey.Play,
        0x5DL to RemoteKey.Menu,
    )

    /** KEY_BACK, KEY_HOMEPAGE, KEY_MENU, KEY_*SONG, KEY_STOPCD, KEY_PLAYPAUSE, KEY_PLAY and the rest, plus 8. */
    private val X11 = mapOf(
        166L to RemoteKey.Back,
        180L to RemoteKey.Home,
        147L to RemoteKey.Menu,
        171L to RemoteKey.Next,
        173L to RemoteKey.Previous,
        174L to RemoteKey.Stop,
        172L to RemoteKey.PlayPause,
        208L to RemoteKey.Play,
        215L to RemoteKey.Play,
        209L to RemoteKey.Pause,
        176L to RemoteKey.Rewind,
        216L to RemoteKey.FastForward,
    )

    /**
     * The raw code in an AWT key event's [paramString] (`rawCode=166`), the one public place the
     * JDK says it; null where it does not.
     */
    fun rawCodeIn(paramString: String): Long? {
        val at = paramString.indexOf("rawCode=")
        if (at < 0) return null
        val digits = paramString.substring(at + 8).takeWhile { it.isDigit() || it == '-' }
        return digits.toLongOrNull()
    }
}

/** How long the pointer may rest in the big-screen mode before it is hidden. */
const val CURSOR_IDLE_MS = 3_000L

/** The [RemoteKey] a key press is, for a key the JDK has no name for; null for every other key. */
fun remoteKeyOf(event: androidx.compose.ui.input.key.KeyEvent): RemoteKey? {
    if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return null
    val native = event.nativeKeyEvent as? java.awt.event.KeyEvent ?: return null
    if (native.keyCode != RemoteKeys.UNDEFINED) return null
    val raw = RemoteKeys.rawCodeIn(native.paramString()) ?: return null
    val system = when {
        com.tmplayer.desktop.os.OsInfo.isWindows -> RemoteKeys.System.Windows
        com.tmplayer.desktop.os.OsInfo.isMac -> RemoteKeys.System.Mac
        else -> RemoteKeys.System.Linux
    }
    return RemoteKeys.classify(native.keyCode, raw, system)
}

/**
 * In the big-screen mode, a list or grid that remembers the item the remote was on, so coming
 * back into it (Down from the bar, the end of a video) lands there rather than on its first item.
 * Each list the page nests needs it: Compose remembers one level of focus per list.
 */
fun Modifier.remembersRemoteItem(): Modifier = if (BigScreen.on) then(Modifier.focusRestorer()) else this
