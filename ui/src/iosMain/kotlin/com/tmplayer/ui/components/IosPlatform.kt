package com.tmplayer.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import com.tmplayer.data.DeviceForm
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType
import platform.UIKit.UISelectionFeedbackGenerator

/**
 * iPhone and iPad are both the touch form: the phone layout, which already adapts to wide windows
 * (Split View, Stage Manager) as Android's does on tablets.
 */
@Composable
@ReadOnlyComposable
fun deviceForm(): DeviceForm = DeviceForm.Phone

/** The name of an icon-only control on a long press, as on an Android phone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconTip(
    label: String,
    modifier: Modifier = Modifier,
    shortcut: String? = null,
    side: TipSide = TipSide.Above,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            if (side == TipSide.Below) TooltipAnchorPosition.Below else TooltipAnchorPosition.Above,
        ),
        tooltip = { TipBubble(label, shortcut) },
        state = rememberTooltipState(),
        modifier = modifier,
        content = content,
    )
}

/** Taptic Engine feedback for each kind of moment the shared screens mark. */
@Composable
fun rememberHaptics(): (Haptic) -> Unit = IosHaptics

/**
 * [rememberHaptics] for a screen built of Views (the player), as on Android: plays [event] when
 * [enabled] ("Vibrate on tap"); every iPhone has the Taptic Engine, an iPad answers nothing. True
 * when something was played.
 */
fun android.view.View.haptic(event: Haptic, enabled: Boolean = Micro.haptics): Boolean {
    if (!Haptics.allowed(enabled, television = false, hasVibrator = true)) return false
    IosHaptics(event)
    return true
}

private val IosHaptics: (Haptic) -> Unit = { haptic ->
    when (haptic) {
        Haptic.Tap, Haptic.KeyTap, Haptic.GestureStart, Haptic.GestureEnd ->
            UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleLight).impactOccurred()
        Haptic.LongPress -> UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium).impactOccurred()
        Haptic.Confirm -> UINotificationFeedbackGenerator().notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
        Haptic.Reject -> UINotificationFeedbackGenerator().notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeError)
        Haptic.ToggleOn, Haptic.ToggleOff, Haptic.Tick, Haptic.FrequentTick -> UISelectionFeedbackGenerator().selectionChanged()
    }
}
