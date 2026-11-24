package com.tmplayer.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.tmplayer.data.SettingsStore
import com.tmplayer.ui.components.TvChoiceRow
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Tv
import kotlinx.coroutines.launch

/**
 * The languages as a list the D-pad walks, the chosen one ticked; OK picks one at once and tells
 * [onPicked]. [focusSelected], when given, sits on the chosen row, which the list opens scrolled to.
 */
@Composable
internal fun TvLanguages(
    settings: SettingsStore,
    modifier: Modifier,
    focusSelected: FocusRequester? = null,
    /** The chosen language when the caller already read it, so the first frame knows the row. */
    current: String? = null,
    onPicked: () -> Unit = {},
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val stored by settings.language.collectAsState(initial = "")
    // Without the caller's value the first frame takes "" (System default) for the chosen row, and
    // the focus would land there rather than on the language in use.
    val saved = current ?: stored
    val system = remember { systemLanguageName() }
    val choices = remember { Onboarding.languages() }
    // The chosen row; a stored tag the list does not know leaves the first row to take the remote.
    val chosenAt = choices.indexOfFirst { it.tag.equals(saved, ignoreCase = true) }.coerceAtLeast(0)
    val list = rememberLazyListState(
        // Two rows above the chosen one show, so the list reads as a list with the choice inside it (WP-15).
        initialFirstVisibleItemIndex = (chosenAt - 2).coerceAtLeast(0),
    )
    LazyColumn(
        modifier,
        state = list,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(vertical = Tv.FocusClearance),
    ) {
        itemsIndexed(choices, key = { _, it -> it.tag }) { at, choice ->
            val selected = saved.equals(choice.tag, ignoreCase = true)
            TvChoiceRow(
                title = choice.name ?: s.onboardingLanguageSystem,
                trailing = if (choice.name == null) system else null,
                selected = selected,
                modifier = if (at == chosenAt && focusSelected != null) Modifier.focusRequester(focusSelected) else Modifier,
            ) {
                scope.launch { settings.setLanguage(choice.tag) }
                onPicked()
            }
        }
    }
}
