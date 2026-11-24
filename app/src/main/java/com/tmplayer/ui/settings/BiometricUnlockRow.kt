package com.tmplayer.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.data.SettingsStore
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.lock.BiometricPrompter
import com.tmplayer.ui.lock.BiometricState
import kotlinx.coroutines.launch

/**
 * "Fingerprint or face unlock" on Privacy and lock (WP-41): a switch, off until chosen, for the app
 * lock's own pad only. Drawn only on a phone, only while the lock asks when the app opens (the
 * caller checks), and only where the device has a sensor. With a sensor but nothing enrolled it is
 * drawn off, not pressable, and says what to do; the sensor is read again on every return to the
 * screen, so enrolling one in the system settings is seen here.
 */
@Composable
internal fun BiometricUnlockRow(settings: SettingsStore) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    var sensor by remember { mutableStateOf(BiometricPrompter.state(context)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) sensor = BiometricPrompter.state(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val on by settings.appLockBiometric.collectAsStateWithLifecycle(initialValue = false)
    val kind = biometricRowOf(sensor, on)
    if (kind == BiometricRow.Hidden) return
    val ready = kind == BiometricRow.Ready
    ToggleRow(
        title = s.settingsBiometricUnlock,
        subtitle = when (kind) {
            BiometricRow.Weaker -> s.lockBiometricWeak
            BiometricRow.Ready -> s.lockBiometricRowDetail
            else -> s.lockBiometricNone
        },
        icon = TmIcons.Lock,
        checked = on && ready,
        enabled = ready,
        onToggle = { scope.launch { runCatching { settings.setAppLockBiometric(!on) } } },
    )
}

/** What Privacy and lock shows for the fingerprint or face row. */
internal enum class BiometricRow {
    /** A phone with no strong sensor (SEC2-07, only a Class 2 face unlock, or none) and nothing switched on: no row. */
    Hidden,

    /** A strong sensor with a fingerprint or face enrolled: the switch works. */
    Ready,

    /** A strong sensor with nothing enrolled: drawn off, saying what to do. */
    NoneEnrolled,

    /**
     * It was switched on and the phone now offers only a weaker sensor (or none): kept, drawn off and not
     * pressable, saying the PIN is used. The pad never offers the sensor then (it asks `available()` each time),
     * so the lock cannot fail open; the switch comes back as it was if a strong one is enrolled again.
     */
    Weaker,
}

internal fun biometricRowOf(sensor: BiometricState, switchedOn: Boolean): BiometricRow = when (sensor) {
    BiometricState.Ready -> BiometricRow.Ready
    BiometricState.NoneEnrolled -> BiometricRow.NoneEnrolled
    BiometricState.Unavailable -> if (switchedOn) BiometricRow.Weaker else BiometricRow.Hidden
}
