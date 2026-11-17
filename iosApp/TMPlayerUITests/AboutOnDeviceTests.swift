import XCTest

/// Walks to the screens the iOS variant changes (gradle/ios-apple-variant.gradle.kts): the links,
/// notes and device names, on a real iPhone or iPad, and keeps pictures of them in the test's results: Settings and its About
/// and help page with the privacy note, the About screen from top to bottom, and Report a problem.
/// The support card is reached without taps (`-openScreen support`).
///
/// Run it on a signed in device, unlocked:
/// xcodebuild test -project iosApp/TMPlayer.xcodeproj -scheme TMPlayerDeviceCheck \
///   -destination 'id=<device>' -only-testing:TMPlayerUITests/AboutOnDeviceTests \
///   DEVELOPMENT_TEAM=<team> -allowProvisioningUpdates
/// The app's language is the one the pictures show: the labels below are looked for in English.
final class AboutOnDeviceTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = true
    }

    func testAboutScreens() {
        let app = XCUIApplication()
        app.launchArguments = ["-mpvAudio", "no"]
        app.launch()
        // A debug build takes a while to its first frame.
        sleep(20)

        // The account's picture, the last of the four tabs at the foot, opens a menu whose last row
        // is Settings: Compose's tabs are not buttons to iOS, so the picture is tapped by its place.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.82, dy: 0.89)).tap()
        sleep(2)
        keep(app, "account-menu")
        guard open(app, "Settings") else {
            XCTFail("No Settings in the account's menu")
            return
        }
        keep(app, "settings")

        if open(app, "About and help") {
            keep(app, "about-and-help")
            for page in 1...2 {
                app.swipeUp()
                sleep(1)
                keep(app, "about-and-help-\(page)")
            }
        }
        // Report a problem is higher up the same page: back to its top first.
        for _ in 0..<6 { app.swipeDown() }
        sleep(1)
        if open(app, "Report a problem") {
            keep(app, "report-a-problem")
            if let close = find(app, "Close"), close.isHittable { close.tap() }
            sleep(1)
        } else {
            XCTFail("No Report a problem row on the About and help page")
        }
        guard open(app, "About TMPlayer") else {
            XCTFail("No About TMPlayer row in Settings")
            return
        }
        keep(app, "about-0")
        for page in 1...5 {
            app.swipeUp()
            sleep(1)
            keep(app, "about-\(page)")
        }
    }

    /// The element whose label is [label], if it is on the screen.
    private func find(_ app: XCUIApplication, _ label: String) -> XCUIElement? {
        let element = app.descendants(matching: .any).matching(NSPredicate(format: "label ==[c] %@", label)).firstMatch
        return element.waitForExistence(timeout: 2) ? element : nil
    }

    /// Scrolls until the row called [label] can be tapped, then taps it.
    private func open(_ app: XCUIApplication, _ label: String) -> Bool {
        for _ in 0..<8 {
            if let row = find(app, label), row.isHittable {
                // A tap while the list still glides only stops it: let it settle first.
                sleep(2)
                row.tap()
                sleep(3)
                return true
            }
            app.swipeUp()
            sleep(1)
        }
        return false
    }

    /// A picture of the screen now, kept in the test's results under [name].
    private func keep(_ app: XCUIApplication, _ name: String) {
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }
}
