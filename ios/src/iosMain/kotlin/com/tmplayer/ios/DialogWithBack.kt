package com.tmplayer.ios

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Compose's Dialog, plus the Back that closes it. On Android the dialog's window takes Back itself
 * (dismissOnBackPress); on iOS the swipe from the left edge reaches the screens' back stack only, so
 * the dialog registers there, ahead of the page under it and behind anything inside it. :app's
 * screens are compiled with this in place of androidx.compose.ui.window.Dialog (ios/build.gradle.kts).
 */
@Composable
fun DialogWithBack(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        com.tmplayer.ui.nav.BackHandler(enabled = properties.dismissOnBackPress) { onDismissRequest() }
        content()
    }
}
