import XCTest

/// What the app costs on a real iPhone or iPad while a viewer uses it, measured by XCTest on the
/// app's own process the same way for every build compared: CPU time and memory while Home and the
/// chat list scroll under a thumb, and while a video plays. Each is repeated, and xcodebuild prints
/// the values and their average. The start is measured by the app itself (`-perfLog`): XCTest's
/// metrics on an app launched inside the measurement gave impossible values (2026-11-13).
///
/// Run it on a signed in device, unlocked:
/// xcodebuild test -project iosApp/TMPlayer.xcodeproj -scheme TMPlayerDeviceCheck \
///   -destination 'id=<device>' -only-testing:TMPlayerUITests/ResourcesOnDeviceTests \
///   DEVELOPMENT_TEAM=<team> -allowProvisioningUpdates
/// The video is a file of one's own in the app's Documents (`perf-1080.mp4`, as `-playFile` takes
/// it, copied there with `xcrun devicectl device copy to`), played without sound.
final class ResourcesOnDeviceTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = true
    }

    private func metrics(_ app: XCUIApplication) -> [XCTMetric] {
        [XCTCPUMetric(application: app), XCTMemoryMetric(application: app)]
    }

    private func options(_ count: Int) -> XCTMeasureOptions {
        let options = XCTMeasureOptions()
        options.iterationCount = count
        return options
    }

    /// Home scrolled down through its rows and back up, as a thumb would.
    func testHomeScroll() {
        let app = XCUIApplication()
        app.launch()
        // A debug build takes a while to its first frame, and Home to its rows.
        sleep(20)
        measure(metrics: metrics(app), options: options(5)) {
            for _ in 0..<3 { app.swipeUp(velocity: .fast) }
            for _ in 0..<3 { app.swipeDown(velocity: .fast) }
        }
    }

    /// The chat list scrolled down and back up.
    func testChatsScroll() {
        let app = XCUIApplication()
        app.launch()
        sleep(20)
        // The tab by its name where iOS sees it, otherwise by its place in the bar (the second of
        // four, at the foot): Compose's tabs are not buttons to iOS.
        let chats = app.descendants(matching: .any).matching(NSPredicate(format: "label ==[c] 'Chats' OR label ==[c] 'Chat'")).firstMatch
        if chats.waitForExistence(timeout: 5) {
            chats.tap()
        } else {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.39, dy: 0.89)).tap()
        }
        sleep(5)
        measure(metrics: metrics(app), options: options(5)) {
            for _ in 0..<3 { app.swipeUp(velocity: .fast) }
            for _ in 0..<3 { app.swipeDown(velocity: .fast) }
        }
    }

    /// A 1080p video playing, without sound, once it has started.
    func testPlayback() {
        let app = XCUIApplication()
        app.launchArguments = ["-playFile", "perf-1080.mp4", "-mpvAudio", "no"]
        app.launch()
        sleep(12)
        measure(metrics: metrics(app), options: options(3)) {
            sleep(10)
        }
    }
}
