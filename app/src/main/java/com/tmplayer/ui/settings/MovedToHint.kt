package com.tmplayer.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tmplayer.data.DeviceForm
import com.tmplayer.data.HintIds
import com.tmplayer.data.SettingsMap
import com.tmplayer.data.SettingsRow
import com.tmplayer.data.SettingsStore
import com.tmplayer.i18n.Strings
import com.tmplayer.ui.components.OneTimeHint
import com.tmplayer.ui.icons.TmIcons

/**
 * One line of the "Moved to" hint (WP-08): the rows of one old page that went to the same page
 * now, named by their titles, [page] where they are, and [first] the row the jump brings into view.
 * Shown for one release, the first time the old page is opened (the WP-68 registry, one hint per
 * old page), so a person who knew where Sign out or Back up was is told once and taken there.
 */
internal class MovedAlias(val title: String, val page: SettingsPage, val first: SettingsRow)

/**
 * The aliases the old page [old] shows on [form]: its moved rows that this device has and a build
 * offers ([needs]), a line for each page they went to, in the page order. Pure but for [strings],
 * so the screen and its tests read the same words.
 */
internal fun movedAliases(old: SettingsPage, form: DeviceForm, needs: SettingsNeeds, strings: Strings): List<MovedAlias> =
    SettingsMap.movedFrom(form, old.group)
        .filter(needs::met)
        .groupBy { it.group }
        .mapNotNull { (group, rows) ->
            val page = SettingsPage.of(group) ?: return@mapNotNull null
            val titles = rows.map { strings.messages.text(it.titleOn(form)) }
            // Three names at most, so the line stays one or two lines at a large font.
            val shown = titles.take(MOVED_TITLES).joinToString(", ") + if (titles.size > MOVED_TITLES) "…" else ""
            MovedAlias(shown, page, rows.first())
        }
        .sortedBy { it.page.ordinal }

private const val MOVED_TITLES = 3

/**
 * The hint's lines at the top of an old page, or nothing: the registry lets it show once per old
 * page (`moved_<page>`), and none at all once the tour was skipped twice. A press opens the page the
 * rows went to on the first of them ([onOpen]).
 */
@Composable
internal fun MovedToHint(
    settings: SettingsStore,
    old: SettingsPage,
    aliases: List<MovedAlias>,
    titleOf: (SettingsPage) -> String,
    strings: Strings,
    onOpen: (MovedAlias) -> Unit,
) {
    if (aliases.isEmpty()) return
    OneTimeHint(settings, HintIds.moved(old.name), label = "", maxShows = 1, draw = {
        Column {
            for (alias in aliases) {
                ActionRow(
                    title = alias.title,
                    subtitle = strings.settingsAlsoInMoved(titleOf(alias.page)),
                    icon = TmIcons.ChevronRight,
                    modifier = Modifier,
                    onClick = { onOpen(alias) },
                )
            }
        }
    })
}
