package com.tmplayer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import com.tmplayer.data.DeviceForm

/**
 * True when this device is driven by a finger or a pointer rather than by a remote.
 *
 * Read it for layout rather than for behaviour. What a control does when it is operated belongs
 * inside that control, so that the two halves of the app cannot drift apart; what a pane looks
 * like is a decision each pane has to make for itself. The desktop answers true: it shares the
 * phone's Material 3 layouts, and where it differs from the phone it asks [deviceForm] instead.
 */
@Composable
@ReadOnlyComposable
fun isTouch(): Boolean = deviceForm() != DeviceForm.Tv
