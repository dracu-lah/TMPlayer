import TMPlayerKit

/// The video engines, each a class in Engines/ following the rules (VideoEngine,
/// docs/IOS.md). The conformance test checks every engine in [all], each on a sample
/// it plays (an MKV for mpv, an MP4 for Apple's player); Android's player and the rest of the app
/// stay as they are.
enum VideoEngines {
    static let all: [(name: String, make: () -> VideoEngine)] = [
        ("mpv", { MpvEngine() }),
        ("apple", { AppleEngine() }),
    ]

    /// What Android's player plays on: Apple's player for the files it plays fully, mpv for the
    /// rest (ChoosingEngine). The conformance test checks each engine in [all] on its own.
    static func make() -> VideoEngine {
        ChoosingEngine()
    }
}
