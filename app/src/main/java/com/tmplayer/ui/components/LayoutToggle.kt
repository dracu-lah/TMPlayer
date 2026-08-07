package com.tmplayer.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.ui.graphics.vector.ImageVector
import com.tmplayer.data.CardLayout

/**
 * What the arrangement toggle should say, given where the screen is now.
 *
 * Named after where the press lands rather than where it is standing, which is how the other
 * controls beside it read ("Clear", "Refresh", "Add favourite"). A toggle labelled with its
 * current state is a coin flip from three metres away: nothing on screen says whether the word is
 * a description or a destination.
 */
val CardLayout.switchLabel: String
    get() = when (toggled()) {
        CardLayout.List -> "List view"
        CardLayout.Grid -> "Grid view"
    }

val CardLayout.switchIcon: ImageVector
    get() = when (toggled()) {
        CardLayout.List -> Icons.Filled.List
        CardLayout.Grid -> TmIcons.Grid
    }
