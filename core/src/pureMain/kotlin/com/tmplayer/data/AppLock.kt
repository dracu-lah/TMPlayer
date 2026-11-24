package com.tmplayer.data

import com.tmplayer.platform.Background
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.tmplayer.platform.monotonicMillis
import com.tmplayer.platform.pbkdf2Sha256
import com.tmplayer.platform.secureRandomBytes
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The app lock's PIN (CP57): four digits, kept only as a salted PBKDF2 hash.
 *
 * Four digits are ten thousand guesses, so no hash makes a stolen settings file safe on its own;
 * what the hash buys is that the PIN itself is never written down, and somebody reading the file
 * over a shoulder learns nothing they could type. The real guard against guessing is [PinBackoff],
 * which makes ten thousand tries at the keypad take weeks.
 *
 * Stored as `v1:<iterations>:<salt>:<hash>`, Base64 for the two byte strings, so a later version
 * can raise the iteration count and still read a PIN set under this one.
 */
@OptIn(ExperimentalEncodingApi::class)
object PinHash {

    /** How many digits a PIN has. Fixed, so the pad can tell when one is complete. */
    const val LENGTH = 4

    /**
     * Enough to take a visible moment on a desktop and well under a frame's budget of patience on
     * a stick: the pad hashes once per complete PIN, never per key.
     */
    private const val ITERATIONS = 20_000
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256
    private const val VERSION = "v1"

    /** Exactly [LENGTH] ASCII digits. */
    fun isValid(pin: String): Boolean = pin.length == LENGTH && pin.all { it in '0'..'9' }

    /** The line to store for [pin], with a fresh random salt unless one is handed in (tests). */
    fun hash(pin: String, salt: ByteArray = newSalt()): String {
        require(isValid(pin)) { "A PIN is $LENGTH digits" }
        return listOf(VERSION, ITERATIONS.toString(), Base64.encode(salt), Base64.encode(derive(pin, salt, ITERATIONS)))
            .joinToString(":")
    }

