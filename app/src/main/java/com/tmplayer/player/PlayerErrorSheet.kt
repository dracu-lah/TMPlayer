package com.tmplayer.player

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import com.tmplayer.ui.settings.SendLogsButton
import com.tmplayer.ui.theme.TMPlayerTheme
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
    fun addSendLogs(openWith: View?): View? {
        val column = openWith?.parent as? ViewGroup ?: return null
        val logs = ComposeView(column.context).apply {
            setContent {
                TMPlayerTheme {
                    androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        SendLogsButton()
                        com.tmplayer.ui.settings.SendToDeveloperButton()
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
}
