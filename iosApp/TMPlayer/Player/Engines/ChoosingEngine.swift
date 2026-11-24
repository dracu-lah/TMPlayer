import Foundation
import TMPlayerKit
import UIKit
import UniformTypeIdentifiers

/// The engine Android's player plays on: Apple's player for a file it plays fully, mpv for the
/// rest, chosen at each open, as Android's Decoder setting asks (docs/IOS.md, "Apple's
/// player for the formats it plays").
///
/// - Automatic: the file's first bytes are read; MP4 or QuickTime goes to Apple's player, which
///   says whether it decodes every track; anything else, or a file it refuses, goes to mpv.
/// - Prefer software video (EngineOpenOptions.softwareDecoding): mpv, decoding in software.
/// - Nothing is lost: what Apple's player cannot do (Volume boost, night mode, downmix, delays, a
///   subtitle file, 3D), asked at the open or later, hands the video to mpv at the same place.
///
/// The player sees one engine and one surface; the engines' views sit in it, the one playing shown.
final class ChoosingEngine: NSObject, VideoEngine {
    private let view = ChoosingSurfaceView()
    private let mpv = MpvEngine()
    private let apple = AppleEngine()
    private let reading = DispatchQueue(label: "org.tmplayer.engine.choice", qos: .userInitiated)

    /// The engine holding the file now, nil while the choice is being made.
    private var active: VideoEngine?
    /// Counts the opens, so a choice made for an earlier one is dropped.
    private var opening = 0

    // The open in force, for a hand over to mpv.
    private var source: String?
    private var startAtMs: Int64 = 0
    private var options: EngineOpenOptions?
    private var reports: EngineReports?

    // What the player set, carried to whichever engine plays and across a hand over.
    private var wantsPlaying = true
    private var seekWhileChoosing: Int64?
    private var speed: Float?
    private var volume: Int32?
    private var muted: Bool?
    private var scale: CoreVideoScale?
    private var zoom: (Double, Double, Double)?
    private var textSubtitlesShown = true
    private var subtitleStyle: CoreSubtitleStyle?
    private var downmix = false
    private var boost = false
    private var night = false
    private var stereo: CoreStereo3d?
    private var subtitleDelay: Int64 = 0
    private var audioDelay: Int64 = 0
    private var abLoop: (Int64, Int64)?

    /// Told, on the main thread, when the corner window opens or closes (ExternalPlaybackEngine).
    var onPictureInPictureChanged: ((KotlinBoolean) -> Void)?

    /// Whether Apple's player may take files at all: on unless launched with `-appleEngine NO`.
    private let appleAllowed = UserDefaults.standard.object(forKey: "appleEngine") == nil || UserDefaults.standard.bool(forKey: "appleEngine")

    override init() {
        super.init()
        view.add(mpv.surface)
        view.add(apple.surface)
        view.show(mpv.surface)
        apple.onPictureInPictureChanged = { [weak self] active in self?.onPictureInPictureChanged?(active) }
        apple.onCannotPlay = { [weak self] reason in self?.appleCannotPlay(reason) }
        apple.onFailedWhilePlaying = { [weak self] reason, atMs in
            guard let self, self.active === self.apple else { return }
            tmLogWarning("engines", "Apple's player failed at %lld ms, mpv carries on: %@", atMs, reason)
            self.openInMpv(at: atMs)
        }
    }

    // VideoEngine

    var surface: UIView { view }

    /// mpv's: whatever Apple's player lacks is done by handing the video to mpv.
    var capabilities: Set<EngineCapability> { mpv.capabilities }

    func open(source: String, startAtMs: Int64, options: EngineOpenOptions, reports: EngineReports) {
        opening += 1
        let attempt = opening
        active?.stop()
        active = nil
        self.source = source
        self.startAtMs = startAtMs
        self.options = options
        self.reports = reports
        wantsPlaying = true
        seekWhileChoosing = nil
        boost = options.volumeBoost
        downmix = options.downmix
        subtitleDelay = options.subtitleDelayMs
        audioDelay = options.audioDelayMs

        let kind = source.hasPrefix("/") ? "a path" : String(source.prefix { $0 != ":" })
        if !appleAllowed || options.softwareDecoding || appleLacks() {
            tmLog("engines", "%@ to mpv (Apple's player %@, software %d, boost %d, delays %lld %lld)",
                  kind, appleAllowed ? "allowed" : "off", options.softwareDecoding, boost, subtitleDelay, audioDelay)
            openInMpv(at: startAtMs)
            return
        }
        // The first bytes say the format; reading them can wait for TDLib, so not here.
        reading.async { [weak self] in
            let (bytes, type) = ChoosingEngine.check(source)
            DispatchQueue.main.async {
                guard let self, self.opening == attempt else {
                    bytes?.close()
                    return
                }
                let start = self.seekWhileChoosing ?? startAtMs
                tmLog("engines", "%@ is %@", kind, type ?? "not MP4 or QuickTime, to mpv")
                if let type {
                    self.openInApple(bytes: bytes, contentType: type, at: start)
                } else {
                    bytes?.close()
                    self.openInMpv(at: start)
                }
            }
        }
    }

