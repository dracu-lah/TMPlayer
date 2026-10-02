package com.tmplayer.data

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import java.util.Locale

enum class LanguageChoice(val tag: String?) {
    System(null),
    Spanish("es"),
    English("en"),
    ;

    companion object {
        fun current(context: Context): LanguageChoice {
            val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null)
            return entries.firstOrNull { it.tag == stored } ?: System
        }

        internal const val PREFS = "tmplayer_locale"
        internal const val KEY = "language"
    }
}

object Language {
    fun wrap(base: Context): Context {
        val choice = LanguageChoice.current(base)
        val locale = choice.tag?.let(Locale::forLanguageTag) ?: systemLocale(base)
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocales(LocaleList(locale))
        return base.createConfigurationContext(configuration)
    }

    fun select(context: Context, choice: LanguageChoice) {
        context.getSharedPreferences(LanguageChoice.PREFS, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (choice == LanguageChoice.System) remove(LanguageChoice.KEY)
                else putString(LanguageChoice.KEY, choice.tag)
            }
            .apply()
    }

    private fun systemLocale(context: Context): Locale {
        val locales = Resources.getSystem().configuration.locales
        for (index in 0 until locales.size()) {
            if (locales[index].language == "es") return Locale.forLanguageTag("es")
        }
        return Locale.ENGLISH
    }
}
