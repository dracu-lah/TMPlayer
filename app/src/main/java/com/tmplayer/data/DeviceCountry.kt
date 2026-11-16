package com.tmplayer.data

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * Where the device thinks it is, for when Telegram will not say.
 *
 * The SIM is asked before the network: a phone roaming abroad still holds the account
 * registered at home, which is the number being typed. The locale is the last resort and the
 * weakest of the three, since it says what language somebody reads rather than where they are.
 * None of them needs a permission.
 */
object DeviceCountry {
    fun code(context: Context): String {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val sim = telephony?.simCountryIso.orEmpty()
        if (sim.isNotBlank()) return sim.uppercase(Locale.ROOT)
        val network = telephony?.networkCountryIso.orEmpty()
        if (network.isNotBlank()) return network.uppercase(Locale.ROOT)
        return Locale.getDefault().country.uppercase(Locale.ROOT)
    }

    /**
     * Which way to support TMPlayer comes first here (see [SupportRegion]): India by the language's
     * region or the SIM's country, else Buy Me a Coffee. No permission, nothing sent anywhere.
     */
    fun supportOrder(context: Context): SupportRegion.Order {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        return SupportRegion.order(Locale.getDefault().country, runCatching { telephony?.simCountryIso }.getOrNull())
    }
}
