package com.tmplayer.data

/**
 * What an Android device says about itself, as far as "is this a television?" goes. Each field is
 * null when it could not be read, which counts as not knowing rather than as a no.
 *
 * Read by `FormSignalsAndroid` on Android and decided by [FormRules], which is pure so every kind
 * of device can be tested without one (HX78).
 */
data class FormSignals(
    /** The leanback feature: a certified Android TV or Google TV, and Fire TV. */
    val leanback: Boolean? = null,
    /** The system's UI mode is television. */
    val uiModeTv: Boolean? = null,
    /** `android.hardware.type.pc`: Android on a desktop or a laptop. */
    val pc: Boolean? = null,
    /** An ARC feature: Android apps on a Chromebook. */
    val chromebook: Boolean? = null,
    val watch: Boolean? = null,
    /** An accelerometer is fitted. No television has one; nearly every phone and tablet does. */
    val accelerometer: Boolean? = null,
    /**
     * The window manager sees a touchscreen (`Configuration.touchscreen`). Derived from the input
     * devices really attached, unlike the touchscreen feature, which box firmware declares anyway.
     */
    val touchInput: Boolean? = null,
    /** A battery is present. */
    val battery: Boolean? = null,
)

/**
 * The rule that turns [FormSignals] into a layout (HX78).
 *
 * Certified televisions say so (leanback, or the television UI mode). Generic boxes (T95, X96,
 * H96, MXQ, Tanix) run tablet builds of Android that say neither, so the hardware decides: no
 * accelerometer, and either no touchscreen or no battery. Two signals rather than one, because a
 * cheap tablet can lack an accelerometer (it still has touch and a battery) and box firmware can
 * fake touch or a battery (it rarely fakes an accelerometer). Anything unknown leans to the phone
 * layout, which is what every device got before this rule; the Interface setting and the remote
 * nudge cover a device the rule gets wrong.
 */
object FormRules {

    fun classify(s: FormSignals): DeviceForm = if (decide(s).tv) DeviceForm.Tv else DeviceForm.Phone

    /** Why [classify] answered as it did, for the log line a box owner's Send logs carries. */
    fun reason(s: FormSignals): String = decide(s).reason

    /** True when the answer came from the hardware guess rather than from the system saying so. */
    fun guessed(s: FormSignals): Boolean = decide(s).let { it.tv && it.reason.startsWith("no-accel") }

    private data class Verdict(val tv: Boolean, val reason: String)

    private fun decide(s: FormSignals): Verdict {
        if (s.leanback == true) return Verdict(true, "leanback")
        if (s.uiModeTv == true) return Verdict(true, "uimode-tv")
        if (s.pc == true) return Verdict(false, "pc")
        if (s.chromebook == true) return Verdict(false, "chromebook")
        if (s.watch == true) return Verdict(false, "watch")
        if (s.accelerometer == false) {
            val noTouch = s.touchInput == false
            val noBattery = s.battery == false
            if (noTouch && noBattery) return Verdict(true, "no-accel+notouch+nobattery")
            if (noTouch) return Verdict(true, "no-accel+notouch")
            if (noBattery) return Verdict(true, "no-accel+nobattery")
            return Verdict(false, "no-accel-but-touch+battery")
        }
        return Verdict(false, if (s.accelerometer == true) "accel" else "unknown")
    }
}
