// Generated from core/src/iosStandIns/AndroidDefaults.kt.template for :ios's packages.
// Do not edit here: edit the template and run core/src/iosStandIns/generate-defaults.sh.

package com.tmplayer.ui.downloads

// Compose hands Android's screens its KeyEvent as nativeKeyEvent; on iOS that is not one, so what
// they read off it answers as for a single, fresh key press.

internal val Any.repeatCount: Int get() = (this as? android.view.KeyEvent)?.repeatCount ?: 0

internal val Any.eventTime: Long get() = (this as? android.view.KeyEvent)?.eventTime ?: 0L
