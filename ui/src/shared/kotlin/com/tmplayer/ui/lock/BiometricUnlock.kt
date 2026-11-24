package com.tmplayer.ui.lock

/**
 * A fingerprint or face check the platform can put up, offered to the app lock's pad and to nothing
 * else (WP-41, security sensitive).
 *
 * What it is allowed to do is small on purpose. A success opens the pad that asks when the app
 * opens: it never opens Settings, signing out, "Remove the PIN", a profile's PIN or the kids lock
 * (those pads are built without one, and [PinPad]'s checker overload has no parameter for it), so
 * the fingerprints enrolled on a shared phone cannot open another person's profile. Anything but a
 * success the system reports (a cancel, a lockout, a sensor that went away, an error, a timeout)
 * leaves the pad as it was: locked, the PIN always working. It is never asked for, and never
 * answers, while the pad waits out wrong PINs, and it holds back until the lock and the opt-in have
 * been read (the pad is not given one before).
 *
 * The Android implementation lives in `app` (androidx.biometric); shared code only sees this.
 */
interface BiometricUnlock {

    /** True when a Class 2 or 3 biometric is enrolled and the sensor can be used now. Read each time the pad opens. */
    fun available(): Boolean

    /**
     * Puts the system's prompt up. Exactly one of [onSuccess] and [onEnd] is called, once, on the
     * main thread: [onSuccess] only for a success the system reports, [onEnd] for every other end.
     */
    fun prompt(onSuccess: () -> Unit, onEnd: () -> Unit)

    /** Takes the prompt down without a result (the pad is leaving, or the viewer typed a digit). */
    fun cancel()
}

/** What this device can do about a fingerprint or face, for the Settings row (WP-41). */
enum class BiometricState {
    /** A Class 2 or 3 biometric is enrolled and the sensor works. */
    Ready,

    /** The sensor is there but nothing is enrolled: the row is off and says to add one first. */
    NoneEnrolled,

    /** No sensor, no support on this device (a television, iOS for now), or it is not usable now: the row is not shown. */
    Unavailable,
}
