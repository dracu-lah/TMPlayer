package com.tmplayer.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/**
 * A view model that lives exactly as long as this point of the composition does for [key].
 *
 * The desktop has no activity to own a `ViewModelStore`, so each call keeps one of its own and
 * clears it on leaving, which cancels the model's `viewModelScope` just as Android does when a
 * screen is popped. A new [key] (another chat, other size limits) is a new model.
 */
@Composable
inline fun <reified VM : ViewModel> rememberViewModel(key: Any?, noinline create: () -> VM): VM {
    val store = remember(key) { ViewModelStore() }
    DisposableEffect(store) { onDispose { store.clear() } }
    return remember(store) {
        ViewModelProvider.create(store, viewModelFactory { initializer { create() } })[VM::class]
    }
}
