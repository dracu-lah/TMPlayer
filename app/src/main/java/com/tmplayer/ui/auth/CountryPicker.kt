package com.tmplayer.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.Country
import com.tmplayer.data.PhoneCountries
import com.tmplayer.ui.components.PhonePad
import com.tmplayer.ui.theme.SurfaceDark
import com.tmplayer.ui.theme.TextMuted

/**
 * The full-screen list of countries, the way Telegram's own sign-in offers it.
 *
 * A picker rather than a free-text country field, because the thing being chosen is really the
 * dial code and almost nobody knows their own by heart, let alone anybody else's. It covers the
 * pane rather than floating over it: on a phone a list of two hundred rows behind a keyboard needs
 * the whole screen, and a half-height sheet only makes the scrolling longer.
 *
 * Touch only. The television keeps the plain international field it has always had, where a remote
 * would have to walk this list a row at a time.
 */
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

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // safeDrawing already carries the keyboard inset; adding imePadding on top of it
            // pushed the whole list a keyboard height up the screen the moment anyone typed.
            .safeDrawingPadding()
            .padding(horizontal = PhonePad.Side, vertical = PhonePad.Top),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackArrow(onBack = onDismiss, description = "Close the country list")
            Spacer(Modifier.width(8.dp))
            Text("Choose a country", style = MaterialTheme.typography.titleLarge)
        }

        PaneField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search by name or code",
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().focusRequester(search),
        )

        if (shown.isEmpty()) {
            Text(
                "Nothing matches that.",
                style = MaterialTheme.typography.bodyLarge,
                color = TextMuted,
            )
        }

        LazyColumn(Modifier.fillMaxWidth()) {
            items(shown, key = { it.iso }) { country ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(country) }
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(country.flag, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(14.dp))
                    Text(
                        country.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "+${country.dialCode}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextMuted,
                    )
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(SurfaceDark))
            }
        }
    }

    // The keyboard is the point of this screen: two hundred rows are found by typing, not by
    // scrolling, and every frame spent reaching for the search box is one the user spends flicking.
    LaunchedEffect(Unit) { search.requestFocus() }
}
