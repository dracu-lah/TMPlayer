import TMPlayerKit
import UIKit
import XCTest
@testable import TMPlayer

/// The rules every video engine follows (docs/IOS.md), checked on each engine that
/// VideoEngines lists, through the same adapter the player drives (EngineCheck). A new engine is
/// a line in VideoEngines.all and passes these before it plays anything for anyone.
///
/// The sample (Fixtures/engine-sample.mkv) is 12 s of 320 by 180 H.264 with a key frame every
/// second, two soundtracks (English, Italian), English subtitles and two chapters (0 s, 6 s).
/// Apple's player reads the same streams from Fixtures/engine-sample.mp4 (ffmpeg, streams copied,
/// the subtitles as mov_text).
final class EngineConformanceTests: XCTestCase {
    override func setUp() {
        super.setUp()
        // The simulator's audio server can stall an engine's sound start; none of this needs sound.
        UserDefaults.standard.set("no", forKey: "mpvAudio")
    }

    override func tearDown() {
        UserDefaults.standard.removeObject(forKey: "mpvAudio")
        super.tearDown()
    }

    /// Opens the file, reports its length, size, tracks and chapters, and words its details.
    func testOpensAndReportsTheFile() {
        each { check, _ in
            guard opened(check) else { return }
            let now = check.snapshot()
            XCTAssertTrue((11_500...12_500).contains(now.durationMs), "length \(now.durationMs) ms")
            XCTAssertEqual(now.tracks.filter { $0.type == .video }.count, 1, "video tracks")
            XCTAssertEqual(Set(now.tracks.filter { $0.type == .audio }.compactMap { $0.language }), ["eng", "ita"], "soundtracks")
            XCTAssertEqual(now.tracks.filter { $0.type == .subtitle }.count, 1, "subtitle tracks")
            XCTAssertEqual(now.chapters, 2, "chapters")
            wait(5, "the picture's size") { check.snapshot().videoWidth > 0 }
            XCTAssertEqual(check.snapshot().videoWidth, 320)
            XCTAssertEqual(check.snapshot().videoHeight, 180)
            XCTAssertFalse(check.details().isEmpty, "Playback details")
        }
    }

    /// Plays from where it opened, holds still while paused, and plays on.
    func testPlaysAndPauses() {
        each { check, _ in
            guard opened(check) else { return }
            wait(8, "playback moving") { check.snapshot().positionMs >= 1_000 }
            XCTAssertTrue(check.snapshot().playing)
            check.pause()
            settle(0.3)
            let held = check.snapshot().positionMs
            settle(1)
            XCTAssertLessThan(abs(check.snapshot().positionMs - held), 300, "position moved while paused")
            XCTAssertFalse(check.snapshot().playing)
            check.play()
            wait(5, "playback moving again") { check.snapshot().positionMs >= held + 500 }
        }
    }

    /// A seek lands on the key frame at or before the place, an exact one on the place, a relative
    /// one on the side it moves to, and each says when it has landed.
    func testSeeks() {
        each { check, _ in
            guard opened(check) else { return }
            check.pause()
            check.seekTo(positionMs: 6_000)
            wait(5, "the seek to 6 s landing") { !check.snapshot().seekPending }
            XCTAssertTrue((5_000...6_500).contains(check.snapshot().positionMs), "landed at \(check.snapshot().positionMs) ms")
            check.seekToExact(positionMs: 3_500)
            wait(5, "the exact seek to 3.5 s landing") { !check.snapshot().seekPending }
            XCTAssertTrue((3_300...3_800).contains(check.snapshot().positionMs), "landed at \(check.snapshot().positionMs) ms")
            check.seekBy(deltaMs: 2_000)
            wait(5, "the 2 s skip landing") { !check.snapshot().seekPending }
            XCTAssertTrue((5_000...6_600).contains(check.snapshot().positionMs), "landed at \(check.snapshot().positionMs) ms")
        }
    }

    /// Speed, volume, mute and both delays are in force in the engine, not only asked for, and
    /// raising the volume takes it out of mute.
    func testSpeedVolumeMuteAndDelays() {
        each { check, engine in
            guard opened(check) else { return }
            check.setSpeed(speed: 1.5)
            check.setVolume(percent: 40)
            check.setMuted(muted: true)
            if engine.capabilities.contains(.subtitleDelay) { check.setSubtitleDelay(ms: 250) }
            if engine.capabilities.contains(.audioDelay) { check.setAudioDelay(ms: -120) }
            settle(0.3)
            var facts = check.facts()
            XCTAssertEqual(facts.speed, 1.5, accuracy: 0.01, "speed")
            XCTAssertEqual(facts.volume, 40, "volume")
            XCTAssertTrue(facts.muted, "muted")
            if engine.capabilities.contains(.subtitleDelay) { XCTAssertEqual(facts.subtitleDelayMs, 250, "subtitle delay") }
            if engine.capabilities.contains(.audioDelay) { XCTAssertEqual(facts.audioDelayMs, -120, "audio delay") }
            check.setVolume(percent: 70)
            settle(0.3)
            facts = check.facts()
            XCTAssertEqual(facts.volume, 70, "volume")
            XCTAssertFalse(facts.muted, "raising the volume unmutes")
        }
    }

