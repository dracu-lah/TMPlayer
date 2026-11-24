package com.tmplayer.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.tmplayer.data.DeviceForm
import com.tmplayer.data.SettingsMap
import com.tmplayer.data.SettingsNeed
import com.tmplayer.data.SettingsRow
import com.tmplayer.data.SettingsSearch
import com.tmplayer.i18n.Strings
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons

/**
 * Settings' search box on a phone (U20), over the category list once Settings has more rows than
 * [SettingsSearch.BOX_FROM_ROWS]. A television has none: its pages are a few presses of the D-pad
 * apart, and typing with a remote costs more than walking them.
 */
@Composable
internal fun SettingsSearchBox(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = { onQuery(it.take(MAX_QUERY)) },
        singleLine = true,
        placeholder = { Text(s.settingsSearch) },
        leadingIcon = { Icon(TmIcons.Search, contentDescription = null) },
        trailingIcon = if (query.isEmpty()) {
            null
        } else {
            {
                com.tmplayer.ui.components.ClearButton(s.settingsSearchClear, onClick = { onQuery("") })
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(Corner.Large),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(vertical = 8.dp)
            .testTag(SEARCH_TAG),
    )
}

/** A query longer than any title is somebody's paste, not a search. */
private const val MAX_QUERY = 80

internal const val SEARCH_TAG = "settings-search"

/** What a phone or a television can show of [row], from what this build and device have. */
internal class SettingsNeeds(
    private val vlc: Boolean,
    private val pictureInPicture: Boolean,
    private val logInbox: Boolean,
    private val playNext: Boolean,
    private val channels: Boolean,
) {
    fun met(row: SettingsRow): Boolean {
        // No Notifications page on a build that posts no notices (iOS, WP-38): its rows are not searched for either.
        if (row.group == com.tmplayer.data.SettingsGroup.Notifications && !com.tmplayer.data.BackgroundChecks.postsNotices) return false
        return needMet(row)
    }

    private fun needMet(row: SettingsRow): Boolean = when (row.need) {
        null, SettingsNeed.Advanced -> true
        SettingsNeed.Vlc -> vlc
        SettingsNeed.Android12 -> android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
        SettingsNeed.PictureInPicture -> pictureInPicture
        SettingsNeed.OnlineSubtitles -> com.tmplayer.online.OnlineSubtitles.available
        SettingsNeed.OnlineMetadata -> com.tmplayer.online.OnlineMetadata.current != null
        SettingsNeed.Updates -> com.tmplayer.data.Updates.enabled
        SettingsNeed.AutoInstall -> com.tmplayer.data.Updates.enabled && com.tmplayer.data.AutoUpdater.supported
        SettingsNeed.CrashReports -> com.tmplayer.data.CrashReports.available
        SettingsNeed.LogInbox -> logInbox
        SettingsNeed.LauncherPlayNext -> playNext
        SettingsNeed.LauncherChannels -> channels
        // What the viewer has (favourites to clear, a PIN to change): the row is not always there
        // to be taken to, so the search leaves it out.
        SettingsNeed.State -> false
    }

    /** The rows [form] shows here, for the search to look through. */
    fun rows(form: DeviceForm): List<SettingsRow> = SettingsMap.on(form).filter(::met)
}

/** A message for the search, with the words a row's own call would have given its arguments. */
internal fun Strings.searchText(key: String, touch: Boolean): String =
    messages.format(key, mapOf("device" to if (touch) "phone" else "tv", "form" to if (touch) "phone" else "tv"))
