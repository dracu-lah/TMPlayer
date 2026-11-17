package com.tmplayer.widget

import android.content.Context
import kotlinx.coroutines.CoroutineScope

// The home screen widget on iOS, in place of upstream's widget package (an Android app widget
// provider and its remote views). iOS widgets are a separate extension target, which the app does
// not have, so nothing is shown and these two calls do nothing. The names are upstream's, so
// App compiles unchanged.

object WidgetAvailability {
    @Suppress("UNUSED_PARAMETER")
    fun sync(context: Context) = Unit
}

object ContinueWidgetFollower {
    @Suppress("UNUSED_PARAMETER")
    fun start(context: Context, scope: CoroutineScope) = Unit
}
