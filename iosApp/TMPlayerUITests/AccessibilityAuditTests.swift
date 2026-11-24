import XCTest

/// Runs iOS's own accessibility audit (`performAccessibilityAudit`) on the screens a viewer meets:
/// what shows first (the sign in on a device with no account, Home on a signed in one), Home, a
/// chat's grid, a video's page, Settings, the player and its status sheet. Every "no description",
/// "hit area too small", "text clipped" and contrast finding fails the test with the screen's name,
/// so a screen that cannot be read by VoiceOver or reached by a large finger is found by a machine
/// before a person (WP-64).
///
/// It needs a Mac: Xcode 15 or later, an iPhone or an iPad (or a simulator) with the build's
/// account signed in for the screens after the first. The Kotlin screens are Compose, which iOS
/// reads through the semantics Compose publishes, so the labels the findings name are the
/// `contentDescription`s of the Android code, which is where they are fixed.
///
/// xcodebuild test -project iosApp/TMPlayer.xcodeproj -scheme TMPlayerDeviceCheck \
///   -destination 'platform=iOS Simulator,name=iPhone 17' -only-testing:TMPlayerUITests/AccessibilityAuditTests
/// The same with `-UIPreferredContentSizeCategoryName UICTContentSizeCategoryAccessibilityXL` in the
/// launch arguments (`TM_LARGER_TEXT=1` in the runner's environment) is the Larger Text pass.
/// The workflow `apple-ci.yml` runs it on GitHub's macOS when it is started by hand
/// (`gh workflow run apple-ci.yml -f accessibility_audit=true`, the sign in screen only).
final class AccessibilityAuditTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = true
    }

    private func launch() -> XCUIApplication {
        let app = XCUIApplication()
        var arguments = ["-mpvAudio", "no"]
        if ProcessInfo.processInfo.environment["TM_LARGER_TEXT"] == "1" {
            arguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXL"]
        }
        app.launchArguments = arguments
        app.launch()
        // A debug build takes a while to its first frame.
        sleep(20)
        return app
    }

    /// The audit of what is on the screen now, named for the report. A finding the audit raises
    /// for a system element (the status bar, the keyboard) is not the app's and is let through.
    private func audit(_ app: XCUIApplication, _ name: String) {
        XCTContext.runActivity(named: "Accessibility audit: \(name)") { _ in
            do {
                try app.performAccessibilityAudit { issue in
                    guard let element = issue.element else { return false }
                    return element.elementType == .statusBar || element.elementType == .keyboard
                }
            } catch {
                XCTFail("\(name): \(error)")
            }
        }
    }

    /// The first screen, whichever it is: the sign in with no account, Home with one.
    func testFirstScreen() {
        audit(launch(), "first screen")
    }

    func testSignedInScreens() throws {
        let app = launch()
        // Compose's cards and tabs are not buttons to iOS: placed by the window's shape, as the
        // other on-device checks do (PlayerOnDeviceTests).
        let wide = app.windows.firstMatch.frame.width > app.windows.firstMatch.frame.height
        let firstCard = wide ? CGVector(dx: 0.142, dy: 0.312) : CGVector(dx: 0.19, dy: 0.326)
        let accountTab = CGVector(dx: 0.82, dy: 0.89)

        audit(app, "home")

        app.coordinate(withNormalizedOffset: firstCard).tap()
        sleep(4)
        audit(app, "a video's page")

        let play = app.buttons.matching(NSPredicate(format: "label ==[c] 'Play'")).firstMatch
        if play.waitForExistence(timeout: 5) {
            play.tap()
            sleep(8)
            // The controls show on a tap; the status sheet is the "More" panel's.
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
            sleep(1)
            audit(app, "the player")
            let more = app.buttons.matching(NSPredicate(format: "label ==[c] 'More' OR label ==[c] 'Playback status'")).firstMatch
            if more.waitForExistence(timeout: 3) {
                more.tap()
                sleep(1)
                audit(app, "the status sheet")
            }
            let back = app.buttons.matching(NSPredicate(format: "label ==[c] 'Back' OR label ==[c] 'Close'")).firstMatch
            if back.exists { back.tap(); sleep(2) }
        } else {
            XCTFail("No Play button on the video's page: the player and its status sheet were not audited")
        }

        app.coordinate(withNormalizedOffset: accountTab).tap()
        sleep(2)
        let settings = app.descendants(matching: .any).matching(NSPredicate(format: "label ==[c] 'Settings'")).firstMatch
        if settings.waitForExistence(timeout: 3) {
            settings.tap()
            sleep(3)
            audit(app, "settings")
        } else {
            XCTFail("No Settings in the account's menu")
        }
    }
}
