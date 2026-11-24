package com.tmplayer.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tmplayer.data.BackgroundChecks
import com.tmplayer.data.NotificationAsk
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Tone

/**
 * Whether a notification posted now would be seen, read again each time the screen comes back
 * (WP-38): the person turns the app's notifications on in the system settings and returns here.
 */
@Composable
internal fun rememberCanNotify(): State<Boolean> {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val state = remember { mutableStateOf(BackgroundChecks.canNotify(context)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.value = BackgroundChecks.canNotify(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return state
}

/**
 * The top of the Notifications page while notifications cannot reach the person: what is off, what
 * still works, and the way to the system's page for the app. After two refusals Android shows no
 * question, so without this the switches below said "on" while nothing could ever arrive.
 */
@Composable
internal fun NotificationsOffCard() {
    val s = LocalStrings.current
    val context = LocalContext.current
    Column {
        Text(
            s.notifyOffTitle,
            style = MaterialTheme.typography.titleMedium,
            color = Tone.text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        ActionRow(
            title = s.notifyOpenSettings,
            subtitle = s.notifyOffBody,
            icon = TmIcons.Settings,
            searchable = false,
            onClick = { NotificationAsk.openSettings(context) },
        )
    }
}
