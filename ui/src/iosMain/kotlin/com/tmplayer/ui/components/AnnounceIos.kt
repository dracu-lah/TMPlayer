package com.tmplayer.ui.components

import platform.UIKit.UIAccessibilityAnnouncementNotification
import platform.UIKit.UIAccessibilityPostNotification

/**
 * VoiceOver's own announcement, posted from the main thread (a composition effect runs there),
 * since a Compose live region is not known to reach VoiceOver (LS-33).
 */
internal fun platformAnnounce(text: String) {
    if (text.isEmpty()) return
    UIAccessibilityPostNotification(UIAccessibilityAnnouncementNotification, text)
}
