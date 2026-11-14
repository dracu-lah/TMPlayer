package com.tmplayer.player

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import com.tmplayer.ui.settings.SendLogsButton
import com.tmplayer.ui.theme.TMPlayerTheme
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp

/** The parts of the player's failure sheet that are built in code rather than in activity_player.xml. */
object PlayerErrorSheet {

    /**
     * "Send logs", the shared Compose button ([SendLogsButton]), hosted under Open in another app
     * in the sheet's button column, last and quietest. Hidden until a failure shows it; the
     * activity and its render test both build it here, so the test draws what the viewer sees.
     *
     * The gap above it is a margin on the view, as the buttons' are, rather than padding inside
     * the Compose button: [SheetActions] keeps the margin in a column and drops it in a row, where
     * padding would have sat the pill lower than the buttons beside it.
     */
    fun addSendLogs(openWith: View?, details: (() -> String)? = null): View? {
        val column = openWith?.parent as? ViewGroup ?: return null
        val touch = !com.tmplayer.data.FormFactor.isTv(column.context)
        val logs = ComposeView(column.context).apply {
            setContent {
                TMPlayerTheme {
                    androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        SendLogsButton()
                        com.tmplayer.ui.settings.SendToDeveloperButton()
                        // What went wrong, in words a report or a forum post can carry: a phone
                        // has a clipboard to put it on; a television does not.
                        if (touch && details != null) CopyDetailsButton(details)
                    }
                }
            }
            visibility = View.GONE
        }
        val gap = ViewGroup.MarginLayoutParams(-2, -2).apply {
            topMargin = (10 * column.resources.displayMetrics.density).toInt()
        }
        column.addView(logs, column.indexOfChild(openWith) + 1, gap)
        return logs
    }

    @androidx.compose.runtime.Composable
    private fun CopyDetailsButton(details: () -> String) {
        val s = com.tmplayer.ui.i18n.LocalStrings.current
        val context = androidx.compose.ui.platform.LocalContext.current
        com.tmplayer.ui.components.TmSecondaryButton(onClick = {
            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
            val copied = runCatching {
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(s.playerCopyDetails, details()))
            }.isSuccess && clipboard != null
            com.tmplayer.ui.components.showMessage(context, if (copied) s.playerDetailsCopied else s.stateFailed)
        }) {
            androidx.compose.material3.Icon(com.tmplayer.ui.icons.TmIcons.Copy, contentDescription = null, modifier = androidx.compose.ui.Modifier.size(20.dp))
            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(8.dp))
            androidx.compose.material3.Text(s.playerCopyDetails)
        }
    }
}
