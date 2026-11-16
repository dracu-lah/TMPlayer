package com.tmplayer.ui.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import com.tmplayer.data.DeviceForm
import com.tmplayer.data.SettingsLink
import com.tmplayer.data.SettingsRow
import com.tmplayer.i18n.Strings
import com.tmplayer.ui.components.JumpTarget
import com.tmplayer.ui.components.jumpTarget
import com.tmplayer.ui.onboarding.SetupStep

/**
 * The row Settings' search box is taking the viewer to (U20). The pages' rows are not each given
 * a target of their own: a row whose title is one of [titles] picks [target] up through
 * [seekable], which every settings row of the phone, the TV and the desktop applies to itself, so
 * a row added later is found without anything more to remember.
 */
@Stable
class SettingsSeek {
    /** The titles, as drawn, of the row being sought; empty while nothing is. */
    var titles: Set<String> by mutableStateOf(emptySet())
        private set

    val target = JumpTarget()

    /** Seeks [row] as [form] draws it, in [strings]'s language. */
    fun aim(row: SettingsRow, form: DeviceForm, strings: Strings) {
        titles = row.titlesOn(form).map { strings.messages.text(it) }.toSet()
    }

    fun clear() {
        titles = emptySet()
    }

    companion object {
        /**
         * Told the title of every settings row as it is drawn, for the tests that hold each page to
         * SettingsMap. Null, and never read, in the app.
         */
        var drawn: ((String) -> Unit)? = null
    }
}

/** The search's row being sought, for [seekable]; null outside Settings. */
val LocalSettingsSeek = staticCompositionLocalOf<SettingsSeek?> { null }

/** Marks a settings row titled [title] as the search's target while it is the one sought. */
fun Modifier.seekable(title: String): Modifier = composed {
    SettingsSeek.drawn?.invoke(title)
    val seek = LocalSettingsSeek.current
    if (seek != null && title in seek.titles) jumpTarget(seek.target) else this
}

/**
 * Where Setup choices' "Also in" sends [step] on [form] ([SettingsLink]), or null where its rows
 * are on more than one page, or the form has no row for it.
 */
fun settingsLinkFor(step: SetupStep, form: DeviceForm): SettingsLink? = when (step) {
    SetupStep.Posters -> SettingsLink.SetupPosters
    SetupStep.Storage -> SettingsLink.SetupStorage
    SetupStep.Performance -> SettingsLink.SetupPerformance
    SetupStep.MobileData -> SettingsLink.SetupMobileData
    // Its settings are on three pages; each row above has its own.
    SetupStep.Device -> null
}?.takeIf { form in it.forms }
