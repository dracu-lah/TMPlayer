package com.tmplayer.ui.lock

import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmplayer.data.DeviceForm
import com.tmplayer.data.PinAttempts
import com.tmplayer.data.PinClock
import com.tmplayer.data.PinCheck
import com.tmplayer.data.PinHash
import com.tmplayer.data.SettingsStore
import com.tmplayer.i18n.L
import com.tmplayer.ui.components.deviceForm
import com.tmplayer.ui.components.liveMessage
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.focusRing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a [PinPad] is asked for. */
enum class PinPurpose {
    /** The stored PIN, before Settings, signing out or the app itself. Wrong guesses count. */
    Check,

    /** A new PIN, typed twice. Nothing is checked or stored: the caller stores what comes back. */
    Choose,
}

/**
 * The app lock's keypad (CP57), the same on the phone, the television and the desktop, full
 * screen over whatever it guards.
 *
 * Four dots, a line for what went wrong, and a three by four grid of keys: one to nine, then
 * Cancel (when there is somewhere to go back to), nought and delete. Every key is a focus target,
 * so a remote walks the grid with the D-pad and presses OK, and the remote's own number keys and
 * a keyboard's digits, Backspace and Delete work wherever focus is. A PIN is submitted the moment
 * its fourth digit is in; there is no OK key to find.
 *
 * Checking goes through [SettingsStore.checkPin], so the back-off is the store's and survives the
 * pad being closed and opened again: while a wait runs the keys do nothing and the line counts it
 * down. [onDone] gets the PIN that was accepted (checked, or chosen and repeated).
 */
@Composable
fun PinPad(
    settings: SettingsStore,
    purpose: PinPurpose,
    title: String,
    onDone: (pin: String) -> Unit,
    onCancel: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** The line on the only way back from a forgotten PIN. Shown when checking, unless asked not to. */
    showForgot: Boolean = purpose == PinPurpose.Check,
    /** The app's mark and name over the lock: the pad that stands in for the whole app at its opening. */
    brand: Boolean = false,
    /**
     * A fingerprint or face for the app lock's own pad (WP-41), and what an unlock by it does. Only
     * here: the checker overload below (profile PINs) has no way to take one, and nor has any other
     * pad of the app lock's PIN (Settings, signing out, Remove the PIN, the kids lock), which leave it
     * null. Null until the lock and the opt-in have been read, so nothing is offered before then.
     */
    biometric: BiometricUnlock? = null,
    onBiometric: (() -> Unit)? = null,
) {
    val checker = remember(settings) { SettingsPinChecker(settings) }
    PinPadBody(checker, purpose, title, onDone, onCancel, modifier, showForgot, brand, biometric, onBiometric)
}

/**
 * Where a [PinPad] checks a PIN: the app lock's ([SettingsPinChecker]) or a profile's
 * ([ProfilePinChecker], HX88). Each keeps its own count of wrong PINs and its own wait.
 */
interface PinChecker {
    /** The [PinClock] time until which wrong PINs from before the pad opened still make it wait, 0 for none. */
    suspend fun waitUntil(): Long

    suspend fun check(pin: String): PinCheck

    /** How many more wrong PINs are free before a wait, after [attempts]. */
    fun freeLeft(attempts: PinAttempts): Int

    /** The line on the only way back from a forgotten PIN, or null for the app lock's own. */
    val forgot: String? get() = null
}

/** The app lock's PIN in [SettingsStore], with its back-off. */
class SettingsPinChecker(private val settings: SettingsStore) : PinChecker {
    override suspend fun waitUntil(): Long {
        val attempts = runCatching { settings.pinAttemptsNow() }.getOrDefault(PinAttempts())
        return if (attempts.waitMs(PinClock.now()) > 0) attempts.waitUntil else 0L
    }

    override suspend fun check(pin: String): PinCheck = settings.checkPin(pin)

    override fun freeLeft(attempts: PinAttempts): Int = attempts.freeLeft
}

/**
 * [PinPad] over any [checker]; with [PinPurpose.Choose] the checker is never asked and may be
 * null. Everything else is as the app lock's pad.
 */
@Composable
fun PinPad(
    checker: PinChecker?,
    purpose: PinPurpose,
    title: String,
    onDone: (pin: String) -> Unit,
    onCancel: (() -> Unit)?,
    modifier: Modifier = Modifier,
    showForgot: Boolean = purpose == PinPurpose.Check,
    brand: Boolean = false,
) = PinPadBody(checker, purpose, title, onDone, onCancel, modifier, showForgot, brand, biometric = null, onBiometric = null)

