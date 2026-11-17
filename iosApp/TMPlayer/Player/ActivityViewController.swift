import TMPlayerKit
import UIKit

/// An Android activity built from Views (upstream's PlayerActivity) full screen over the app's
/// screens, as ActivityScreen (Kotlin) runs it: the activity's lifecycle follows this screen
/// coming and going, the orientations are the ones it asks for, and its finish takes the screen
/// away. The status bar shows and the home indicator stays while the activity shows Android's
/// system bars (the player's row is up), and they go when it hides them.
final class ActivityViewController: UIViewController {
    private let screen: ActivityScreen
    private var appeared = false
    private weak var leavingScene: UIWindowScene?
    private weak var under: UIViewController?

    /// The way the screens were turned as the player came up, which stands for the way the device
    /// is held until iOS has a reading of its own (it answers unknown at first, and lying flat),
    /// as Android's sensor keeps its last way up.
    private let heldAtOpen: UIInterfaceOrientationMask?

    init(screen: ActivityScreen) {
        self.screen = screen
        // The app's scene, active or still coming up (a player opened as the app starts).
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let scene = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
        heldAtOpen = scene.flatMap { Self.mask(of: $0.interfaceOrientation) }
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .fullScreen
    }

    required init?(coder: NSCoder) {
        nil
    }

    /// Every way while the player comes up, then the activity's. Presented while it allowed only
    /// landscape (the player asks that before it knows the video's shape), iOS turned the player
    /// alone and left the scene upright: the scene still said portrait, so when a tall video then
    /// asked for the phone's way, upright, iOS saw nothing to turn and the video stayed sideways
    /// (iPhone 13, 2026-11-12). Asked once the player is up ([viewDidAppear]), the scene turns.
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask {
        appeared && !engineStarting ? asked : .all
    }

    /// True from the player's creation, and again whenever its engine opens a file, until the engine
    /// shows the first frame (or gives up): the screen is not turned meanwhile. Turned while mpv was
    /// making its first frame, the video never started now and then, the player stuck on Buffering
    /// (iPhone 13, a wide video opened upright, 2026-11-13); turned once it plays, it never failed.
    /// [startGrace] lets the turn go ahead anyway should no word come.
    private var engineStarting = true
    private var startGrace: DispatchWorkItem?
    private var engineWatch: [NSObjectProtocol] = []

    private func engineStartingChanged(_ starting: Bool) {
        startGrace?.cancel()
        engineStarting = starting
        if starting {
            let grace = DispatchWorkItem { [weak self] in self?.engineStartingChanged(false) }
            startGrace = grace
            DispatchQueue.main.asyncAfter(deadline: .now() + 3, execute: grace)
        } else if appeared {
            applyOrientations()
        }
    }

    /// What an iPad in a window can do for the player. iPadOS turns no window for an app there
    /// ("The current windowing mode does not allow for programmatic changes to interface
    /// orientation"), but since iPadOS 26 it keeps one from turning when asked. So where the
    /// activity limits the ways (a wide video's landscape, the player's lock) and the screen
    /// already stands in one of them, it stays: tipping the iPad does not turn a wide video
    /// upright, as an Android tablet keeps it sideways. On an iPhone the asked orientations do it.
    override var prefersInterfaceOrientationLocked: Bool {
        guard appeared, asked != .all,
              let now = view.window?.windowScene?.effectiveGeometry.interfaceOrientation,
              let mask = Self.mask(of: now) else { return false }
        return asked.contains(mask)
    }