    func play() {
        wantsPlaying = true
        active?.play()
    }

    func pause() {
        wantsPlaying = false
        active?.pause()
    }

    func stop() {
        opening += 1
        active?.stop()
        active = nil
        reports = nil
    }

    func seekTo(positionMs: Int64, exact: Bool) {
        guard let active else {
            seekWhileChoosing = positionMs
            return
        }
        active.seekTo(positionMs: positionMs, exact: exact)
    }

    func seekBy(deltaMs: Int64) { active?.seekBy(deltaMs: deltaMs) }

    func frameStep(forward: Bool) { active?.frameStep(forward: forward) }

    func setSpeed(speed: Float) {
        self.speed = speed
        active?.setSpeed(speed: speed)
    }

    func setVolume(percent: Int32) {
        volume = percent
        active?.setVolume(percent: percent)
    }

    func setMuted(muted: Bool) {
        self.muted = muted
        active?.setMuted(muted: muted)
    }

    func selectTrack(type: IosTrackType, id: Int32) { active?.selectTrack(type: type, id: id) }

    func setScale(scale: CoreVideoScale) {
        self.scale = scale
        active?.setScale(scale: scale)
    }

    func setZoom(zoom: Double, panX: Double, panY: Double) {
        self.zoom = (zoom, panX, panY)
        active?.setZoom(zoom: zoom, panX: panX, panY: panY)
    }

    func setDownmix(stereo: Bool) {
        downmix = stereo
        active?.setDownmix(stereo: stereo)
    }

    func setVolumeBoost(on: Bool) {
        boost = on
        forward { $0.setVolumeBoost(on: on) }
    }

    func setNightMode(on: Bool) {
        night = on
        forward { $0.setNightMode(on: on) }
    }

    func setStereo3d(stereo: CoreStereo3d) {
        self.stereo = stereo
        forward { $0.setStereo3d(stereo: stereo) }
    }

    func setSubtitleStyle(style: CoreSubtitleStyle) {
        subtitleStyle = style
        active?.setSubtitleStyle(style: style)
    }

    func setTextSubtitlesShown(shown: Bool) {
        textSubtitlesShown = shown
        mpv.setTextSubtitlesShown(shown: shown)
        apple.setTextSubtitlesShown(shown: shown)
    }

    func setSubtitleDelay(ms: Int64) {
        subtitleDelay = ms
        forward { $0.setSubtitleDelay(ms: ms) }
    }

    func setAudioDelay(ms: Int64) {
        audioDelay = ms
        forward { $0.setAudioDelay(ms: ms) }
    }

    /// A subtitle file is mpv's to load: on Apple's player the video moves to mpv first, and the
    /// file is loaded there once mpv has the video open.
    func addSubtitle(path: String) -> Bool {
        if active === apple {
            handOverToMpv()
            addSubtitleWhenOpen(path, tries: 20)
            return true
        }
        return active?.addSubtitle(path: path) ?? false
    }

    func screenshot(path: String, withSubtitles: Bool, done: @escaping (KotlinBoolean) -> Void) {
        guard let active else {
            done(KotlinBoolean(value: false))
            return
        }
        active.screenshot(path: path, withSubtitles: withSubtitles, done: done)
    }

    func setAbLoop(startMs: Int64, endMs: Int64) {
        abLoop = (startMs, endMs)
        active?.setAbLoop(startMs: startMs, endMs: endMs)
    }

    func facts() -> EngineFacts { active?.facts() ?? EngineFacts() }

    func close() {
        opening += 1
        active = nil
        reports = nil
        apple.close()
        mpv.close()
    }

    // Choosing

    /// Whether something asked is beyond Apple's player and within mpv's. Night mode and 3D are
    /// beyond both for now (IOS_VIDEO_ENGINES.md, "The mpv engine"), so they move nothing. Neither
    /// does Downmix to stereo, on by default on phones and tablets as on Android: a stereo or mono
    /// soundtrack has nothing to fold, and on a two channel output (the speakers, headphones)
    /// Apple's player folds surround into stereo by itself, as mpv's `audio-channels=stereo` does.
    private func appleLacks() -> Bool {
        boost || subtitleDelay != 0 || audioDelay != 0
    }

    /// The source's format from its first bytes: the open source for a stream (nil for a path),
    /// and the uniform type when it is one Apple's player reads (MP4, QuickTime), nil otherwise.
    private static func check(_ source: String) -> (ByteSource?, String?) {
        let head: Data?
        var bytes: ByteSource?
        if source.hasPrefix("/") || source.hasPrefix("file://") {
            let path = source.hasPrefix("file://") ? (URL(string: source)?.path ?? "") : source
            head = FileHandle(forReadingAtPath: path).flatMap { handle in
                defer { try? handle.close() }
                return try? handle.read(upToCount: 16)
            }
        } else if let opened = ByteSources.open(source) {
            bytes = opened
            head = opened.read(at: 0, count: 16)
        } else {
            return (nil, nil)
        }
        return (bytes, head.flatMap(appleType))
    }