/** The pad itself; [biometric] is only ever given by the app lock's own overload above. */
@Composable
private fun PinPadBody(
    checker: PinChecker?,
    purpose: PinPurpose,
    title: String,
    onDone: (pin: String) -> Unit,
    onCancel: (() -> Unit)?,
    modifier: Modifier,
    showForgot: Boolean,
    brand: Boolean,
    biometric: BiometricUnlock?,
    onBiometric: (() -> Unit)?,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    var entered by remember { mutableStateOf("") }
    // Choosing: the first PIN typed, waiting for its repeat.
    var first by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var waitUntil by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(PinClock.now()) }
    val waiting = waitUntil > now
    // The fingerprint (WP-41): only while checking the app lock's PIN, only when both the prompt and the
    // way to act on its success were given.
    val bio = if (purpose == PinPurpose.Check && biometric != null && onBiometric != null) biometric else null
    var bioReady by remember(bio) { mutableStateOf(false) }
    var bioBusy by remember(bio) { mutableStateOf(false) }
    var bioAuto by remember(bio) { mutableStateOf(false) }
    val onBio by rememberUpdatedState(onBiometric)
    // A keypad's taps, and the verdict felt before it is read (HAPTICS-PLAN rows 10 to 12).
    val haptic = com.tmplayer.ui.components.rememberHaptics()

    // A wait earned before this pad opened is still a wait. Until it has been read the pad does not know
    // whether it is waiting, so nothing that skips the PIN (a fingerprint, WP-41) is offered before.
    var waitRead by remember(purpose, checker) { mutableStateOf(false) }
    LaunchedEffect(purpose, checker) {
        if (purpose != PinPurpose.Check || checker == null) {
            waitRead = true
            return@LaunchedEffect
        }
        val until = runCatching { checker.waitUntil() }.getOrDefault(0L)
        if (until > PinClock.now()) waitUntil = until
        waitRead = true
    }
    // The countdown, a second at a time, and the keys back once it runs out.
    LaunchedEffect(waitUntil) {
        while (true) {
            now = PinClock.now()
            if (now >= waitUntil) break
            delay(1_000L)
        }
        if (waitUntil != 0L) message = null
        waitUntil = 0L
    }

    // Whether a fingerprint can be tried now: never while the pad waits out wrong PINs, or while one is being checked.
    fun askBiometric() {
        val b = bio ?: return
        // Read from the state as it is now, not as it was when the composition ran: a wait found in this
        // very frame (the read of the stored wait) must already stop the prompt.
        if (!bioReady || !waitRead || waitUntil > now || busy || bioBusy) return
        bioBusy = true
        b.prompt(
            onSuccess = {
                bioBusy = false
                // A wait that began meanwhile still holds: the PIN's back-off is not for a sensor to skip.
                if (waitUntil <= now) onBio?.invoke()
            },
            onEnd = { bioBusy = false },
        )
    }
    // Read each time the pad opens: a sensor can be enrolled or removed between two openings. A read
    // that fails is "not available", never "available".
    LaunchedEffect(bio) { bioReady = bio != null && runCatching { bio.available() }.getOrDefault(false) }
    // Once, when the pad first shows with a ready sensor and nothing typed or waiting; after a cancel the button is there.
    LaunchedEffect(bioReady, waitRead, waiting) {
        if (bioReady && waitRead && waitUntil <= now && !bioAuto) {
            bioAuto = true
            askBiometric()
        }
    }
    // A wait takes the prompt down; the pad leaving takes it down too.
    LaunchedEffect(waiting) { if (waiting) bio?.cancel() }
    DisposableEffect(bio) { onDispose { bio?.cancel() } }

    fun submit(pin: String) {
        when (purpose) {
            PinPurpose.Choose -> {
                val chosen = first
                entered = ""
                when {
                    chosen == null -> {
                        first = pin
                        message = null
                    }
                    chosen == pin -> {
                        haptic(com.tmplayer.ui.components.Haptic.Confirm)
                        onDone(pin)
                    }
                    else -> {
                        haptic(com.tmplayer.ui.components.Haptic.Reject)
                        first = null
                        message = s.lockPinsDiffer
                    }
                }
            }
            PinPurpose.Check -> {
                busy = true
                scope.launch {
                    // The hash is deliberately slow; off the thread drawing the keys.
                    val verdict = withContext(Dispatchers.Default) {
                        // No checker where a PIN is asked: refused (fail closed), never let through.
                        runCatching { (checker ?: RefusingChecker).check(pin) }.getOrElse { PinCheck.Wrong(PinAttempts()) }
                    }
                    busy = false
                    entered = ""
                    when (verdict) {
                        PinCheck.Right, PinCheck.NoPin -> {
                            haptic(com.tmplayer.ui.components.Haptic.Confirm)
                            onDone(pin)
                        }
                        is PinCheck.Wrong -> {
                            haptic(com.tmplayer.ui.components.Haptic.Reject)
                            val wait = verdict.attempts.waitMs(PinClock.now())
                            if (wait > 0) {
                                waitUntil = verdict.attempts.waitUntil
                                message = null
                            } else {
                                message = s.lockWrong(left = checker?.freeLeft(verdict.attempts) ?: verdict.attempts.freeLeft)
                            }
                        }
                        is PinCheck.Waiting -> {
                            haptic(com.tmplayer.ui.components.Haptic.Reject)
                            waitUntil = PinClock.now() + verdict.waitMs
                            message = null
                        }
                    }
                }
            }
        }
    }

    fun press(digit: Char) {
        if (busy || waiting || entered.length >= PinHash.LENGTH) return
        // A digit typed is the PIN's way chosen: the fingerprint prompt gets out of the way.
        if (bioBusy) bio?.cancel()
        haptic(com.tmplayer.ui.components.Haptic.KeyTap)
        entered += digit
        if (entered.length == PinHash.LENGTH) submit(entered)
    }

    fun delete() {
        if (busy || waiting) return
        if (entered.isNotEmpty()) haptic(com.tmplayer.ui.components.Haptic.KeyTap)
        entered = entered.dropLast(1)
    }

    if (onCancel != null) BackHandler(onBack = onCancel)

    val form = deviceForm()
    val tv = form == DeviceForm.Tv
    val keyFocus = remember { FocusRequester() }
    val padFocus = remember { FocusRequester() }
    // A remote needs a key under it to move from; a keyboard needs the pad to have focus for its
    // digits to arrive. A phone needs neither, and a focus ring there would only be noise.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        when (form) {
            DeviceForm.Tv -> runCatching { keyFocus.requestFocus() }
            DeviceForm.Desktop -> runCatching { padFocus.requestFocus() }
            DeviceForm.Phone -> Unit
        }
    }

    val shown = when {
        waiting -> waitWords(waitUntil - now)
        else -> message
    }
    // What a screen reader says: a wrong PIN as it appears, and a wait once, as it starts, not the
    // countdown's every second (the words on screen move, the spoken ones stay fixed per wait).
    val spoken = if (waiting) remember(waitUntil) { shown } else shown
    val heading = when {
        purpose == PinPurpose.Choose && first != null -> s.lockRepeatPin
        else -> title
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Tone.background)
            .focusRequester(padFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val digit = digitOf(event.key)
                when {
                    digit != null -> {
                        press(digit)
                        true
                    }
                    event.key == Key.Backspace || event.key == Key.Delete -> {
                        delete()
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val key = if (tv) 64.dp else 72.dp
        val enabled = !busy && !waiting
        val header: @Composable () -> Unit = {
            if (brand) {
                com.tmplayer.ui.components.BrandLockup(
                    size = com.tmplayer.ui.components.BrandSize.Medium,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }
            Icon(TmIcons.Lock, contentDescription = null, tint = Tone.accent, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                heading,
                style = MaterialTheme.typography.titleLarge,
                color = Tone.text,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Dots(entered.length)
            Text(
                shown.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (waiting) Tone.caution else Tone.danger,
                textAlign = TextAlign.Center,
                minLines = 2,
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .liveMessage()
                    .semantics { if (waiting) contentDescription = spoken.orEmpty() },
            )
        }
        // Always 1 2 3 from the left, as a phone's dialler and every number pad are, also in Arabic
        // and Persian: a mirrored pad put 3 under the thumb where 1 is looked for.
        val keys: @Composable () -> Unit = {
          CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("123", "456", "789").forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        row.forEach { digit ->
                            PadKey(
                                size = key,
                                label = digit.toString(),
                                enabled = enabled,
                                modifier = if (digit == '1') Modifier.focusRequester(keyFocus) else Modifier,
                                onClick = { press(digit) },
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (onCancel != null) {
                        PadKey(size = key, label = s.commonCancel, small = true, onClick = onCancel)
                    } else {
                        Spacer(Modifier.size(key))
                    }
                    PadKey(size = key, label = "0", enabled = enabled, onClick = { press('0') })
                    PadKey(
                        size = key,
                        label = s.lockDeleteDigit,
                        icon = TmIcons.Backspace,
                        enabled = enabled,
                        onClick = { delete() },
                    )
                }
            }
          }
        }
        val biometricButton: @Composable () -> Unit = {
            if (bio != null && bioReady && waitRead && !waiting) {
                TextButton(onClick = { askBiometric() }, enabled = enabled && !bioBusy) {
                    Text(s.lockUseBiometric)
                }
            }
        }
        val forgot: @Composable () -> Unit = {
            if (showForgot) {
                Text(
                    checker?.forgot ?: s.lockForgot,
                    style = MaterialTheme.typography.bodySmall,
                    color = Tone.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        }
        // Four rows of keys under the heading need about 560 dp. A phone on its side has nearer 360,
        // which pushed the bottom row and its 0 off the screen, so a short, wide screen puts the
        // heading beside the keys instead.
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (maxHeight < 560.dp && maxWidth > maxHeight) {
                Row(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        Modifier.widthIn(max = 360.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        header()
                        biometricButton()
                        forgot()
                    }
                    keys()
                }
            } else {
                Column(
                    Modifier
                        .widthIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = if (tv) 32.dp else 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    header()
                    keys()
                    biometricButton()
                    forgot()
                }
            }
        }
    }
}

/** The four places of a PIN, filled as digits go in. The digits themselves are never drawn. */
@Composable
private fun Dots(filled: Int) {
    val description = "$filled / ${PinHash.LENGTH}"
    Row(
        Modifier.semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        repeat(PinHash.LENGTH) { at ->
            val on = at < filled
            Box(
                Modifier
                    .size(16.dp)
                    .then(
                        if (on) {
                            Modifier.background(Tone.accent, CircleShape)
                        } else {
                            Modifier.border(2.dp, Tone.muted, CircleShape)
                        },
                    ),
            )
        }
    }
}

/**
 * One key of the pad: a round button on a touch screen, a rounded tile that fills with the focus
 * colour under a remote. [small] is for a word (Cancel) rather than a digit. A key that cannot be
 * pressed now still takes focus, so the remote is never dropped while a wait runs.
 */
@Composable
private fun PadKey(
    size: Dp,
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    small: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tv = deviceForm() == DeviceForm.Tv
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val shape = if (tv) RoundedCornerShape(Corner.Large) else CircleShape
    val ink = when {
        focused && tv -> Tone.onFocusFill
        !enabled -> Tone.muted
        else -> Tone.text
    }
    // A key drawn as a glyph (the backspace) has its name in a tooltip; a digit or a word says
    // what it is already.
    if (icon != null) {
        com.tmplayer.ui.components.IconTip(label, modifier) {
            PadKeyBody(size, label, Modifier, icon, small, enabled, tv, interactions, focused, shape, ink, onClick)
        }
    } else {
        PadKeyBody(size, label, modifier, icon, small, enabled, tv, interactions, focused, shape, ink, onClick)
    }
}

@Composable
private fun PadKeyBody(
    size: Dp,
    label: String,
    modifier: Modifier,
    icon: ImageVector?,
    small: Boolean,
    enabled: Boolean,
    tv: Boolean,
    interactions: MutableInteractionSource,
    focused: Boolean,
    shape: androidx.compose.ui.graphics.Shape,
    ink: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .heightIn(min = size)
            .background(if (focused && tv) Tone.focusFill else Tone.surface, shape)
            .focusRing(focused, shape)
            .clickable(interactionSource = interactions, indication = null, role = Role.Button) {
                if (enabled) onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            icon != null -> Icon(icon, contentDescription = label, tint = ink, modifier = Modifier.size(26.dp))
            // A word steps down to fit the round key ("Abbrechen" was cut to "Abbreche"). Clip,
            // not Ellipsis, because an ellipsized line never reports an overflow and so never steps.
            small -> Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = ink,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize),
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            else -> Text(
                label,
                style = MaterialTheme.typography.headlineSmall,
                color = ink,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

/** The digit a key stands for: the top row, the number pad, and a remote's number keys. */
internal fun digitOf(key: Key): Char? = when (key) {
    Key.Zero, Key.NumPad0 -> '0'
    Key.One, Key.NumPad1 -> '1'
    Key.Two, Key.NumPad2 -> '2'
    Key.Three, Key.NumPad3 -> '3'
    Key.Four, Key.NumPad4 -> '4'
    Key.Five, Key.NumPad5 -> '5'
    Key.Six, Key.NumPad6 -> '6'
    Key.Seven, Key.NumPad7 -> '7'
    Key.Eight, Key.NumPad8 -> '8'
    Key.Nine, Key.NumPad9 -> '9'
    else -> null
}

/** "Try again in 25 seconds", or in minutes once the wait is a minute or longer. */
private fun waitWords(ms: Long): String {
    val seconds = ((ms + 999) / 1000).coerceAtLeast(1)
    return if (seconds < 60) {
        L.lockWaitSeconds(seconds = seconds)
    } else {
        L.lockWaitMinutes(minutes = (seconds + 59) / 60)
    }
}