    override var prefersStatusBarHidden: Bool { screen.systemBarsHidden }
    override var prefersHomeIndicatorAutoHidden: Bool { screen.systemBarsHidden }
    override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }

    /// The keyboard's keys are the activity's while it is in front, as on Android: iOS hands them
    /// to the first responder, which would otherwise stay the screens' view behind the player.
    override var canBecomeFirstResponder: Bool { true }

    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        for press in presses {
            if let key = press.key { _ = screen.keyDown(usage: Int64(key.keyCode.rawValue)) }
        }
    }

    override func pressesEnded(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        for press in presses {
            if let key = press.key { _ = screen.keyUp(usage: Int64(key.keyCode.rawValue)) }
        }
    }

    override func pressesCancelled(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        pressesEnded(presses, with: event)
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        let content = screen.controller()
        addChild(content)
        content.view.frame = view.bounds
        content.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        content.view.backgroundColor = .clear
        view.addSubview(content.view)
        content.didMove(toParent: self)
        screen.onFinish = { [weak self] in self?.dismiss(animated: true) }
        engineStartingChanged(true)
        engineWatch = [
            NotificationCenter.default.addObserver(forName: .videoEngineStarting, object: nil, queue: .main) { [weak self] _ in
                self?.engineStartingChanged(true)
            },
            NotificationCenter.default.addObserver(forName: .videoEngineStarted, object: nil, queue: .main) { [weak self] _ in
                self?.engineStartingChanged(false)
            },
        ]
        screen.onOrientation = { [weak self] in self?.applyOrientations() }
        screen.onSystemBars = { [weak self] in
            self?.setNeedsStatusBarAppearanceUpdate()
            self?.setNeedsUpdateOfHomeIndicatorAutoHidden()
        }
    }

    /// The status bar coming or going with the player's row moves the safe area without a change
    /// of size: the activity's views are told, as Android hands them the bars' insets.
    override func viewSafeAreaInsetsDidChange() {
        super.viewSafeAreaInsetsDidChange()
        screen.safeAreaChanged()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        guard !appeared else { return }
        appeared = true
        becomeFirstResponder()
        applyOrientations()
        screen.shown()
    }

    /// The orientations the activity asks for.
    private var asked: UIInterfaceOrientationMask {
        UIInterfaceOrientationMask(rawValue: UInt(screen.orientations))
    }

    /// The screen turned to what the activity asks for, where iOS allows it (not in every iPad
    /// window). Among several, the way the device is held now, as Android's sensor turns the
    /// screen at once: a tall video asking to follow the phone turns an upright phone upright.
    private func applyOrientations() {
        // Asked again once the engine shows its first frame ([engineStartingChanged]).
        if engineStarting { return }
        let scene = view.window?.windowScene
        tmLog(
            "orientation", "asked %lu wanted %lu device %ld scene %ld geometry %ld bounds %@ window %@",
            asked.rawValue, wanted().rawValue, UIDevice.current.orientation.rawValue,
            scene?.interfaceOrientation.rawValue ?? -1, scene?.effectiveGeometry.interfaceOrientation.rawValue ?? -1,
            NSCoder.string(for: view.bounds.size), NSCoder.string(for: view.window?.bounds.size ?? .zero)
        )
        setNeedsUpdateOfSupportedInterfaceOrientations()
        setNeedsUpdateOfPrefersInterfaceOrientationLocked()
        view.window?.windowScene?.requestGeometryUpdate(.iOS(interfaceOrientations: wanted())) { error in
            tmLogWarning("orientation", "%@", error.localizedDescription)
        }
    }

    /// Whether the screen stands in one of [ways], by its shape.
    private func stands(in ways: UIInterfaceOrientationMask) -> Bool {
        let size = view.bounds.size
        guard size.width > 0, size.height > 0 else { return true }
        return size.width > size.height ? !ways.isDisjoint(with: .landscape) : !ways.isDisjoint(with: [.portrait, .portraitUpsideDown])
    }

    /// What the activity asks for; among several, the way the device is held.
    private func wanted() -> UIInterfaceOrientationMask {
        let allowed = asked
        return (Self.deviceOrientation() ?? heldAtOpen).flatMap { allowed.contains($0) ? $0 : nil } ?? allowed
    }

    /// A request made while the screen was turning (a tall video's answer arriving during the
    /// player's first turn sideways) is not acted on by iOS: once a turn is over, the screen is
    /// asked again if it does not stand as the activity wants.
    override func viewWillTransition(to size: CGSize, with coordinator: UIViewControllerTransitionCoordinator) {
        super.viewWillTransition(to: size, with: coordinator)
        coordinator.animate(alongsideTransition: nil) { [weak self] _ in
            guard let self else { return }
            tmLog("orientation", "turned to %@, stands as wanted %d", NSCoder.string(for: self.view.bounds.size), self.stands(in: self.wanted()) ? 1 : 0)
            // An iPad turned by hand into a way the activity asks for stays there from now on.
            self.setNeedsUpdateOfPrefersInterfaceOrientationLocked()
            guard self.appeared, !self.stands(in: self.wanted()) else { return }
            self.applyOrientations()
        }
    }

    private static func mask(of orientation: UIInterfaceOrientation) -> UIInterfaceOrientationMask? {
        switch orientation {
        case .portrait: return .portrait
        case .portraitUpsideDown: return .portraitUpsideDown
        case .landscapeLeft: return .landscapeLeft
        case .landscapeRight: return .landscapeRight
        default: return nil
        }
    }

    /// The way the device is held, as an interface orientation; nil lying flat or unknown.
    private static func deviceOrientation() -> UIInterfaceOrientationMask? {
        switch UIDevice.current.orientation {
        case .portrait: return .portrait
        case .portraitUpsideDown: return .portraitUpsideDown
        // The device turned to its left shows the interface's right side up, and the other way.
        case .landscapeLeft: return .landscapeRight
        case .landscapeRight: return .landscapeLeft
        default: return nil
        }
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        UIDevice.current.beginGeneratingDeviceOrientationNotifications()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if isBeingDismissed {
            leavingScene = view.window?.windowScene
            under = presentingViewController
        }
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        guard isBeingDismissed else { return }
        screen.onFinish = {}
        screen.onOrientation = {}
        engineWatch.forEach(NotificationCenter.default.removeObserver)
        engineWatch = []
        startGrace?.cancel()
        screen.onSystemBars = {}
        screen.closed()
        turnToTheDevice(leavingScene)
        UIDevice.current.endGeneratingDeviceOrientationNotifications()
        // The screens behind take the keyboard back (Esc as their Back).
        _ = Self.giveKeys(to: under?.view)
    }

    /// The first view in [view]'s tree that can take the keyboard, made its first responder.
    private static func giveKeys(to view: UIView?) -> Bool {
        guard let view else { return false }
        if view.canBecomeFirstResponder { return view.becomeFirstResponder() }
        for sub in view.subviews.reversed() where giveKeys(to: sub) { return true }
        return false
    }

    /// The screens under the player follow the way the device is held again, as Android's activity
    /// under the player turns back to the sensor once the player's own orientation is gone. Lying
    /// flat or unknown, the screen stays as it is, as Android's does.
    private func turnToTheDevice(_ scene: UIWindowScene?) {
        guard let scene, let mask = Self.deviceOrientation() else { return }
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask)) { _ in }
    }
}
