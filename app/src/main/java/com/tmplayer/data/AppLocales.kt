package com.tmplayer.data

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import com.tmplayer.i18n.Languages
import com.tmplayer.platform.Logger

/**
 * The in-app language and Android's own per-app language (Android 13 and later: Settings, Apps,
 * TMPlayer, Language) kept as one setting.
 *
 * The in-app choice is the one the app reads ([SettingsStore.language]). Each change is handed to
 * [LocaleManager], so Android's page shows it and the views outside Compose (the player's overlay)
 * lay out in its direction. A change made on Android's page comes back the other way through
 * [adopt]: a language picked there becomes the in-app choice, and "System default" picked there
 * clears it.
 *
 * Older Android has no per-app language, so there it is the in-app setting alone.
 */
object AppLocales {

    private const val TAG = "AppLocales"
    private const val PREFS = "app_locales"
    private const val APPLIED = "applied"

    /** The device's own languages, as BCP 47 tags, not this app's override of them. */
    fun system(context: Context): List<String> {
        val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.systemLocales ?: LocaleList.getDefault()
        } else {
            LocaleList.getDefault()
        }
        return list.toLanguageTags().split(',').filter { it.isNotBlank() }
    }

    /** Hands the in-app choice to Android. "" is the system's language. */
    fun apply(context: Context, saved: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val manager = context.getSystemService(LocaleManager::class.java) ?: return
        val wanted = if (saved.isBlank()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(saved)
        runCatching {
            if (manager.applicationLocales.toLanguageTags() != wanted.toLanguageTags()) manager.applicationLocales = wanted
        }.onFailure { Logger.w(TAG, "could not hand the language to Android", it) }
        Logger.i(TAG, "app locales now ${manager.applicationLocales.toLanguageTags().ifEmpty { "system" }}")
        prefs(context).edit().putString(APPLIED, saved).apply()
    }

    /**
     * Takes a change made on Android's language page since the app last handed one over, and
     * returns the in-app choice to save, or null when nothing changed there.
     */
    fun adopt(context: Context, saved: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val manager = context.getSystemService(LocaleManager::class.java) ?: return null
        val current = runCatching { manager.applicationLocales }.getOrNull() ?: return null
        val applied = prefs(context).getString(APPLIED, null) ?: return null
        return changed(applied, saved, if (current.isEmpty) "" else current[0].toLanguageTag())
    }

    /**
     * The pure rule behind [adopt]: [applied] is what the app last handed Android, [saved] the
     * in-app choice and [android] what Android holds now ("" for the system's language).
     */
    internal fun changed(applied: String, saved: String, android: String): String? {
        val now = if (android.isBlank()) "" else Languages.match(android) ?: return null
        if (now.equals(Languages.match(applied) ?: applied, ignoreCase = true)) return null
        return now.takeUnless { it.equals(saved, ignoreCase = true) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
