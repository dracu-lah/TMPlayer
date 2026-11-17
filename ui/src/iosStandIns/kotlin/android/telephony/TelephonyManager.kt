package android.telephony

/** No SIM details reach an iOS app; the device country comes from the locale instead. */
class TelephonyManager {
    val simCountryIso: String? get() = null
    val networkCountryIso: String? get() = null
}
