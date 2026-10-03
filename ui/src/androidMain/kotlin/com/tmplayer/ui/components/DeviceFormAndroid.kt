package com.tmplayer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import com.tmplayer.data.DeviceForm
import com.tmplayer.data.FormFactor

/** What kind of screen this is: on Android, [FormFactor]'s answer, a phone or a television. */
@Composable
@ReadOnlyComposable
fun deviceForm(): DeviceForm = FormFactor.form(LocalContext.current)
