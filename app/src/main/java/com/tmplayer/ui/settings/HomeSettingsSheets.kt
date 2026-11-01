package com.tmplayer.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.theme.Tone

/**
 * Small print under a settings group, in the muted colour, lined up with the cards' rows.
 *
 * This file once also held Home's "Recently added comes from" sheets (Automatic or Chosen chats,
 * and a picker of the chosen chats). They repeated the Add chats picker and are gone (2026-11-01):
 * Recently added follows the added chats ([com.tmplayer.data.AddedChats]).
 */
@Composable
internal fun SettingsNoteText(text: String) {
    val touch = isTouch()
    Text(
        text,
        style = if (touch) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        color = Tone.muted,
        modifier = Modifier.padding(start = if (touch) 16.dp else 4.dp, end = 16.dp, top = 6.dp, bottom = 4.dp),
    )
}
