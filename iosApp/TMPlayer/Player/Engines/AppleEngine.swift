import AVFoundation
import AVKit
import TMPlayerKit
import UIKit

/// Apple's player (AVPlayer) as a TMPlayer video engine (docs/IOS.md), for the files
/// it plays: MP4 and QuickTime with H.264 or HEVC. It hands each decoded frame to iOS's compositor
/// through AVPlayerLayer, where mpv draws it again through MoltenVK, so a file it plays costs less.
/// ChoosingEngine gives it those files and mpv the rest; what it cannot do (Volume boost, night
/// mode, downmix, delays, a subtitle file, 3D) makes ChoosingEngine hand the video to mpv.
///
/// Telegram's stream and Android's data sources reach it through AppleStreamLoader. AirPlay and
/// picture in picture are Apple's player's alone (WP-31, `ExternalPlaybackEngine`): the player's More
/// panel asks ChoosingEngine for them, and an MKV in mpv says why it has neither. AirPlay is iOS's
/// list of screens (an AVRoutePickerView the surface holds out of sight, pressed by the row) with
/// external playback allowed; the corner window is an AVPictureInPictureController on the same layer.
final class AppleEngine: NSObject, VideoEngine {
    private let view = AppleSurfaceView()
    private var player: AVPlayer?
    private var item: AVPlayerItem?
    private var asset: AVURLAsset?
    private var loader: AppleStreamLoader?
    private var itemWatch: [NSKeyValueObservation] = []
    private var playerWatch: [NSKeyValueObservation] = []
    private var lifecycle: [NSObjectProtocol] = []
    private var notices: [NSObjectProtocol] = []
    private var timeObserver: Any?
    private var loopObserver: Any?
    private var legible: AVPlayerItemLegibleOutput?
    private var pip: AVPictureInPictureController?
    /// Whether the system may open the corner window by itself when the app goes away while this plays.
    private var autoPip = false
    /// Whether this video may go to an AirPlay screen (SEC2-01, `setExternalPlaybackAllowed`); false until the player knows.
    private var externalAllowed = false

    /// Told, on the main thread, when the corner window opens (true) or closes (false).
    var onPictureInPictureChanged: ((KotlinBoolean) -> Void)?

    // The reports of the file now open, set on the main thread, read from any.
    private let reportsLock = NSLock()
    private var currentReports: EngineReports?
    private var reports: EngineReports? {
        get { reportsLock.lock(); defer { reportsLock.unlock() }; return currentReports }
        set { reportsLock.lock(); currentReports = newValue; reportsLock.unlock() }
    }

    // On the main thread.
    private var speed: Float = 1
    private var wantsPlaying = true
    private var opened = false
    private var textSubtitlesDrawn = true
    private var audioGroup: AVMediaSelectionGroup?
    private var subtitleGroup: AVMediaSelectionGroup?
    private var audioOptions: [AVMediaSelectionOption] { audioGroup?.options ?? [] }
    /// The file's subtitle tracks. AVFoundation adds a "Forced" twin of each, the same track showing
    /// only its forced lines; the player lists the tracks a file has, as mpv reports them, so a
    /// forced option is a track of its own only where no full one has its language.
    private var subtitleOptions: [AVMediaSelectionOption] {
        let options = subtitleGroup?.options ?? []
        let full = Set(options.filter { !$0.hasMediaCharacteristic(.containsOnlyForcedSubtitles) }.map(AppleEngine.language))
        return options.filter { !$0.hasMediaCharacteristic(.containsOnlyForcedSubtitles) || !full.contains(AppleEngine.language($0)) }
    }
    private var container = ""
    private var lastSubtitle: String?

    /// Told, on the main thread, when a file this engine took turns out to be one it cannot play,
    /// before anything was reported for it: ChoosingEngine opens it in mpv instead.
    var onCannotPlay: ((_ reason: String) -> Void)?

    /// Told, on the main thread, when the file stops playing on a failure after it opened: the
    /// decoder or the stream gave up. ChoosingEngine carries on in mpv at the same place, as
    /// Android's "Try the other decoder" does, without asking. Without it, the failure is reported.
    var onFailedWhilePlaying: ((_ reason: String, _ atMs: Int64) -> Void)?

