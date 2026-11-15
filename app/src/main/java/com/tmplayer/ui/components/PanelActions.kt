package com.tmplayer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The buttons at the foot of a floating panel, in one order on every panel (U19): the safe press
 * first, the step itself last, at the end edge, as [TvConfirm] and the title report already had
 * them. A long translation wraps them to a second line instead of running them off the panel.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PanelActions(modifier: Modifier = Modifier, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}
