import TMPlayerKit
import UIKit
import XCTest
@testable import TMPlayer

/// Android's player speaks Media3; on iOS its ExoPlayer plays on the app's video engine
/// (EngineExoPlayer). These drive that ExoPlayer as Android's player does, wrapped in upstream's own
/// StateChangeOnlyPlayer, on each engine VideoEngines lists, with the same 12 s sample as
/// EngineConformanceTests, and check what Media3's state and callbacks say back.
final class Media3BridgeTests: XCTestCase {
    override func setUp() {
        super.setUp()
        UserDefaults.standard.set("no", forKey: "mpvAudio")
    }

    override func tearDown() {
        UserDefaults.standard.removeObject(forKey: "mpvAudio")
        Engines.shared.install { VideoEngines.make() }
        super.tearDown()
    }

    /// TDLib's URIs reach the engine as its stream reads them; files by their path.
    func testSourcesForTheEngine() {
        XCTAssertEqual(Media3Check.companion.sourceOf(uri: "tdfile://6094?type=video%2Fx-matroska"), "tdfile://6094")
        XCTAssertEqual(Media3Check.companion.sourceOf(uri: "tdfile://12"), "tdfile://12")
        XCTAssertEqual(Media3Check.companion.sourceOf(uri: "file:///tmp/a%20b.mkv"), "/tmp/a b.mkv")
        XCTAssertEqual(Media3Check.companion.sourceOf(uri: "/tmp/plain.mkv"), "/tmp/plain.mkv")
    }

    /// Prepared with playWhenReady on: buffering, then ready and playing, with the tracks, the
    /// picture's size and the first frame told to listeners, through the wrapping player.
    func testOpensAndPlays() {
        each { check in
            check.open(uri: sample, playWhenReady: true)
            wait(15, "ready and playing") { check.snapshot().playbackState == 3 && check.snapshot().isPlaying }
            let now = check.snapshot()
            XCTAssertNil(now.error)
            XCTAssertTrue((11_500...12_500).contains(now.durationMs), "length \(now.durationMs)")
            XCTAssertEqual(now.tracks.filter { $0.type == "audio" }.count, 2, "soundtracks")
            XCTAssertEqual(now.tracks.filter { $0.type == "text" }.count, 1, "subtitle tracks")
            wait(5, "the picture's size") { check.snapshot().videoWidth == 320 }
            let heard = check.heard()
            for expected in ["state 2", "state 3", "playing true", "first frame", "size 320x180"] {
                XCTAssertTrue(heard.contains(expected), "heard \(expected): \(heard)")
            }
            XCTAssertTrue(heard.contains { $0.hasPrefix("tracks ") }, "tracks heard")
            // Upstream's StateChangeOnlyPlayer passes state changes on and drops the rest, here too.
            let wrapped = check.heardThroughWrapper()
            for expected in ["state 2", "state 3", "playing true"] {
                XCTAssertTrue(wrapped.contains(expected), "heard through the wrapper \(expected): \(wrapped)")
            }
            XCTAssertFalse(wrapped.contains { $0.hasPrefix("tracks ") || $0.hasPrefix("size ") || $0 == "first frame" }, "the wrapper's filter")
            XCTAssertFalse((heard + wrapped).contains("wrong player"), "each listener told about the player it was added to")
        }
    }

    /// Prepared with playWhenReady off: ready, paused, not moving; play starts it.
    func testPreparesPaused() {
        each { check in
            check.open(uri: sample, playWhenReady: false)
            wait(15, "ready") { check.snapshot().playbackState == 3 }
            let held = check.snapshot().positionMs
            settle(1)
            XCTAssertFalse(check.snapshot().isPlaying)
            XCTAssertLessThan(abs(check.snapshot().positionMs - held), 300, "moved while paused")
            check.play()
            wait(5, "playing") { check.snapshot().isPlaying && check.snapshot().positionMs > held + 300 }
        }
    }

    /// Seeks, exact and to key frames, and the skip forward Android's controls use.
    func testSeeks() {
        each { check in
            check.open(uri: sample, playWhenReady: false)
            wait(15, "ready") { check.snapshot().playbackState == 3 }
            check.setExactSeeks(exact: true)
            check.seekTo(positionMs: 3_500)
            wait(5, "the exact seek") { check.snapshot().playbackState == 3 && abs(check.snapshot().positionMs - 3_500) < 300 }
            check.setExactSeeks(exact: false)
            check.seekTo(positionMs: 6_400)
            wait(5, "the key frame seek") { check.snapshot().playbackState == 3 && (5_000...6_500).contains(check.snapshot().positionMs) }
            XCTAssertTrue(check.heard().contains("seek 6400"), "the discontinuity told")
        }
    }