    /// Switching soundtrack, turning subtitles off and on again, as the engine itself reports it.
    func testTracks() {
        each { check, _ in
            guard opened(check) else { return }
            let audio = check.reportedTracks().filter { $0.type == .audio }
            guard let other = audio.first(where: { !$0.selected }) else {
                XCTFail("no second soundtrack to switch to")
                return
            }
            check.selectTrack(type: .audio, id: other.id)
            wait(3, "the engine on the other soundtrack") {
                check.reportedTracks().contains { $0.type == .audio && $0.id == other.id && $0.selected }
            }
            check.selectTrack(type: .subtitle, id: EngineValues.shared.NO_TRACK)
            wait(3, "the engine with subtitles off") {
                !check.reportedTracks().contains { $0.type == .subtitle && $0.selected }
            }
            guard let subtitle = check.reportedTracks().first(where: { $0.type == .subtitle }) else { return }
            check.selectTrack(type: .subtitle, id: subtitle.id)
            wait(3, "the engine with subtitles on") {
                check.reportedTracks().contains { $0.type == .subtitle && $0.id == subtitle.id && $0.selected }
            }
        }
    }

    /// Reaching the end says so and stops; play then starts again from the beginning.
    func testEndsAndStartsAgain() {
        each { check, _ in
            guard opened(check) else { return }
            check.seekTo(positionMs: 10_500)
            wait(5, "the seek near the end landing") { !check.snapshot().seekPending }
            check.play()
            wait(8, "the end") { check.snapshot().ended }
            XCTAssertFalse(check.snapshot().playing)
            check.play()
            wait(5, "playing from the beginning") {
                let now = check.snapshot()
                return !now.ended && now.playing && !now.seekPending && now.positionMs < 3_000
            }
        }
    }

    /// A subtitle file loads beside the file's own, and the frame on screen saves as a picture.
    func testExternalSubtitlesAndScreenshot() {
        each { check, engine in
            guard opened(check) else { return }
            if engine.capabilities.contains(.externalSubtitles) {
                let srt = NSTemporaryDirectory() + "engine-check.srt"
                try? "1\n00:00:00,500 --> 00:00:05,000\nLoaded from a file\n".write(toFile: srt, atomically: true, encoding: .utf8)
                XCTAssertTrue(check.addSubtitle(path: srt), "addSubtitle")
                wait(3, "the loaded subtitles selected") {
                    check.reportedTracks().contains { $0.type == .subtitle && $0.external && $0.selected }
                }
            }
            if engine.capabilities.contains(.screenshot) {
                wait(5, "a frame on screen") { check.snapshot().positionMs > 0 }
                let png = NSTemporaryDirectory() + "engine-check.png"
                try? FileManager.default.removeItem(atPath: png)
                var saved: Bool?
                check.screenshot(path: png) { saved = $0.boolValue }
                wait(8, "the screenshot") { saved != nil }
                XCTAssertEqual(saved, true, "screenshot")
                XCTAssertTrue(FileManager.default.fileExists(atPath: png), "screenshot file")
            }
        }
    }

    /// A file that is not a video ends in an error the player can show, not a crash.
    func testFailsWithoutCrashing() {
        each { check, _ in
            let junk = NSTemporaryDirectory() + "engine-check-junk.mkv"
            let bytes = (0..<65_536).map { _ in UInt8.random(in: 0...255) }
            FileManager.default.createFile(atPath: junk, contents: Data(bytes))
            check.open(source: junk, startAtMs: 0)
            wait(10, "the error") { check.snapshot().error != nil }
            XCTAssertFalse(check.snapshot().opened)
        }
    }

    /// Closing returns at once on the main thread, and nothing is reported after it.
    func testClosesWithoutBlocking() {
        each { check, _ in
            guard opened(check) else { return }
            wait(8, "playback moving") { check.snapshot().positionMs >= 500 }
            let started = Date()
            check.close()
            XCTAssertLessThan(Date().timeIntervalSince(started), 0.2, "close blocked the main thread")
            let after = check.snapshot().positionMs
            settle(0.8)
            XCTAssertEqual(check.snapshot().positionMs, after, "reported after close")
        }
    }

    // The engines, one at a time, each drawing in the app's window as it would in the player.

    private func each(_ body: (EngineCheck, VideoEngine) -> Void) {
        for engine in VideoEngines.all {
            XCTContext.runActivity(named: engine.name) { _ in
                sampleType = engine.name == "apple" ? "mp4" : "mkv"
                let made = engine.make()
                let window = UIApplication.shared.connectedScenes
                    .compactMap { ($0 as? UIWindowScene)?.windows.first }.first
                made.surface.frame = CGRect(x: 0, y: 0, width: 320, height: 180)
                window?.addSubview(made.surface)
                let check = EngineCheck(engine: made)
                body(check, made)
                check.close()
                made.surface.removeFromSuperview()
                settle(0.5)
            }
        }
    }

    /// The sample's kind for the engine under test: what it plays.
    private var sampleType = "mkv"

    private var sample: String {
        Bundle(for: Self.self).path(forResource: "engine-sample", ofType: sampleType) ?? ""
    }

    private func opened(_ check: EngineCheck) -> Bool {
        check.open(source: sample, startAtMs: 0)
        guard wait(15, "the sample opening", until: { check.snapshot().opened || check.snapshot().error != nil }) else { return false }
        XCTAssertNil(check.snapshot().error, "opening the sample")
        return check.snapshot().opened
    }

    /// Lets the main thread run (the engine's reports, the adapter's coroutines) until [condition].
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