    /// MP4 and QuickTime begin with a box whose type is the 4 bytes after its size: `ftyp` with
    /// the brand after it, or, in older QuickTime files, the movie or its data.
    private static func appleType(_ head: Data) -> String? {
        guard head.count >= 12 else { return nil }
        let bytes = [UInt8](head)
        let box = String(bytes: bytes[4..<8], encoding: .ascii) ?? ""
        switch box {
        case "ftyp":
            let brand = String(bytes: bytes[8..<12], encoding: .ascii) ?? ""
            return brand == "qt  " ? UTType.quickTimeMovie.identifier : UTType.mpeg4Movie.identifier
        case "moov", "mdat", "wide", "free", "pnot":
            return UTType.quickTimeMovie.identifier
        default:
            return nil
        }
    }

    private func openInApple(bytes: ByteSource?, contentType: String, at startAtMs: Int64) {
        guard let source, let options, let reports else {
            bytes?.close()
            return
        }
        active = apple
        view.show(apple.surface)
        carry(to: apple)
        apple.open(source: source, bytes: bytes, contentType: contentType, startAtMs: startAtMs, options: options, reports: reports)
        if !wantsPlaying { apple.pause() }
    }

    private func openInMpv(at startAtMs: Int64) {
        guard let source, let options, let reports else { return }
        active = mpv
        view.show(mpv.surface)
        mpv.open(source: source, startAtMs: startAtMs, options: options, reports: reports)
        carry(to: mpv)
        mpv.setVolumeBoost(on: boost)
        mpv.setDownmix(stereo: downmix)
        mpv.setNightMode(on: night)
        if let stereo { mpv.setStereo3d(stereo: stereo) }
        if let subtitleStyle { mpv.setSubtitleStyle(style: subtitleStyle) }
        mpv.setSubtitleDelay(ms: subtitleDelay)
        mpv.setAudioDelay(ms: audioDelay)
        if let abLoop { mpv.setAbLoop(startMs: abLoop.0, endMs: abLoop.1) }
        if !wantsPlaying { mpv.pause() }
    }

    /// What the player set that every engine takes, to the one that plays now.
    private func carry(to engine: VideoEngine) {
        engine.setTextSubtitlesShown(shown: textSubtitlesShown)
        if let speed { engine.setSpeed(speed: speed) }
        if let volume { engine.setVolume(percent: volume) }
        if let muted { engine.setMuted(muted: muted) }
        if let scale { engine.setScale(scale: scale) }
        if let zoom { engine.setZoom(zoom: zoom.0, panX: zoom.1, panY: zoom.2) }
    }

    /// A command Apple's player cannot carry out: on it, the video moves to mpv, which then has it
    /// (openInMpv sets everything asked); otherwise it goes to the engine playing.
    private func forward(_ command: (VideoEngine) -> Void) {
        if active === apple {
            if appleLacks() { handOverToMpv() }
            return
        }
        if let active { command(active) }
    }

    /// Apple's player refused the file before reporting anything: mpv opens it, at the same place.
    private func appleCannotPlay(_ reason: String) {
        guard active === apple else { return }
        tmLog("engines", "Apple's player passes the file to mpv: %@", reason)
        openInMpv(at: seekWhileChoosing ?? startAtMs)
    }

    /// The video from Apple's player to mpv, where it stands, playing or paused as it was.
    private func handOverToMpv() {
        guard active === apple else { return }
        let at = Int64((apple.positionSeconds * 1000).rounded())
        tmLog("engines", "to mpv at %lld ms for a feature Apple's player lacks", at)
        apple.stop()
        openInMpv(at: at)
    }

    private func addSubtitleWhenOpen(_ path: String, tries: Int) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            guard let self, self.active === self.mpv else { return }
            if !self.mpv.addSubtitle(path: path), tries > 1 {
                self.addSubtitleWhenOpen(path, tries: tries - 1)
            }
        }
    }
}

/// AirPlay and picture in picture belong to Apple's player (WP-31): while mpv holds the video the
/// player's rows say so instead (`usesApplePlayer` false).
extension ChoosingEngine: ExternalPlaybackEngine {
    var usesApplePlayer: Bool { active === apple }

    func showAirPlayPicker() {
        if active === apple { apple.showAirPlayPicker() }
    }

    func startPictureInPicture() -> Bool {
        active === apple && apple.startPictureInPicture()
    }

    func stopPictureInPicture() {
        apple.stopPictureInPicture()
    }

    func setAutoPictureInPicture(on: Bool) {
        apple.setAutoPictureInPicture(on: on)
    }

    func setExternalPlaybackAllowed(on: Bool) {
        apple.setExternalPlaybackAllowed(on: on)
    }
}

/// The surface the player sees: the engines' views stacked in it, the one playing shown.
final class ChoosingSurfaceView: UIView {
    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
    }

    required init?(coder: NSCoder) { fatalError("not from a storyboard") }

    func add(_ engine: UIView) {
        engine.frame = bounds
        engine.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        engine.isHidden = true
        addSubview(engine)
    }

    func show(_ engine: UIView) {
        for view in subviews { view.isHidden = view !== engine }
    }
}
