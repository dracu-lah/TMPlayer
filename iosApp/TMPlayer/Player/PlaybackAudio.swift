import AVFoundation

/// The audio session a video player asks for: playback (heard with the silent switch on, and later
/// in the background), the movie mode, long-form video routing. Activating it can block on the
/// audio server, so the player calls it off the main thread.
enum PlaybackAudio {
    static func activate() {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, mode: .moviePlayback, policy: .longFormVideo)
            try session.setActive(true)
        } catch {
            tmLogWarning("audio session", "%@", error.localizedDescription)
        }
    }

    /// Lets other apps' audio resume once the player is gone.
    static func deactivate() {
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}
