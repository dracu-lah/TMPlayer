package com.tmplayer.ui.auth

import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.nav.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.tmplayer.data.Country
import com.tmplayer.data.PhoneCountries
import com.tmplayer.data.from
import androidx.compose.ui.platform.testTag
import com.tmplayer.ui.components.dpadLeavesFieldOnTv
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.theme.Tone

/**
 * The full-screen list of countries, the way Telegram's own sign-in offers it.
 *
 * A picker rather than a free-text country field, because the thing being chosen is really the
 * dial code and almost nobody knows their own by heart, let alone anybody else's. It covers the
 * pane rather than floating over it: on a phone a list of two hundred rows behind a keyboard needs
 * the whole screen, and a half-height sheet only makes the scrolling longer.
 *
 * On every device: on a phone and a desktop the search box has the keyboard at once; on a TV the
 * remote lands on the country already chosen ([selected]), the search box one press up, and the
 * row in focus is filled so it can be seen from the sofa.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountryPicker(
    countries: List<Country>,
    onPick: (Country) -> Unit,
    onDismiss: () -> Unit,
    selected: Country? = null,
) {
    val s = LocalStrings.current
    val touch = isTouch()
    var query by remember { mutableStateOf("") }
    val search = remember { FocusRequester() }
    val firstRow = remember { FocusRequester() }
    val shown = remember(countries, query) {
        countries.filter { PhoneCountries.matches(it, query) }
    }
    val start = remember(shown, selected) { shown.indexOf(selected).coerceAtLeast(0) }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = if (query.isEmpty()) start else 0)

    BackHandler(onBack = onDismiss)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Tone.background,
        topBar = {
            TopAppBar(
                title = { Text(s.loginChooseCountry) },
                navigationIcon = {
                    com.tmplayer.ui.components.IconTip(s.loginCloseCountryList, side = com.tmplayer.ui.components.TipSide.Below) {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                TmIcons.ArrowBack,
                                contentDescription = s.loginCloseCountryList,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Tone.background),
            )
        },
    ) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val column = Modifier.widthIn(max = PICKER_MAX_WIDTH).fillMaxWidth()
            val held = com.tmplayer.ui.components.rememberHeldText(query)
            OutlinedTextField(
                value = held.value,
                onValueChange = { held.edit(it, { query = it }) },
                singleLine = true,
                label = { Text(s.loginCountrySearch) },
                leadingIcon = { Icon(TmIcons.Search, contentDescription = null) },
                trailingIcon = {
                    // Two hundred rows filtered to none is a dead end, and clearing a field by
                    // holding backspace is a chore nobody should be set on a sign-in screen.
                    if (query.isNotEmpty()) {
                        com.tmplayer.ui.components.ClearButton(s.loginClearSearch, onClick = { query = "" })
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = MaterialTheme.shapes.medium,
                modifier = column
                    .padding(horizontal = SIDE, vertical = 8.dp)
                    .focusRequester(search)
                    .dpadLeavesFieldOnTv()
                    .testTag(COUNTRY_SEARCH_TAG),
            )

            if (shown.isEmpty()) {
                Text(
                    s.loginCountryNoMatch,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Tone.muted,
                    modifier = column.padding(horizontal = SIDE, vertical = 8.dp),
                )
            }

            LazyColumn(column.fillMaxSize(), state = list) {
                itemsIndexed(shown, key = { _, it -> it.iso }) { index, country ->
                    val interactions = remember { MutableInteractionSource() }
                    val focused by interactions.collectIsFocusedAsState()
                    val lit = focused && !touch
                    ListItem(
                        leadingContent = {
                            Text(country.flag, style = MaterialTheme.typography.titleLarge)
                        },
                        headlineContent = { Text(country.name, color = if (lit) Tone.onFocusFill else Tone.text) },
                        trailingContent = { Text("+${country.dialCode}", color = if (lit) Tone.onFocusFill else Tone.muted) },
                        colors = ListItemDefaults.colors(containerColor = if (lit) Tone.focusFill else Tone.background),
                        // A row in a list of two hundred is a button whether or not it looks like
                        // one, and a screen reader is told so here because nothing else says it.
                        modifier = Modifier
                            .then(if (index == (if (query.isEmpty()) start else 0)) Modifier.focusRequester(firstRow) else Modifier)
                            .clickable(interactionSource = interactions, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button) { onPick(country) },
                    )
                    HorizontalDivider(color = Tone.outline)
                }
            }
        }
    }

    // The keyboard is the point of this screen on a phone: two hundred rows are found by typing,
    // not by scrolling. A remote starts on the list instead, where Up reaches the search box.
    LaunchedEffect(Unit) {
        runCatching { if (touch) search.requestFocus() else firstRow.requestFocus() }
    }
}

private val SIDE = 16.dp

/** A desktop window can be wide; past this the rows read as a table nobody can follow across. */
private val PICKER_MAX_WIDTH = 640.dp

/** The countries Telegram accepts numbers from, loaded once per pane. Empty until TDLib answers. */
@Composable
fun rememberSignInCountries(): List<Country> {
    val countries by androidx.compose.runtime.produceState(emptyList<Country>()) {
        value = runCatching { PhoneCountries.from(com.tmplayer.data.Td.countries()) }.getOrDefault(emptyList())
    }
    return countries
}

/** Test tag of the country picker's search box. */
const val COUNTRY_SEARCH_TAG = "country-search"
