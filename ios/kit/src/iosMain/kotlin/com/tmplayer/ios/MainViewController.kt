package com.tmplayer.ios

import platform.UIKit.UIViewController

/**
 * The app's one view controller, which the SwiftUI entry point in iosApp/ hosts: TMPlayer's own
 * shell and screens ([ActivityShell]). The same on iPhone and iPad: the layout follows the
 * window's size, never the device type.
 */
@Suppress("FunctionName", "unused") // Called from Swift as MainViewControllerKt.MainViewController(launcher:).
fun MainViewController(launcher: PlayerLauncher): UIViewController = ActivityShell.controller(launcher)
