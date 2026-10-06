package com.tmplayer.ui.browse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.components.AppLogo
import com.tmplayer.ui.i18n.LocalStrings

// The Material 3 half of the folding sidebar, shared by the phone's drawer and the desktop's side
// bar. The television draws its own, in tv-material, from the same NavGroups model.

/**
 * The heading over a group: its name and a chevron that folds it.
 *
 * Quiet on purpose, in the muted label style the drawer's headings always had, so the rows under
 * it still read as the destinations. The group holding the current page has no chevron and does
 * not respond: it cannot be folded, and a control that does nothing is worse than none.
 */
@Composable
fun NavGroupHeading(
    group: NavGroup,
    open: Boolean,
    toggleable: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    /** 48 dp on a phone, where a thumb has to hit it; less on the desktop. */
    height: Dp = 40.dp,
    start: Dp = 16.dp,
) {
    val s = LocalStrings.current
    val turn by animateFloatAsState(if (open) 0f else -90f, label = "navGroupChevron")
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .then(
                if (toggleable) {
                    Modifier
                        .clickable(role = Role.Button, onClickLabel = if (open) s.navFold else s.navUnfold, onClick = onToggle)
                        .semantics { stateDescription = if (open) s.navOpen else s.navFolded }
                } else {
                    Modifier
                },
            )
            .padding(start = start, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            group.label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (toggleable) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp).rotate(turn),
            )
        }
    }
}

/** A group's rows, folding away under its heading. */
@Composable
fun NavGroupBody(open: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = open,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Column { content() }
    }
}

/**
 * The logo, the name and the version, at the top of the drawer and the side bar.
 *
 * The version is muted and smaller, a thing to read off when reporting a problem rather than a
 * control. When a newer one is out, the amber Update row says so; this stays plain text.
 */
@Composable
fun NavBrand(
    version: String,
    modifier: Modifier = Modifier,
    logo: @Composable () -> Unit = {
        Image(AppLogo.Mark, contentDescription = null, modifier = Modifier.size(28.dp))
    },
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        logo()
        Spacer(Modifier.width(12.dp))
        Text("TMPlayer", style = MaterialTheme.typography.titleMedium, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        Text(
            "v$version",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
