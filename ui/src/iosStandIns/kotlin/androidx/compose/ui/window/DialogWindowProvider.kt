package androidx.compose.ui.window

/** Android's handle on a dialog's window; no dialog on iOS is one, so the cast never succeeds. */
interface DialogWindowProvider {
    val window: android.view.Window
}
