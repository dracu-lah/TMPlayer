package com.tmplayer.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.tmplayer.data.Country
import com.tmplayer.data.PhoneCountries
import com.tmplayer.ui.components.PhonePad
import com.tmplayer.ui.theme.Tone

/**
 * The full-screen list of countries, the way Telegram's own sign-in offers it.
 *
 * A picker rather than a free-text country field, because the thing being chosen is really the
 * dial code and almost nobody knows their own by heart, let alone anybody else's. It covers the
 * pane rather than floating over it: on a phone a list of two hundred rows behind a keyboard needs
 * the whole screen, and a half-height sheet only makes the scrolling longer.
 *
 * Touch only: a remote would have to walk this list a row at a time, so the television keeps its
 * plain international field. Since nothing but a phone draws this, it is the one sign-in screen
 * written straight against the phone's Material theme with no branch in it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountryPicker(
    countries: List<Country>,
    onPick: (Country) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val search = remember { FocusRequester() }
    val shown = remember(countries, query) {
        countries.filter { PhoneCountries.matches(it, query) }
    }

    BackHandler(onBack = onDismiss)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Tone.background,
        topBar = {
            TopAppBar(
                title = { Text("Choose a country") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Close the country list",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Tone.background),
            )
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text("Search by name or code") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    // Two hundred rows filtered to none is a dead end, and clearing a field by
                    // holding backspace is a chore nobody should be set on a sign-in screen.
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear the search")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PhonePad.Side, vertical = 8.dp)
                    .focusRequester(search),
            )

            if (shown.isEmpty()) {
                Text(
                    "Nothing matches that.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Tone.muted,
                    modifier = Modifier.padding(horizontal = PhonePad.Side, vertical = 8.dp),
                )
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.iso }) { country ->
                    ListItem(
                        leadingContent = {
                            Text(country.flag, style = MaterialTheme.typography.titleLarge)
                        },
                        headlineContent = { Text(country.name) },
                        trailingContent = { Text("+${country.dialCode}") },
                        colors = ListItemDefaults.colors(containerColor = Tone.background),
                        // A row in a list of two hundred is a button whether or not it looks like
                        // one, and a screen reader is told so here because nothing else says it.
                        modifier = Modifier.clickable(role = Role.Button) { onPick(country) },
                    )
                    HorizontalDivider(color = Tone.outline)
                }
            }
        }
    }

    // The keyboard is the point of this screen: two hundred rows are found by typing, not by
    // scrolling, and every frame spent reaching for the search box is one the user spends flicking.
    LaunchedEffect(Unit) { search.requestFocus() }
}