    /// A soundtrack chosen by an override, subtitles turned off and on, as the engine reports them.
    func testTrackChoices() {
        each { check in
            check.open(uri: sample, playWhenReady: false)
            wait(15, "ready") { check.snapshot().playbackState == 3 }
            check.chooseAudio(language: "ita")
            wait(3, "the Italian soundtrack") { check.snapshot().tracks.contains { $0.type == "audio" && $0.language == "ita" && $0.selected } }
            check.subtitles(on: false)
            wait(3, "subtitles off") { !check.snapshot().tracks.contains { $0.type == "text" && $0.selected } }
            check.subtitles(on: true)
            wait(3, "subtitles on") { check.snapshot().tracks.contains { $0.type == "text" && $0.selected } }
        }
    }

    /// At the end Media3 stays ended until a seek, even when asked to play, then plays from there.
    func testEndsAsExoPlayerDoes() {
        each { check in
            check.open(uri: sample, playWhenReady: true)
            wait(15, "ready") { check.snapshot().playbackState == 3 }
            check.setExactSeeks(exact: true)
            check.seekTo(positionMs: 10_800)
            wait(8, "ended") { check.snapshot().playbackState == 4 }
            XCTAssertFalse(check.snapshot().isPlaying)
            check.play()
            settle(1)
            XCTAssertEqual(check.snapshot().playbackState, 4, "play at the end waits for a seek")
            check.seekTo(positionMs: 0)
            wait(5, "playing again") { check.snapshot().isPlaying && check.snapshot().positionMs < 3_000 }
        }
    }

    /// The features Android builds into ExoPlayer's pipeline reach the engine as commands: upstream's
    /// own processors and text renderer, set as the player's menus set them (PipelineFeatures).
    func testPipelineFeatures() {
        each { check in
            check.open(uri: sample, playWhenReady: false)
            wait(15, "ready") { check.snapshot().playbackState == 3 }
            check.setBoost(on: true)
            wait(2, "Volume boost") { check.snapshot().volumeBoost }
            check.setNight(on: true)
            wait(2, "Night mode") { check.snapshot().nightMode }
            check.setAudioOffsetMs(ms: 150)
            wait(2, "the sound's delay") { check.snapshot().audioDelayMs == 150 }
            check.setSubtitleDelayMs(ms: -300)
            wait(2, "the subtitles' delay") { check.snapshot().subtitleDelayMs == -300 }
            check.foldSurround(on: true)
            wait(2, "the downmix") { check.snapshot().downmix }
            check.foldSurround(on: false)
            check.setBoost(on: false)
            wait(2, "both off again") { !check.snapshot().downmix && !check.snapshot().volumeBoost }
        }
    }

    /// A file that is not a video ends in Media3's error, idle, with the callback told.
    func testErrors() {
        each { check in
            let junk = NSTemporaryDirectory() + "media3-check-junk.mkv"
            FileManager.default.createFile(atPath: junk, contents: Data((0..<65_536).map { _ in UInt8.random(in: 0...255) }))
            check.open(uri: junk, playWhenReady: true)
            wait(10, "the error") { check.snapshot().error != nil }
            XCTAssertEqual(check.snapshot().playbackState, 1, "idle with the error")
            XCTAssertTrue(check.heard().contains("error"), "onPlayerError")
        }
    }

    // Each engine in turn, installed for ExoPlayer, drawing in the app's window.

    private func each(_ body: (Media3Check) -> Void) {
        for engine in VideoEngines.all {
            XCTContext.runActivity(named: engine.name) { _ in
                Engines.shared.install { engine.make() }
                let check = Media3Check()
                let window = UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.windows.first }.first
                if let surface = check.surface() {
                    surface.frame = CGRect(x: 0, y: 0, width: 320, height: 180)
                    window?.addSubview(surface)
                }
                body(check)
                check.release()
                check.surface()?.removeFromSuperview()
                settle(0.5)
            }
        }
    }

    private var sample: String {
        Bundle(for: Self.self).path(forResource: "engine-sample", ofType: "mkv") ?? ""
    }

    @discardableResult
    private func wait(_ seconds: TimeInterval, _ what: String, until condition: () -> Bool) -> Bool {
        let deadline = Date().addingTimeInterval(seconds)
        while !condition() {
            if Date() > deadline {
                XCTFail("\(what): not within \(seconds) s")
                return false
            }
            RunLoop.main.run(until: Date().addingTimeInterval(0.05))
        }
        return true
    }

    private func settle(_ seconds: TimeInterval) {
        RunLoop.main.run(until: Date().addingTimeInterval(seconds))
    }
}
