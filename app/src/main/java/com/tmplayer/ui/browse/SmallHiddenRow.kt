package com.tmplayer.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.i18n.L
import com.tmplayer.ui.components.TmButton
import com.tmplayer.ui.theme.Tone

/**
 * What ends a chat's grid when "Hide small clips" left videos out (R1): "3 small videos hidden"
 * and Show them, which lists them for this visit. [count] is exact only when [exact] (the chat has
 * been read to its start); until then the row says "Some small videos are hidden", never a number
 * it cannot stand behind. A full width line of the grid, so the remote reaches the button with Down
 * from the last row, as it reaches the archives and a search's Keep looking.
 */
@Composable
internal fun SmallHiddenRow(count: Int, exact: Boolean, onShow: () -> Unit, modifier: Modifier = Modifier) {
    val words = if (exact) L.gridSmallHidden(count) else L.gridSmallHiddenSome
    // At a large font the words and the button no longer fit side by side: the button sits under them.
    val stacked = LocalDensity.current.fontScale > 1.15f
    if (stacked) {
        Column(
            modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(words, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
            TmButton(onClick = onShow) { Text(L.gridFilterShowThem) }
        }
        return
    }
    Row(
        modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(words, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, modifier = Modifier.weight(1f, fill = false))
        TmButton(onClick = onShow) { Text(L.gridFilterShowThem) }
    }
}

/** [SmallHiddenRow] as a grid line; nothing when [count] is 0. */
internal fun LazyGridScope.smallHiddenItem(count: Int, exact: Boolean, onShow: () -> Unit) {
    if (count <= 0) return
    item(key = "small-hidden", span = { GridItemSpan(maxLineSpan) }, contentType = "small-hidden") {
        SmallHiddenRow(count, exact, onShow)
    }
}
