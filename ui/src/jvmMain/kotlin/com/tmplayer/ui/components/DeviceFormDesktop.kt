package com.tmplayer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import com.tmplayer.data.DeviceForm

/** What kind of screen this is: on the JVM, always a desktop with a mouse and a keyboard. */
@Composable
@ReadOnlyComposable
fun deviceForm(): DeviceForm = DeviceForm.Desktop