    /**
     * Whether [pin] is the one [stored] was made from. A line that cannot be read is never a
     * match, so a damaged setting locks rather than opens; signing out clears it either way.
     * The comparison takes the same time however many bytes agree.
     */
    fun verify(pin: String, stored: String?): Boolean {
        if (stored == null || !isValid(pin)) return false
        val parts = stored.split(':')
        if (parts.size != 4 || parts[0] != VERSION) return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return false
        val salt = runCatching { Base64.decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { Base64.decode(parts[3]) }.getOrNull() ?: return false
        return sameBytes(derive(pin, salt, iterations), expected)
    }

    private fun newSalt(): ByteArray = secureRandomBytes(SALT_BYTES)

    /** PBKDF2 with HMAC-SHA256, as the JDK's PBKDF2WithHmacSHA256 derives it. */
    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray =
        pbkdf2Sha256(pin.encodeToByteArray(), salt, iterations, KEY_BITS / 8)

    /** Compared in constant time, as MessageDigest.isEqual does. */
    private fun sameBytes(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}

/**
 * The clock the PIN's waits are timed on: one that only moves forward, so setting the device's
 * date ahead does not end a wait. Milliseconds from an arbitrary start; the Android app points it
 * at `SystemClock.elapsedRealtime`, which keeps counting through deep sleep.
 */
object PinClock {
    @Volatile
    var source: () -> Long = { monotonicMillis() }

    fun now(): Long = source()
}

/**
 * Wrong guesses and the wait they earn, as stored beside the PIN.
 *
 * [failures] counts wrong PINs since the last right one; [waitUntil] is the [PinClock] time before
 * which the pad takes no PIN at all. Both survive a restart, or closing the app would be the way
 * round the wait: what is stored is the wait left and the [PinClock] time it was written at (see
 * [stored] and [restored]), since that clock starts again from nothing after a reboot.
 */
data class PinAttempts(val failures: Int = 0, val waitUntil: Long = 0L) {

    /** How long is left of the wait at [now], or 0 when a PIN may be tried. */
    fun waitMs(now: Long): Long = (waitUntil - now).coerceAtLeast(0L)

    /** After one more wrong PIN at [now]. */
    fun failed(now: Long): PinAttempts {
        val count = failures + 1
        val wait = PinBackoff.waitAfter(count)
        return PinAttempts(count, if (wait > 0) now + wait else 0L)
    }

    /** How many more wrong PINs are free before the next one earns a wait. */
    val freeLeft: Int get() = (PinBackoff.FREE_TRIES - failures).coerceAtLeast(0)

    companion object {
        /**
         * The attempts from what was stored: [waitLeftMs] as of [stampedAt]. A clock behind the
         * stamp has started again (a reboot, a new desktop session), and then none of the wait
         * counts as served; it is never longer than [PinBackoff.MAX_WAIT_MS] either way.
         */
        fun restored(failures: Int, waitLeftMs: Long, stampedAt: Long, now: Long): PinAttempts {
            val served = if (now >= stampedAt) now - stampedAt else 0L
            val left = (waitLeftMs.coerceIn(0L, PinBackoff.MAX_WAIT_MS) - served).coerceAtLeast(0L)
            return PinAttempts(failures, if (left > 0) now + left else 0L)
        }
    }
}

/**
 * How long a run of wrong PINs makes the pad wait: nothing for the first [FREE_TRIES], which is
 * room for a child's honest attempt or a fumbled remote, then 30 seconds, doubling with every
 * further miss up to a quarter of an hour. Ten thousand guesses at that ceiling is over three
 * months of trying.
 */
object PinBackoff {
    const val FREE_TRIES = 3
    private const val FIRST_WAIT_MS = 30_000L
    const val MAX_WAIT_MS = 15 * 60_000L

    /** The wait earned by the [failures]th wrong PIN in a row. */
    fun waitAfter(failures: Int): Long {
        if (failures < FREE_TRIES) return 0L
        val doublings = (failures - FREE_TRIES).coerceAtMost(10)
        return (FIRST_WAIT_MS shl doublings).coerceAtMost(MAX_WAIT_MS)
    }
}

/** What the pad hears back for a complete PIN. */
sealed interface PinCheck {
    /** The right PIN: the wrong guesses are forgotten. */
    data object Right : PinCheck

    /** A wrong PIN; [attempts] is the new count, with any wait it earned. */
    data class Wrong(val attempts: PinAttempts) : PinCheck

    /** Still inside an earlier wait: the PIN was not even looked at. */
    data class Waiting(val waitMs: Long) : PinCheck

    /** There is no PIN to check against: nothing is locked. */
    data object NoPin : PinCheck
}

/**
 * Whether the app has been unlocked since it was last opened, for the "Ask when the app opens"
 * option. Process wide, so the player (another activity on Android) coming and going does not
 * lock it; the Android app locks it again when its last activity leaves the screen.
 */
object AppLockSession {
    private val state = MutableStateFlow(false)

    val unlocked: StateFlow<Boolean> = state.asStateFlow()

    fun unlock() {
        state.value = true
    }

    fun lock() {
        val was = state.value
        state.value = false
        if (was) for (each in listeners.value) runCatching(each)
    }

    // Pure Kotlin (no JDK here): the list is replaced whole, atomically.
    private val listeners = MutableStateFlow<List<() -> Unit>>(emptyList())

    /**
     * Runs [block] each time an unlocked app is locked again (HX88: the open profile's PIN is
     * asked again with the app's), wherever the lock happens, with or without a screen up.
     */
    fun onLock(block: () -> Unit) {
        listeners.update { it + block }
    }
}

/**
 * Settings' own gate (the app lock's PIN given for this visit to Settings), process wide, so a
 * rotation keeps it and a process that died asks again. What every account action behind the
 * Settings PIN reads ([SignOutGate]), from Settings or from a menu outside it (the TV's bar over
 * Downloads, the phone's account button), so no route trusts a gate that outlived its visit.
 *
 * A visit ends when Settings is left for anything but its own pages (About, Add chats), and when
 * the app leaves the screen ([appLeft]), whatever the lock's options: with "ask when the app opens"
 * off nothing else locks, and a phone left on a table must not keep Settings open.
 */
object SettingsUnlock {
    private val state = MutableStateFlow(false)

    val unlocked: StateFlow<Boolean> = state.asStateFlow()

    fun unlock() {
        state.value = true
    }

    fun lock() {
        state.value = false
    }

    private val leaveListeners = MutableStateFlow<List<() -> Unit>>(emptyList())

    /**
     * The app's last window left the screen (not a rotation): Settings locks, and what was asked or
     * answered for a PIN before it goes ([onAppLeft]).
     */
    fun appLeft() {
        state.value = false
        for (each in leaveListeners.value) runCatching(each)
    }

    /** Runs [block] each time the app leaves the screen; the returned function stops it. */
    fun onAppLeft(block: () -> Unit): () -> Unit {
        leaveListeners.update { it + block }
        return { leaveListeners.update { list -> list - block } }
    }
}