    override init() {
        super.init()
        let center = NotificationCenter.default
        // A layer with a player stops the video in the background; without one the sound goes on,
        // which is the player's to decide (the rules: an engine does not pause by itself).
        lifecycle.append(center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            guard let self, !self.keepsPictureInBackground else { return }
            self.view.playerLayer.player = nil
        })
        lifecycle.append(center.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
            self?.view.playerLayer.player = self?.player
        })
    }

    /// Whether the layer stays on the player in the background: the corner window is up, the system
    /// is about to open it (it may, and the video is playing), or the picture is on an AirPlay screen.
    private var keepsPictureInBackground: Bool {
        guard let player else { return false }
        if player.isExternalPlaybackActive { return true }
        guard let pip else { return false }
        return pip.isPictureInPictureActive || (autoPip && pip.isPictureInPicturePossible && player.timeControlStatus == .playing)
    }

    /// Where playback stands, for a hand over to mpv.
    var positionSeconds: Double { player?.currentTime().seconds.finiteOr(0) ?? 0 }

    // VideoEngine

    var surface: UIView { view }

    var capabilities: Set<EngineCapability> { [.frameStep, .zoom, .screenshot, .abLoop] }

    /// Opens a file whose first bytes said MP4 or QuickTime: [bytes] is its open source for a
    /// stream (nil for a path or a URL), [contentType] its uniform type.
    func open(source: String, bytes: ByteSource?, contentType: String, startAtMs: Int64, options: EngineOpenOptions, reports: EngineReports) {
        unload()
        self.reports = reports
        opened = false
        lastSubtitle = nil
        speed = options.speed
        wantsPlaying = true
        container = contentType == "com.apple.quicktime-movie" ? "mov" : "mp4"
        NotificationCenter.default.post(name: .videoEngineStarting, object: nil)

        let url: URL
        if let bytes {
            let loader = AppleStreamLoader(source: bytes, contentType: contentType)
            self.loader = loader
            url = loader.url
        } else if source.hasPrefix("/") {
            url = URL(fileURLWithPath: source)
        } else if let parsed = URL(string: source) {
            url = parsed
        } else {
            cannotPlay("not a path or a URL")
            return
        }
        let asset = AVURLAsset(url: url, options: [AVURLAssetPreferPreciseDurationAndTimingKey: false])
        if let loader { asset.resourceLoader.setDelegate(loader, queue: loader.delegateQueue) }
        self.asset = asset

        Task { @MainActor [weak self, asset] in
            do {
                let (playable, duration, tracks) = try await asset.load(.isPlayable, .duration, .tracks)
                guard let self, self.asset === asset else { return }
                guard playable, try await AppleEngine.playsEveryTrack(tracks) else {
                    self.cannotPlay("Apple's player does not play this file's format")
                    return
                }
                let offered = try await AppleEngine.offersEveryTrack(asset, tracks)
                guard self.asset === asset else { return }
                guard offered else {
                    self.cannotPlay("Apple's player does not offer every sound and subtitle track of this file")
                    return
                }
                self.start(asset: asset, duration: duration, startAtMs: startAtMs, options: options)
            } catch {
                guard let self, self.asset === asset else { return }
                self.cannotPlay(error.localizedDescription)
            }
        }
    }

    /// The VideoEngine open, for a source no one checked: a path or a URL Apple's player reads by
    /// itself. ChoosingEngine uses the open above.
    func open(source: String, startAtMs: Int64, options: EngineOpenOptions, reports: EngineReports) {
        open(source: source, bytes: nil, contentType: "public.mpeg-4", startAtMs: startAtMs, options: options, reports: reports)
    }

    func play() {
        wantsPlaying = true
        guard let player else { return }
        player.defaultRate = speed
        player.play()
    }

    func pause() {
        wantsPlaying = false
        player?.pause()
    }

    func stop() {
        reports = nil
        unload()
    }

    func seekTo(positionMs: Int64, exact: Bool) {
        // Not exact: the key frame at or before the place, as the rules ask.
        seek(to: positionMs, before: exact ? .zero : .positiveInfinity, after: .zero)
    }

    func seekBy(deltaMs: Int64) {
        guard let player else { return }
        let target = Int64((player.currentTime().seconds.finiteOr(0) * 1000).rounded()) + deltaMs
        // A key frame on the side it moves to.
        if deltaMs >= 0 {
            seek(to: max(0, target), before: .zero, after: .positiveInfinity)
        } else {
            seek(to: max(0, target), before: .positiveInfinity, after: .zero)
        }
    }

    func frameStep(forward: Bool) {
        guard let player, let item else { return }
        wantsPlaying = false
        player.pause()
        if forward ? item.canStepForward : item.canStepBackward {
            item.step(byCount: forward ? 1 : -1)
        }
    }

    func setSpeed(speed: Float) {
        self.speed = speed
        guard let player else { return }
        player.defaultRate = speed
        if player.rate != 0 { player.rate = speed }
    }

    func setVolume(percent: Int32) {
        player?.volume = Float(max(0, min(100, percent))) / 100
    }

    func setMuted(muted: Bool) {
        player?.isMuted = muted
    }

    func selectTrack(type: IosTrackType, id: Int32) {
        guard let item, type != .video, let group = type == .audio ? audioGroup : subtitleGroup else { return }
        let options = type == .audio ? audioOptions : subtitleOptions
        let index = Int(id) - 1
        if id < 0 {
            if type == .subtitle { item.select(nil, in: group) }
        } else if options.indices.contains(index) {
            item.select(options[index], in: group)
        }
        reportTracks()
    }

    func setScale(scale: CoreVideoScale) {
        view.scale = scale
    }

    func setZoom(zoom: Double, panX: Double, panY: Double) {
        let finite = { (value: Double) in value.isFinite ? value : 0 }
        view.zoom = (finite(zoom), finite(panX), finite(panY))
    }

    // What Apple's player cannot do: ChoosingEngine hands the video to mpv before these matter.
    func setDownmix(stereo: Bool) {}
    func setVolumeBoost(on: Bool) {}
    func setNightMode(on: Bool) {}
    func setStereo3d(stereo: CoreStereo3d) {}
    func setSubtitleStyle(style: CoreSubtitleStyle) {}
    func setSubtitleDelay(ms: Int64) {}
    func setAudioDelay(ms: Int64) {}
    func addSubtitle(path: String) -> Bool { false }

    func setTextSubtitlesShown(shown: Bool) {
        textSubtitlesDrawn = shown
        legible?.suppressesPlayerRendering = !shown
    }

    /// The frame at the place playback is, from the file itself; Apple's player hands out no frame
    /// with its subtitles drawn, so the picture comes without them.
    func screenshot(path: String, withSubtitles: Bool, done: @escaping (KotlinBoolean) -> Void) {
        guard let asset, let player else {
            done(KotlinBoolean(value: false))
            return
        }
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.requestedTimeToleranceBefore = .zero
        generator.requestedTimeToleranceAfter = .zero
        generator.generateCGImageAsynchronously(for: player.currentTime()) { image, _, _ in
            let saved: Bool
            if let image, let png = UIImage(cgImage: image).pngData() {
                saved = (try? png.write(to: URL(fileURLWithPath: path), options: .atomic)) != nil
            } else {
                saved = false
            }
            DispatchQueue.main.async { done(KotlinBoolean(value: saved)) }
        }
    }

    func setAbLoop(startMs: Int64, endMs: Int64) {
        if let loopObserver { player?.removeTimeObserver(loopObserver) }
        loopObserver = nil
        guard let player, startMs >= 0, endMs > startMs else { return }
        let end = NSValue(time: CMTime(value: endMs, timescale: 1000))
        loopObserver = player.addBoundaryTimeObserver(forTimes: [end], queue: .main) { [weak self] in
            self?.seek(to: startMs, before: .zero, after: .zero)
        }
    }

    func facts() -> EngineFacts {
        let facts = EngineFacts()
        facts.engine = "AVPlayer"
        facts.engineVersion = "iOS \(UIDevice.current.systemVersion)"
        facts.decoder = "VideoToolbox"
        facts.hardwareDecoder = "videotoolbox"
        facts.videoOutput = "AVPlayerLayer"
        facts.audioOutput = "AVPlayer"
        facts.container = container
        facts.speed = Double(speed)
        facts.volume = Int32(((player?.volume ?? 1) * 100).rounded())
        facts.muted = player?.isMuted ?? false
        if let item {
            let size = item.presentationSize
            facts.videoWidth = Int32(size.width)
            facts.videoHeight = Int32(size.height)
            facts.bufferedSeconds = bufferedEnd(item) - item.currentTime().seconds.finiteOr(0)
            for track in item.tracks {
                guard let assetTrack = track.assetTrack else { continue }
                let description = (assetTrack.formatDescriptions as? [CMFormatDescription])?.first
                if assetTrack.mediaType == .video {
                    facts.framesPerSecond = Double(track.currentVideoFrameRate > 0 ? track.currentVideoFrameRate : assetTrack.nominalFrameRate)
                    if let description { facts.videoCodec = AppleEngine.fourCC(CMFormatDescriptionGetMediaSubType(description)) }
                } else if assetTrack.mediaType == .audio, track.isEnabled {
                    if let description {
                        facts.audioCodec = AppleEngine.fourCC(CMFormatDescriptionGetMediaSubType(description))
                        if let basic = CMAudioFormatDescriptionGetStreamBasicDescription(description)?.pointee {
                            facts.audioChannels = Int32(basic.mChannelsPerFrame)
                            facts.sampleRate = Int32(basic.mSampleRate)
                        }
                    }
                }
            }
        }
        return facts
    }

    func close() {
        reports = nil
        lifecycle.forEach(NotificationCenter.default.removeObserver)
        lifecycle.removeAll()
        unload()
        DispatchQueue.global(qos: .userInitiated).async { PlaybackAudio.deactivate() }
    }

    // AirPlay and picture in picture (ExternalPlaybackEngine, through ChoosingEngine)

    /// iOS's list of AirPlay screens, over the player: the route picker the surface holds is pressed
    /// for the viewer, as the system's own button would be.
    func showAirPlayPicker() {
        view.routePicker.subviews.compactMap { $0 as? UIButton }.first?.sendActions(for: .touchUpInside)
    }

    /// The corner window, once the picture is on screen; false while it is not possible (not ready,
    /// the feature off in Settings, or no video track).
    func startPictureInPicture() -> Bool {
        guard let pip, pip.isPictureInPicturePossible else { return false }
        pip.startPictureInPicture()
        return true
    }

    func stopPictureInPicture() {
        pip?.stopPictureInPicture()
    }

    func setAutoPictureInPicture(on: Bool) {
        autoPip = on
        pip?.canStartPictureInPictureAutomaticallyFromInline = on
    }

    /// The player's savable verdict (SEC2-01): AirPlay of any kind only while it is true. Turned off
    /// while the video is on another screen, it pauses first, so a restricted video does not go on
    /// playing there.
    func setExternalPlaybackAllowed(on: Bool) {
        externalAllowed = on
        if let player { applyExternalPlayback(to: player) }
    }

    private func applyExternalPlayback(to player: AVPlayer) {
        if !externalAllowed && player.isExternalPlaybackActive { player.pause() }
        player.allowsExternalPlayback = externalAllowed
        player.usesExternalPlaybackWhileExternalScreenIsActive = externalAllowed
    }

    /// The controller is made once for the layer and follows whatever player the layer holds.
    private func preparePictureInPicture() {
        guard AVPictureInPictureController.isPictureInPictureSupported() else { return }
        if pip == nil, let controller = AVPictureInPictureController(playerLayer: view.playerLayer) {
            controller.delegate = self
            pip = controller
        }
        pip?.canStartPictureInPictureAutomaticallyFromInline = autoPip
    }

    // Opening

    /// Whether Apple's player decodes every track of the file: a format it reads with a track it
    /// cannot decode (an MP4 with MPEG-4 Part 2 video, or DTS sound) goes to mpv whole.
    private static func playsEveryTrack(_ tracks: [AVAssetTrack]) async throws -> Bool {
        for track in tracks where track.mediaType == .video || track.mediaType == .audio {
            let (playable, decodable) = try await track.load(.isPlayable, .isDecodable)
            if !playable || !decodable { return false }
        }
        return tracks.contains { $0.mediaType == .video }
    }

    /// Whether every sound and subtitle track of the file is a choice Apple's player offers, as
    /// Android's player lists them all. Tracks a file does not mark as alternatives of one another
    /// (each in an alternate group of its own, as some muxers write them) are left out of its
    /// choices, and the sound ones that are on all play at once: an MP4 with Italian and English
    /// sound, both on, played the two languages over each other and listed Italian alone, with one
    /// of its 18 subtitle tracks (2026-11-14). mpv lists every track and plays one, so such a file goes to mpv. A chapter
    /// track is a text track too, and no subtitle.
    static func offersEveryTrack(_ asset: AVAsset, _ tracks: [AVAssetTrack]) async throws -> Bool {
        let sound = tracks.filter { $0.mediaType == .audio }.count
        if sound > 1 {
            let offered = try await asset.loadMediaSelectionGroup(for: .audible)?.options.count ?? 0
            if offered < sound { return false }
        }
        var chapterTracks = Set<CMPersistentTrackID>()
        for track in tracks {
            for chapters in try await track.loadAssociatedTracks(ofType: .chapterList) {
                chapterTracks.insert(chapters.trackID)
            }
        }
        let subtitles = tracks.filter { subtitleTypes.contains($0.mediaType) && !chapterTracks.contains($0.trackID) }.count
        if subtitles > 0 {
            let options = try await asset.loadMediaSelectionGroup(for: .legible)?.options ?? []
            // AVFoundation adds a forced only twin of each subtitle track: the full ones are counted.
            let full = options.filter { !$0.hasMediaCharacteristic(.containsOnlyForcedSubtitles) }.count
            if full < subtitles { return false }
        }
        return true
    }

    private static let subtitleTypes: Set<AVMediaType> = [.subtitle, .text, .closedCaption]

    private func start(asset: AVURLAsset, duration: CMTime, startAtMs: Int64, options: EngineOpenOptions) {
        let item = AVPlayerItem(asset: asset)
        item.audioTimePitchAlgorithm = .timeDomain
        if options.readAheadSeconds > 0 { item.preferredForwardBufferDuration = TimeInterval(options.readAheadSeconds) }
        let legible = AVPlayerItemLegibleOutput(mediaSubtypesForNativeRepresentation: [])
        legible.suppressesPlayerRendering = !textSubtitlesDrawn
        legible.setDelegate(self, queue: .main)
        item.add(legible)
        self.legible = legible

        let player = AVPlayer(playerItem: item)
        // AirPlay: the picture goes to the screen the viewer picked from iOS's list, but only once the
        // player says the chat allows saving (SEC2-01): Control Center's route button reaches AVPlayer
        // without the More panel's row. Off until then.
        player.allowsExternalPlayback = false
        player.usesExternalPlaybackWhileExternalScreenIsActive = false
        applyExternalPlayback(to: player)
        player.actionAtItemEnd = .pause
        player.volume = Float(max(0, min(100, options.volume))) / 100
        player.isMuted = options.muted
        player.defaultRate = speed
        self.item = item
        self.player = player
        view.playerLayer.player = UIApplication.shared.applicationState == .background ? nil : player
        preparePictureInPicture()
        watch(item: item, player: player, durationMs: Int64((duration.seconds.finiteOr(0) * 1000).rounded()), startAtMs: startAtMs, options: options)
    }

    private func watch(item: AVPlayerItem, player: AVPlayer, durationMs: Int64, startAtMs: Int64, options: EngineOpenOptions) {
        itemWatch.append(item.observe(\.status, options: [.new]) { [weak self] item, _ in
            DispatchQueue.main.async {
                guard let self, self.item === item else { return }
                switch item.status {
                case .readyToPlay where !self.opened:
                    self.ready(item: item, durationMs: durationMs, startAtMs: startAtMs, options: options)
                case .failed:
                    let reason = item.error?.localizedDescription ?? "Apple's player failed"
                    if self.opened { self.failedWhilePlaying(reason) } else { self.cannotPlay(reason) }
                default:
                    break
                }
            }
        })
        itemWatch.append(item.observe(\.presentationSize, options: [.new]) { [weak self] item, _ in
            let size = item.presentationSize
            guard size.width > 0, size.height > 0 else { return }
            self?.reports?.videoSize(width: Int32(size.width), height: Int32(size.height))
            DispatchQueue.main.async { self?.view.videoSize = size }
        })
        playerWatch.append(player.observe(\.timeControlStatus, options: [.new]) { [weak self] player, _ in
            guard let self else { return }
            switch player.timeControlStatus {
            case .playing:
                self.reports?.buffering(buffering: false)
                self.reports?.paused(paused: false)
            case .paused:
                self.reports?.buffering(buffering: false)
                self.reports?.paused(paused: true)
            case .waitingToPlayAtSpecifiedRate:
                self.reports?.buffering(buffering: player.reasonForWaitingToPlay != .noItemToPlay)
            @unknown default:
                break
            }
        })
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 4), queue: .main) { [weak self] time in
            guard let self, self.opened, let item = self.item else { return }
            let duration = item.duration.seconds.finiteOr(0)
            self.reports?.progress(
                positionMs: Int64((time.seconds.finiteOr(0) * 1000).rounded()),
                durationMs: Int64((duration * 1000).rounded()),
                bufferedMs: Int64((self.bufferedEnd(item) * 1000).rounded())
            )
        }
        notices.append(NotificationCenter.default.addObserver(forName: AVPlayerItem.didPlayToEndTimeNotification, object: item, queue: .main) { [weak self] _ in
            self?.reports?.ended()
        })
        notices.append(NotificationCenter.default.addObserver(forName: AVPlayerItem.failedToPlayToEndTimeNotification, object: item, queue: .main) { [weak self] note in
            let error = note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
            self?.failedWhilePlaying(error?.localizedDescription ?? "Apple's player stopped")
        })
    }

    /// The file is ready: its tracks and chapters, opened, the languages asked for, the start.
    private func ready(item: AVPlayerItem, durationMs: Int64, startAtMs: Int64, options: EngineOpenOptions) {
        guard let asset else { return }
        Task { @MainActor [weak self] in
            let audible = try? await asset.loadMediaSelectionGroup(for: .audible)
            let legibleGroup = try? await asset.loadMediaSelectionGroup(for: .legible)
            let chapters = (try? await AppleEngine.chapters(of: asset)) ?? []
            guard let self, self.item === item else { return }
            self.audioGroup = audible
            self.subtitleGroup = legibleGroup
            if let audible, let pick = AppleEngine.option(in: self.audioOptions, for: options.audioLanguages) {
                item.select(pick, in: audible)
            }
            if let legibleGroup {
                if options.subtitlesOff {
                    item.select(nil, in: legibleGroup)
                } else if let pick = AppleEngine.option(in: self.subtitleOptions, for: options.subtitleLanguages) {
                    item.select(pick, in: legibleGroup)
                }
            }
            self.opened = true
            self.reportTracks()
            self.reports?.chapters(chapters: chapters)
            self.reports?.opened(durationMs: durationMs)
            let begin = { [weak self] in
                guard let self, self.item === item else { return }
                self.reports?.seekLanded(positionMs: startAtMs)
                NotificationCenter.default.post(name: .videoEngineStarted, object: nil)
                if self.wantsPlaying { self.play() }
            }
            if startAtMs > 0 {
                item.seek(to: CMTime(value: startAtMs, timescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero) { _ in
                    DispatchQueue.main.async(execute: begin)
                }
            } else {
                begin()
            }
        }
    }

    private func cannotPlay(_ reason: String) {
        tmLog("apple", "cannot play here, %@", reason)
        let reports = self.reports
        unload()
        NotificationCenter.default.post(name: .videoEngineStarted, object: nil)
        if let onCannotPlay {
            onCannotPlay(reason)
        } else {
            reports?.failed(message: reason)
        }
    }

    private func failedWhilePlaying(_ reason: String) {
        tmLogWarning("apple", "failed while playing, %@", reason)
        let at = Int64((positionSeconds * 1000).rounded())
        if let onFailedWhilePlaying {
            reports = nil
            unload()
            onFailedWhilePlaying(reason, at)
        } else {
            reports?.failed(message: reason)
        }
    }

    /// Lets go of the file: observers, the player, the loader and its stream.
    private func unload() {
        itemWatch.forEach { $0.invalidate() }
        itemWatch.removeAll()
        playerWatch.forEach { $0.invalidate() }
        playerWatch.removeAll()
        if let timeObserver { player?.removeTimeObserver(timeObserver) }
        timeObserver = nil
        if let loopObserver { player?.removeTimeObserver(loopObserver) }
        loopObserver = nil
        notices.forEach(NotificationCenter.default.removeObserver)
        notices.removeAll()
        player?.pause()
        // The corner window goes with the video it showed.
        if pip?.isPictureInPictureActive == true { pip?.stopPictureInPicture() }
        player?.replaceCurrentItem(with: nil)
        view.playerLayer.player = nil
        player = nil
        item = nil
        asset = nil
        legible = nil
        loader?.close()
        loader = nil
        opened = false
        audioGroup = nil
        subtitleGroup = nil
    }

    // Reports

    private func seek(to positionMs: Int64, before: CMTime, after: CMTime) {
        guard let item else { return }
        item.seek(to: CMTime(value: positionMs, timescale: 1000), toleranceBefore: before, toleranceAfter: after) { [weak self] finished in
            guard finished else { return }
            DispatchQueue.main.async {
                guard let self, self.item === item else { return }
                self.reports?.seekLanded(positionMs: Int64((item.currentTime().seconds.finiteOr(0) * 1000).rounded()))
            }
        }
    }

    private func reportTracks() {
        guard let item else { return }
        var tracks: [EngineTrack] = []
        let chosenAudio = audioGroup.flatMap { item.currentMediaSelection.selectedMediaOption(in: $0) }
        let chosenSubtitle = subtitleGroup.flatMap { item.currentMediaSelection.selectedMediaOption(in: $0) }
        if let video = item.tracks.first(where: { $0.assetTrack?.mediaType == .video })?.assetTrack {
            let description = (video.formatDescriptions as? [CMFormatDescription])?.first
            tracks.append(EngineTrack(
                id: 1, type: .video, title: nil, language: nil,
                codec: description.map { AppleEngine.codecName(CMFormatDescriptionGetMediaSubType($0)) },
                channels: 0, selected: true, isDefault: true, external: false, forced: false
            ))
        }
        for (index, option) in audioOptions.enumerated() {
            tracks.append(EngineTrack(
                id: Int32(index + 1), type: .audio, title: AppleEngine.title(option), language: AppleEngine.language(option),
                codec: nil, channels: 0, selected: option == chosenAudio, isDefault: index == 0,
                external: false, forced: false
            ))
        }
        for (index, option) in subtitleOptions.enumerated() {
            tracks.append(EngineTrack(
                id: Int32(index + 1), type: .subtitle, title: AppleEngine.title(option), language: AppleEngine.language(option),
                codec: "mov_text", channels: 0, selected: option == chosenSubtitle, isDefault: false,
                external: false, forced: option.hasMediaCharacteristic(.containsOnlyForcedSubtitles)
            ))
        }
        reports?.tracks(tracks: tracks)
    }

    private func bufferedEnd(_ item: AVPlayerItem) -> Double {
        let now = item.currentTime()
        for value in item.loadedTimeRanges {
            let range = value.timeRangeValue
            if range.containsTime(now) || range.start > now { return range.end.seconds.finiteOr(0) }
        }
        return now.seconds.finiteOr(0)
    }

    private static func chapters(of asset: AVURLAsset) async throws -> [EngineChapter] {
        let locales = try await asset.load(.availableChapterLocales)
        guard let locale = locales.first else { return [] }
        let groups = try await asset.loadChapterMetadataGroups(withTitleLocale: locale, containingItemsWithCommonKeys: [.commonKeyTitle])
        var chapters: [EngineChapter] = []
        for group in groups {
            let title = try? await group.items.first(where: { $0.commonKey == .commonKeyTitle })?.load(.stringValue)
            chapters.append(EngineChapter(startMs: Int64((group.timeRange.start.seconds.finiteOr(0) * 1000).rounded()), title: title))
        }
        return chapters
    }

    /// The option whose language is one of [languages]' spellings (en, eng, en-US), or nil.
    private static func option(in options: [AVMediaSelectionOption], for languages: [String]) -> AVMediaSelectionOption? {
        let wanted = Set(languages.map { $0.lowercased() })
        guard !wanted.isEmpty else { return nil }
        return options.first { option in
            let tag = (option.extendedLanguageTag ?? option.locale?.identifier ?? "").lowercased()
            let base = tag.split(separator: "-").first.map(String.init) ?? tag
            let code = option.locale?.language.languageCode
            return wanted.contains(tag) || wanted.contains(base)
                || (code.map { wanted.contains($0.identifier.lowercased()) || wanted.contains(($0.identifier(.alpha3) ?? "").lowercased()) } ?? false)
        }
    }

    /// The language as files and mpv name it, three letters (eng, ita), or the tag when it has none.
    private static func language(_ option: AVMediaSelectionOption) -> String {
        if let code = option.locale?.language.languageCode, let three = code.identifier(.alpha3) { return three }
        return option.extendedLanguageTag ?? ""
    }

    private static func title(_ option: AVMediaSelectionOption) -> String? {
        AVMetadataItem.metadataItems(from: option.commonMetadata, withKey: AVMetadataKey.commonKeyTitle, keySpace: .common).first?.stringValue
    }

    /// A four character code as text, "avc1" or "aac ".
    private static func fourCC(_ code: FourCharCode) -> String {
        let bytes = [UInt8((code >> 24) & 0xff), UInt8((code >> 16) & 0xff), UInt8((code >> 8) & 0xff), UInt8(code & 0xff)]
        return String(bytes: bytes, encoding: .ascii)?.trimmingCharacters(in: .whitespaces) ?? ""
    }

    /// The codec as FFmpeg names it, which is how the player's panels and choices read codecs.
    private static func codecName(_ code: FourCharCode) -> String {
        switch fourCC(code) {
        case "avc1", "avc3": return "h264"
        case "hvc1", "hev1", "dvh1", "dvhe": return "hevc"
        case "av01": return "av1"
        default: return fourCC(code)
        }
    }
}

