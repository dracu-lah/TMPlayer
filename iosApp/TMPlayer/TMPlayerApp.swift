import SwiftUI
import TMPlayerKit

/// The app's entry point. Everything on screen is Android's app, from the Kotlin framework: its
/// Compose screens and its player's Views. SwiftUI only hosts it, with the pieces iOS gives no
/// choice about (the video engine, the audio session).
@main
struct TMPlayerApp: App {
    init() {
        // The engine every ExoPlayer of Android's player plays on (docs/IOS.md).
        Engines.shared.install { VideoEngines.make() }
        // The sheet an in app trailer plays in (Player/TrailerSheet.swift); nothing loads until one is tapped.
        Trailers.shared.install(presenter: TrailerSheetPresenterImpl())
        // `-memoryWarningAfter <s>` (a developer's check): iOS's memory warning that many seconds
        // in, as UIKit posts it, to see what the app does with one.
        let after = UserDefaults.standard.integer(forKey: "memoryWarningAfter")
        if after > 0 {
            DispatchQueue.main.asyncAfter(deadline: .now() + .seconds(after)) {
                NotificationCenter.default.post(name: UIApplication.didReceiveMemoryWarningNotification, object: UIApplication.shared)
            }
        }
    }

    var body: some Scene {
        WindowGroup {
            ComposeHost()
                // Compose pads for the safe areas and the keyboard itself.
                .ignoresSafeArea()
        }
    }
}

private struct ComposeHost: UIViewControllerRepresentable {
    func makeCoordinator() -> Launcher {
        Launcher()
    }

    func makeUIViewController(context: Context) -> UIViewController {
        let controller = MainViewControllerKt.MainViewController(launcher: context.coordinator)
        context.coordinator.host = controller
        return controller
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}

    /// Opens the player from the app's screens, full screen over them.
    final class Launcher: NSObject, PlayerLauncher {
        weak var host: UIViewController?

        func audioReady(then: @escaping () -> Void) {
            DispatchQueue.global(qos: .userInitiated).async {
                PlaybackAudio.activate()
                DispatchQueue.main.async { then() }
            }
        }

        /// Upstream's PlayerActivity, full screen over the screens.
        func present(screen: ActivityScreen) {
            host?.present(ActivityViewController(screen: screen), animated: true)
        }
    }
}
