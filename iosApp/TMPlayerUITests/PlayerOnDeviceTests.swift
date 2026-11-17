import XCTest

/// Drives the app on a real iPhone or iPad the way a viewer would, for checks that would otherwise
/// need someone at the device (docs/IOS.md): it opens the first video on Home in
/// the player and keeps pictures of what the screen shows in the test's results.
///
/// Run it on a signed in device, with the device unlocked:
/// xcodebuild test -project iosApp/TMPlayer.xcodeproj -scheme TMPlayerDeviceCheck \
///   -destination 'id=<device>' DEVELOPMENT_TEAM=<team> -allowProvisioningUpdates
/// It plays that video in the account's app, as a viewer would.
///
/// The `testPlaying...` passes drive a video already playing in another app, the release a viewer
/// installed for one, named by the runner's `TM_TARGET`, while `devicectl ... --console` reads its
/// log. On a free Apple ID (three developer apps a device) the runner alone fits: build with
/// `build-for-testing`, then in the `.xctestrun` point `UITargetAppPath` at the runner itself and
/// drop TMPlayer.app from `DependentProductPaths`, and run `test-without-building` with it. The
/// first run asks on the device to allow UI automation (Touch ID or the code).
final class PlayerOnDeviceTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = true
    }

    func testPlayerLoader() throws {
        let app = XCUIApplication()
        app.launch()
        // A debug build takes a while to its first frame.
        sleep(25)
        keep(app, "home")

        // The first card of Home's first row: placed by the window's shape, as the cards carry
        // no names iOS can find.
        let wide = app.windows.firstMatch.frame.width > app.windows.firstMatch.frame.height
        let firstCard = wide ? CGVector(dx: 0.142, dy: 0.312) : CGVector(dx: 0.19, dy: 0.326)
        app.coordinate(withNormalizedOffset: firstCard).tap()
        sleep(4)
        keep(app, "details")

        let play = app.buttons.matching(NSPredicate(format: "label ==[c] 'Play' OR label ==[c] 'Riproduci'")).firstMatch
        guard play.waitForExistence(timeout: 5) else {
            XCTFail("No Play button on the page opened from Home's first card")
            return
        }
        play.tap()
        // The loader, as it comes up and while the stream starts.
        for frame in 0..<12 {
            usleep(700_000)
            keep(app, "loader-\(frame)")
        }
        sleep(4)
        keep(app, "playing")
    }

    /// The controls of a video already playing, in the app named by the runner's `TM_TARGET`
    /// (`TEST_RUNNER_TM_TARGET=<bundle id>` on xcodebuild; this build's app otherwise), opened
    /// beforehand with `devicectl device process launch --console ... -playFile <name>` so its log
    /// can be read meanwhile. This first pass shows the controls and keeps what iOS sees of them.
    func testPlayingControlsSeen() throws {
        let app = target()
        app.activate()
        sleep(2)
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        sleep(1)
        keep(app, "controls")
        let tree = XCTAttachment(string: app.debugDescription)
        tree.name = "controls-tree"
        tree.lifetime = .keepAlways
        add(tree)
    }

    /// What the target app shows now and what iOS sees of it (the names a test can tap by), with
    /// no tap; `TM_STEPS` (labels joined by `|`) taps each label first, in order, where it is found:
    /// the whole label or its beginning, or `type:<text>`, `swipe:up`, `swipe:down`.
    func testScreenSeen() throws {
        let app = target()
        app.activate()
        sleep(2)
        let steps = (ProcessInfo.processInfo.environment["TM_STEPS"] ?? "").split(separator: "|").map(String.init)
        for (index, label) in steps.enumerated() {
            // `type:<text>` types into the field in focus; `swipe:up` and `swipe:down` scroll.
            if label.hasPrefix("type:") {
                app.typeText(String(label.dropFirst(5)))
                sleep(3)
                keep(app, "step-\(index + 1)")
                continue
            }
            if label == "swipe:up" || label == "swipe:down" {
                if label == "swipe:up" { app.swipeUp() } else { app.swipeDown() }
                sleep(2)
                keep(app, "step-\(index + 1)")
                continue
            }
            // The label itself, or one that begins with it (a row's label carries its details too).
            let exact = app.descendants(matching: .any).matching(NSPredicate(format: "label ==[c] %@", label)).firstMatch
            let element = exact.exists ? exact : app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH[c] %@", label)).firstMatch
            if element.waitForExistence(timeout: 5), element.isHittable {
                element.tap()
            } else {
                XCTFail("No \(label) to tap")
            }
            sleep(3)
            keep(app, "step-\(index + 1)")
        }
        keep(app, "screen")
        let tree = XCTAttachment(string: app.debugDescription)
        tree.name = "screen-tree"
        tree.lifetime = .keepAlways
        add(tree)
    }

    /// The second pass: each control of the bar at the foot's start, and the menu at the top's end,
    /// tapped by its place in the landscape window (Compose's controls carry no names iOS sees),
    /// with a picture after each and the sheet it opened closed again.
    func testPlayingControlsTapped() throws {
        let app = target()
        app.activate()
        sleep(2)
        let places: [(String, CGVector)] = [
            ("bar-1", CGVector(dx: 0.045, dy: 0.92)),
            ("bar-2", CGVector(dx: 0.088, dy: 0.92)),
            ("bar-3", CGVector(dx: 0.132, dy: 0.92)),
            ("more", CGVector(dx: 0.975, dy: 0.04)),
        ]
        for (name, place) in places {
            showControls(app)
            app.coordinate(withNormalizedOffset: place).tap()
            sleep(2)
            keep(app, name)
            // A sheet closes with a tap above it; with none open, the tap hides the controls.
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.12)).tap()
            sleep(2)
            keep(app, "\(name)-closed")
        }
    }

    /// What a viewer does while watching, for the log's sync lines to be read against: the speed
    /// back to 1x, a pause of 8 s, a skip forward and one back on the bar, the sound to English
    /// and back to Italian. Places as in the landscape window of an iPad (1180 by 820), the first
    /// sound tracks Italian and English, as the test video has them. The controls hide by
    /// themselves while playing, so each step waits for that before showing them again.
    func testPlayingControlsExercised() throws {
        let app = target()
        app.activate()
        sleep(2)
        let pausePlay = CGVector(dx: 0.5, dy: 0.5)
        let bar = { (fraction: CGFloat) in CGVector(dx: 0.03 + 0.94 * fraction, dy: 0.856) }

        step(app, "speed-menu") { app.coordinate(withNormalizedOffset: CGVector(dx: 0.132, dy: 0.92)).tap() }
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.325, dy: 0.333)).tap()
        sleep(2)
        keep(app, "speed-1x")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.68, dy: 0.89)).tap()

        step(app, "paused") { app.coordinate(withNormalizedOffset: pausePlay).tap() }
        sleep(8)
        app.coordinate(withNormalizedOffset: pausePlay).tap()
        sleep(3)
        keep(app, "resumed")

        step(app, "skipped-forward") { app.coordinate(withNormalizedOffset: bar(0.8)).tap() }
        step(app, "skipped-back") { app.coordinate(withNormalizedOffset: bar(0.3)).tap() }

        for (name, row) in [("english", CGVector(dx: 0.38, dy: 0.48)), ("italian", CGVector(dx: 0.38, dy: 0.405))] {
            step(app, "audio-menu-\(name)") { app.coordinate(withNormalizedOffset: CGVector(dx: 0.088, dy: 0.92)).tap() }
            app.coordinate(withNormalizedOffset: row).tap()
            sleep(2)
            keep(app, "audio-\(name)")
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.68, dy: 0.68)).tap()
            sleep(8)
            keep(app, "audio-\(name)-playing")
        }
    }

    /// Subtitles turned off in their picker ("No", its first row), and the picture kept a while.
    func testSubtitlesTurnedOff() throws {
        let app = target()
        app.activate()
        sleep(2)
        step(app, "subtitles-menu") { app.coordinate(withNormalizedOffset: CGVector(dx: 0.045, dy: 0.92)).tap() }
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.327, dy: 0.187)).tap()
        sleep(3)
        keep(app, "subtitles-off")
        sleep(15)
        keep(app, "subtitles-off-later")
    }

    /// No taps: a picture every 5 s for 40 s, for what the video shows by itself.
    func testWatchedAWhile() throws {
        let app = target()
        app.activate()
        for at in 0..<8 {
            keep(app, "seen-\(at * 5)")
            sleep(5)
        }
    }

    /// Waits for the controls to hide, shows them, does [action], and keeps a picture after it.
    private func step(_ app: XCUIApplication, _ name: String, _ action: () -> Void) {
        sleep(6)
        showControls(app)
        action()
        sleep(3)
        keep(app, name)
    }

    /// The controls on screen: a tap away from the play button when they are hidden.
    private func showControls(_ app: XCUIApplication) {
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.4)).tap()
        sleep(1)
    }

    private func target() -> XCUIApplication {
        if let bundle = ProcessInfo.processInfo.environment["TM_TARGET"], !bundle.isEmpty {
            return XCUIApplication(bundleIdentifier: bundle)
        }
        return XCUIApplication()
    }

    /// A picture of the screen now, kept in the test's results under [name].
    private func keep(_ app: XCUIApplication, _ name: String) {
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }
}