extension AppleEngine: AVPictureInPictureControllerDelegate {
    func pictureInPictureControllerDidStartPictureInPicture(_ controller: AVPictureInPictureController) {
        onPictureInPictureChanged?(KotlinBoolean(value: true))
    }

    func pictureInPictureControllerDidStopPictureInPicture(_ controller: AVPictureInPictureController) {
        onPictureInPictureChanged?(KotlinBoolean(value: false))
    }

    func pictureInPictureController(_ controller: AVPictureInPictureController, failedToStartPictureInPictureWithError error: Error) {
        tmLogWarning("engines", "Picture in picture did not start: %@", error.localizedDescription)
        onPictureInPictureChanged?(KotlinBoolean(value: false))
    }

    /// The viewer tapped the corner window to come back: the player screen is still there, so
    /// there is nothing to rebuild.
    func pictureInPictureController(_ controller: AVPictureInPictureController, restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void) {
        completionHandler(true)
    }
}

extension AppleEngine: AVPlayerItemLegibleOutputPushDelegate {
    /// The text subtitle due now, handed to the player as plain text, as the rules ask of an engine
    /// without ASS markup.
    func legibleOutput(_ output: AVPlayerItemLegibleOutput, didOutputAttributedStrings strings: [NSAttributedString],
                       nativeSampleBuffers nativeSamples: [Any], forItemTime itemTime: CMTime) {
        let text = strings.map(\.string).joined(separator: "\n")
        let line: String? = text.isEmpty ? nil : text
        guard line != lastSubtitle else { return }
        lastSubtitle = line
        reports?.subtitleText(text: line)
    }
}

