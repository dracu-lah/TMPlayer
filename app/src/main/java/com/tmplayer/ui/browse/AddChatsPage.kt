package com.tmplayer.ui.browse

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tmplayer.data.SettingsStore
import com.tmplayer.ui.components.TvSearchField
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.settings.SendLogsButton

/**
 * The Add chats picker (HX27) on the phone and the television, over the shell's chat list.
 *
 * [run] is how many times the picker has been opened. The view model is kept under it, so a
 * rotation in the middle of a choice keeps its ticks and the next opening starts from the store.
 */
@Composable
internal fun AddChatsPage(
    settings: SettingsStore,
    chatsViewModel: ChatListViewModel,
    run: Int,
    onClose: () -> Unit,
) {
    val model: AddChatsViewModel = viewModel(
        key = "add-chats-$run",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = newAddChatsViewModel(settings) as T
        },
    )
    AddChatsFed(
        settings = settings,
        chatsViewModel = chatsViewModel,
        model = model,
        onClose = onClose,
        sendLogs = { SendLogsButton() },
        // A remote and a sofa get the television's own search box, which opens its keyboard on a press.
        searchField = if (isTouch()) null else { value, onValueChange, placeholder, modifier ->
            TvSearchField(value = value, onValueChange = onValueChange, placeholder = placeholder, modifier = modifier)
        },
    )
}