/// The view Apple's player draws in: an AVPlayerLayer laid out for the shape and the zoom the
/// player asks for, as MpvRecipes lays out mpv's.
final class AppleSurfaceView: UIView {
    let playerLayer = AVPlayerLayer()

    /// iOS's AirPlay button, kept in the corner out of sight and out of VoiceOver: the player's own
    /// row opens its list (AppleEngine.showAirPlayPicker), and an iPad anchors the list here.
    let routePicker: AVRoutePickerView = {
        let picker = AVRoutePickerView(frame: CGRect(x: 0, y: 0, width: 44, height: 44))
        picker.prioritizesVideoDevices = true
        picker.alpha = 0.02
        picker.isUserInteractionEnabled = false
        picker.isAccessibilityElement = false
        picker.accessibilityElementsHidden = true
        return picker
    }()

    var scale: CoreVideoScale = CoreVideoScale.fit { didSet { setNeedsLayout() } }
    var zoom: (Double, Double, Double) = (0, 0, 0) { didSet { setNeedsLayout() } }
    var videoSize: CGSize = .zero { didSet { setNeedsLayout() } }

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        clipsToBounds = true
        layer.addSublayer(playerLayer)
        addSubview(routePicker)
    }

    required init?(coder: NSCoder) { fatalError("not from a storyboard") }

    override func layoutSubviews() {
        super.layoutSubviews()
        routePicker.frame = CGRect(x: max(0, bounds.width - 60), y: 16, width: 44, height: 44)
        bringSubviewToFront(routePicker)
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        let area = bounds
        var frame = area
        if let ratio = scale.ratio?.doubleValue, ratio > 0 {
            // A fixed shape: the picture stretched to that ratio, letterboxed in the view.
            frame = AVMakeRect(aspectRatio: CGSize(width: ratio, height: 1), insideRect: area)
            playerLayer.videoGravity = .resize
        } else {
            switch scale {
            case CoreVideoScale.crop: playerLayer.videoGravity = .resizeAspectFill
            case CoreVideoScale.stretch: playerLayer.videoGravity = .resize
            default: playerLayer.videoGravity = .resizeAspect
            }
        }
        playerLayer.setAffineTransform(.identity)
        playerLayer.frame = frame
        // The free zoom over the shape, as mpv's video-zoom (a power of two) and video-pan (a
        // fraction of the picture's size).
        let (amount, panX, panY) = zoom
        if amount != 0 || panX != 0 || panY != 0 {
            let factor = pow(2, amount)
            let shown = videoSize.width > 0 && scale.ratio == nil && scale == CoreVideoScale.fit
                ? AVMakeRect(aspectRatio: videoSize, insideRect: frame).size
                : frame.size
            let move = CGAffineTransform(translationX: CGFloat(panX) * shown.width * factor, y: CGFloat(panY) * shown.height * factor)
            playerLayer.setAffineTransform(CGAffineTransform(scaleX: factor, y: factor).concatenating(move))
        }
        CATransaction.commit()
    }
}

private extension Double {
    func finiteOr(_ fallback: Double) -> Double { isFinite ? self : fallback }
}
