import Libmpv
import TMPlayerKit
import UIKit

/// libmpv as a TMPlayer video engine (docs/IOS.md). It carries out the commands of
/// the rules (VideoEngine) and reports through EngineReports; what each command sets in mpv is
/// what upstream's desktop engine sets (desktop/.../player/MpvPlaybackEngine.kt), with the shapes
/// and subtitle styles from the same shared code (MpvRecipes). It draws into a Metal layer through
/// MoltenVK and plays a path, a URL or a `tdfile://` stream of TDLib's download (TdStreamProtocol).
final class MpvEngine: NSObject, VideoEngine {
    private let view = MpvSurfaceView()
    private var mpv: OpaquePointer?
    private let events = DispatchQueue(label: "org.tmplayer.mpv.events", qos: .userInitiated)
    private var observers: [NSObjectProtocol] = []

    // The reports of the file now open, read on the events queue and set on the main thread.
    private let reportsLock = NSLock()
    private var currentReports: EngineReports?
    private var reports: EngineReports? {
        get { reportsLock.lock(); defer { reportsLock.unlock() }; return currentReports }
        set { reportsLock.lock(); currentReports = newValue; reportsLock.unlock() }
    }

    // What mpv last said, on the events queue; the time goes out a few times a second.
    private var position = 0.0
    private var duration = 0.0
    private var buffered = 0.0
    private var lastSent = Date.distantPast

    // What the commands set, on the main thread.
    /// The size, in pixels, mpv set its video output up at, as it reported it at the last
    /// MPV_EVENT_VIDEO_RECONFIG; nil while it is being set up again.
    private var drawnSize: CGSize?
    /// The layer size the output was last set up again for, so a size mpv does not take is asked
    /// for once, not over and over.
    private var rebuiltFor: CGSize?
    /// Whether the file now open has started playing (its first MPV_EVENT_PLAYBACK_RESTART). Until
    /// then the output is not set up again for a new size: turning the video track off and on
    /// while mpv was still starting left it never starting, the player stuck on Buffering (a wide
    /// video opened on an upright iPhone, which turns as the player comes up). The size is caught
    /// up with once it plays.
    private var started = false
    private var boost = false
    private var volume: Int32 = 100
    private var zoomed = false
    private var subtitleStyle: CoreSubtitleStyle?

    // Whether mpv draws text subtitles, or leaves them to the player (setTextSubtitlesShown); set
    // on the main thread, read on the events queue as the subtitle track changes.
    private let subtitlesLock = NSLock()
    private var textSubtitlesDrawn = true

    override init() {
        super.init()
        watchLifecycle()
        view.onResize = { [weak self] in self?.followSize() }
    }

    // VideoEngine

    var surface: UIView { view }

    var capabilities: Set<EngineCapability> {
        [.frameStep, .zoom, .downmix, .volumeBoost, .subtitleStyle, .subtitleDelay, .audioDelay,
         .externalSubtitles, .screenshot, .abLoop]
    }

    func open(source: String, startAtMs: Int64, options: EngineOpenOptions, reports: EngineReports) {
        guard let handle = mpv ?? start() else {
            reports.failed(message: "mpv could not start")
            return
        }
        self.reports = reports
        // On the main thread, in order with the start that lets it go (MPV_EVENT_PLAYBACK_RESTART).
        let opening = { [weak self] in
            self?.started = false
            self?.view.holdsDrawableSize = true
            NotificationCenter.default.post(name: .videoEngineStarting, object: nil)
        }
        if Thread.isMainThread { opening() } else { DispatchQueue.main.async(execute: opening) }
        events.async { [weak self] in
            self?.position = Double(startAtMs) / 1000
            self?.duration = 0
            self?.buffered = 0
        }
        boost = options.volumeBoost
        volume = options.volume
        if options.softwareDecoding { set(handle, "hwdec", "no") }
        set(handle, "volume-max", boost ? "\(MpvRecipes.shared.boostedVolumeMax)" : "100")
        set(handle, "volume", String(format: "%.1f", mpvVolume(options.volume)))
        set(handle, "mute", options.muted || MpvEngine.mutedForCheck ? "yes" : "no")
        set(handle, "speed", String(format: "%.3f", options.speed))
        set(handle, "audio-channels", options.downmix ? "stereo" : "auto-safe")
        set(handle, "sub-delay", seconds(options.subtitleDelayMs))
        set(handle, "audio-delay", seconds(options.audioDelayMs))
        set(handle, "alang", options.audioLanguages.joined(separator: ","))
        set(handle, "slang", options.subtitleLanguages.joined(separator: ","))
        set(handle, "aid", "auto")
        set(handle, "sid", options.subtitlesOff ? "no" : "auto")
        applyReadAhead(handle, options)
        set(handle, "start", seconds(startAtMs))
        set(handle, "pause", "no")
        command(handle, ["loadfile", source, "replace"])
    }

    /// Android's read ahead (DefaultLoadControl through EngineOpenOptions): mpv's cache on for the
    /// player's own streams, which mpv does not count as network ones and so read about a second
    /// ahead of; filled to the seconds asked, read again only under the lower figure, within the
    /// bytes Android allows the device ahead and behind, and after running dry the seconds Android
    /// gathers before playing on. Not given, mpv's own defaults stand.
    private func applyReadAhead(_ handle: OpaquePointer, _ options: EngineOpenOptions) {
        guard options.readAheadSeconds > 0 else { return }
        set(handle, "cache", "yes")
        set(handle, "cache-secs", "\(options.readAheadSeconds)")
        set(handle, "demuxer-hysteresis-secs", "\(options.readAgainBelowSeconds)")
        if options.aheadBytes > 0 { set(handle, "demuxer-max-bytes", "\(options.aheadBytes)") }
        if options.behindBytes > 0 { set(handle, "demuxer-max-back-bytes", "\(options.behindBytes)") }
        if options.rebufferSeconds > 0 { set(handle, "cache-pause-wait", String(format: "%.1f", options.rebufferSeconds)) }
    }

    func play() { setProperty("pause", "no") }

    func pause() { setProperty("pause", "yes") }

    func stop() {
        guard let handle = mpv else { return }
        command(handle, ["stop"])
        reports = nil
    }

    func seekTo(positionMs: Int64, exact: Bool) {
        guard let handle = mpv else { return }
        command(handle, ["seek", seconds(positionMs), exact ? "absolute+exact" : "absolute+keyframes"])
    }

    func seekBy(deltaMs: Int64) {
        guard let handle = mpv else { return }
        command(handle, ["seek", seconds(deltaMs), "relative+keyframes"])
    }

    func frameStep(forward: Bool) {
        guard let handle = mpv else { return }
        command(handle, [forward ? "frame-step" : "frame-back-step"])
    }

    func setSpeed(speed: Float) { setProperty("speed", String(format: "%.3f", speed)) }

    func setVolume(percent: Int32) {
        volume = percent
        setProperty("volume", String(format: "%.1f", mpvVolume(percent)))
    }

    func setMuted(muted: Bool) { setProperty("mute", muted || MpvEngine.mutedForCheck ? "yes" : "no") }

    /// `-mpvAudio muted`: the sound goes to the real output, silent, so a device check measures
    /// the audio clock with headphones in.
    private static let mutedForCheck = UserDefaults.standard.string(forKey: "mpvAudio") == "muted"

    func selectTrack(type: IosTrackType, id: Int32) {
        let name = type == .audio ? "aid" : type == .subtitle ? "sid" : "vid"
        if UserDefaults.standard.integer(forKey: "perfLog") > 0 { log("\(name) asked: \(id)") }
        setProperty(name, id < 0 ? "no" : String(id))
    }

    func setScale(scale: CoreVideoScale) {
        let width = Int32(property("width") ?? "") ?? 0
        let height = Int32(property("height") ?? "") ?? 0
        for item in MpvRecipes.shared.shape(scale: scale, stereo: CoreStereo3d.companion.OFF, width: width, height: height) {
            setProperty(item.name, item.value)
        }
    }

    /// As the desktop's setZoom: zoom and pan, and while zoomed the window, not the picture,
    /// holds the subtitles.
    func setZoom(zoom: Double, panX: Double, panY: Double) {
        let finite = { (value: Double) in value.isFinite ? value : 0 }
        setProperty("video-zoom", String(format: "%.4f", finite(zoom)))
        setProperty("video-pan-x", String(format: "%.4f", finite(panX)))
        setProperty("video-pan-y", String(format: "%.4f", finite(panY)))
        let now = finite(zoom) > 0
        guard now != zoomed else { return }
        zoomed = now
        let word = now ? "yes" : "no"
        setProperty("sub-use-margins", word)
        setProperty("sub-ass-use-margins", word)
        setProperty("sub-ass-scale-with-window", word)
    }

    func setDownmix(stereo: Bool) { setProperty("audio-channels", stereo ? "stereo" : "auto-safe") }

    /// As the desktop's applyVolumeBoost: a higher volume-max, with full volume moved up to it.
    func setVolumeBoost(on: Bool) {
        boost = on
        if on {
            setProperty("volume-max", "\(MpvRecipes.shared.boostedVolumeMax)")
            setProperty("volume", String(format: "%.1f", mpvVolume(volume)))
        } else {
            setProperty("volume", String(format: "%.1f", mpvVolume(volume)))
            setProperty("volume-max", "100")
        }
    }

    /// Not yet: the desktop's night mode filters are its own; listed as missing in capabilities.
    func setNightMode(on: Bool) {}

    /// Not yet: listed as missing in capabilities.
    func setStereo3d(stereo: CoreStereo3d) {}

    func setSubtitleStyle(style: CoreSubtitleStyle) {
        subtitleStyle = style
        for item in MpvRecipes.shared.subtitleStyle(style: style, zoomed: zoomed) {
            let set = setProperty(item.name, item.value)
            if !set && item.name == "sub-border-style" && item.value == "background-box" {
                for fallback in MpvRecipes.shared.boxFallback(style: style) {
                    setProperty(fallback.name, fallback.value)
                }
            }
        }
    }

    /// mpv hands out a text subtitle as it is due (`sub-text/ass`, observed), drawn or not; a
    /// picture one it can only draw. So while the player draws the text, `sub-visibility` is on
    /// only for a picture track.
    func setTextSubtitlesShown(shown: Bool) {
        subtitlesLock.lock()
        textSubtitlesDrawn = shown
        subtitlesLock.unlock()
        showSubtitles(codec: property("current-tracks/sub/codec"))
    }

    private func showSubtitles(codec: String?) {
        subtitlesLock.lock()
        let drawn = textSubtitlesDrawn
        subtitlesLock.unlock()
        let picture = codec.map { MpvEngine.pictureSubtitles.contains($0) } ?? false
        setProperty("sub-visibility", drawn || picture ? "yes" : "no")
    }

    /// FFmpeg's names of the subtitle formats made of pictures.
    private static let pictureSubtitles: Set<String> = [
        "hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle", "xsub", "dvb_teletext",
    ]

    func setSubtitleDelay(ms: Int64) { setProperty("sub-delay", seconds(ms)) }

    func setAudioDelay(ms: Int64) { setProperty("audio-delay", seconds(ms)) }

    func addSubtitle(path: String) -> Bool {
        guard let handle = mpv else { return false }
        return command(handle, ["sub-add", path, "select"])
    }

    /// The frame's pixels from mpv (`screenshot-raw`, subtitles drawn on when asked), written as a
    /// PNG by UIKit: MPVKit's FFmpeg has no image encoder, so mpv cannot write the file itself. On a
    /// queue of its own; the main thread hears once the file is there.
    func screenshot(path: String, withSubtitles: Bool, done: @escaping (KotlinBoolean) -> Void) {
        guard let handle = mpv else {
            done(KotlinBoolean(bool: false))
            return
        }
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let saved = self?.saveFrame(handle, to: path, withSubtitles: withSubtitles) ?? false
            DispatchQueue.main.async { done(KotlinBoolean(bool: saved)) }
        }
    }

    private func saveFrame(_ handle: OpaquePointer, to path: String, withSubtitles: Bool) -> Bool {
        let words: [String] = ["screenshot-raw", withSubtitles ? "subtitles" : "video", "bgra"]
        var arguments: [UnsafePointer<CChar>?] = words.map { UnsafePointer(strdup($0)) }
        arguments.append(nil)
        defer { arguments.forEach { free(UnsafeMutablePointer(mutating: $0)) } }
        var result = mpv_node()
        guard check(mpv_command_ret(handle, &arguments, &result), "screenshot-raw") else { return false }
        defer { mpv_free_node_contents(&result) }
        guard result.format == MPV_FORMAT_NODE_MAP, let list = result.u.list?.pointee else { return false }
        var width = 0, height = 0, stride = 0
        var pixels: Data?
        for index in 0..<Int(list.num) {
            guard let key = list.keys?[index].map({ String(cString: $0) }), let value = list.values?[index] else { continue }
            switch (key, value.format) {
            case ("w", MPV_FORMAT_INT64): width = Int(value.u.int64)
            case ("h", MPV_FORMAT_INT64): height = Int(value.u.int64)
            case ("stride", MPV_FORMAT_INT64): stride = Int(value.u.int64)
            case ("data", MPV_FORMAT_BYTE_ARRAY):
                if let bytes = value.u.ba?.pointee, let data = bytes.data {
                    pixels = Data(bytes: data, count: bytes.size)
                }
            default: break
            }
        }
        guard width > 0, height > 0, stride >= width * 4, let pixels, pixels.count >= stride * height,
              let provider = CGDataProvider(data: pixels as CFData),
              let image = CGImage(
                  width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: stride,
                  space: CGColorSpaceCreateDeviceRGB(),
                  bitmapInfo: CGBitmapInfo(rawValue: CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.noneSkipFirst.rawValue),
                  provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent
              ),
              let png = UIImage(cgImage: image).pngData()
        else { return false }
        let url = URL(fileURLWithPath: path)
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        return (try? png.write(to: url, options: .atomic)) != nil
    }

    func setAbLoop(startMs: Int64, endMs: Int64) {
        if startMs < 0 || endMs < 0 {
            setProperty("ab-loop-b", "no")
            setProperty("ab-loop-a", "no")
        } else {
            setProperty("ab-loop-a", seconds(startMs))
            setProperty("ab-loop-b", seconds(endMs))
        }
    }

    /// Read back from mpv, as the desktop's details: what is in force, not what was asked for.
    func facts() -> EngineFacts {
        let facts = EngineFacts()
        facts.videoWidth = Int32(property("video-params/w") ?? "") ?? 0
        facts.videoHeight = Int32(property("video-params/h") ?? "") ?? 0
        facts.framesPerSecond = Double(property("container-fps") ?? property("estimated-vf-fps") ?? "") ?? 0
        facts.videoCodec = property("video-codec") ?? ""
        facts.pixelFormat = property("video-params/pixelformat") ?? ""
        let hardware = property("hwdec-current") ?? ""
        facts.hardwareDecoder = hardware == "no" ? "" : hardware
        facts.audioCodec = property("audio-codec-name") ?? ""
        facts.audioChannels = Int32(property("audio-params/channel-count") ?? "") ?? 0
        facts.sampleRate = Int32(property("audio-params/samplerate") ?? "") ?? 0
        facts.container = property("file-format") ?? ""
        facts.bufferedSeconds = Double(property("demuxer-cache-duration") ?? "") ?? 0
        facts.videoOutput = property("current-vo") ?? ""
        facts.audioOutput = property("current-ao") ?? ""
        facts.engine = "mpv"
        facts.engineVersion = property("mpv-version") ?? ""
        facts.decoder = "FFmpeg"
        facts.decoderVersion = property("ffmpeg-version") ?? ""
        facts.speed = Double(property("speed") ?? "") ?? 1
        let scale = boost ? Double(MpvRecipes.shared.boostedVolumeMax) / 100 : 1
        facts.volume = Int32(((Double(property("volume") ?? "") ?? 100) / scale).rounded())
        facts.muted = property("mute") == "yes"
        facts.subtitleDelayMs = milliseconds(Double(property("sub-delay") ?? "") ?? 0)
        facts.audioDelayMs = milliseconds(Double(property("audio-delay") ?? "") ?? 0)
        return facts
    }

    /// Off the main thread: mpv_terminate_destroy waits for mpv's own thread, which may be starting
    /// the audio output, and that needs the main thread. The layer mpv draws into is kept alive
    /// until mpv has let go of it; the audio session goes once nothing plays.
    func close() {
        observers.forEach(NotificationCenter.default.removeObserver)
        observers.removeAll()
        reports = nil
        guard let handle = mpv else { return }
        mpv = nil
        mpv_set_wakeup_callback(handle, nil, nil)
        let layer = view.videoLayer
        DispatchQueue.global(qos: .userInitiated).async {
            mpv_terminate_destroy(handle)
            withExtendedLifetime(layer) {}
            PlaybackAudio.deactivate()
        }
    }

    // Starting mpv, once, at the first open.

    private func start() -> OpaquePointer? {
        guard let handle = mpv_create() else { return nil }
        // mpv draws into the layer it is given as a pointer value ("wid").
        var layer = Int64(Int(bitPattern: Unmanaged.passUnretained(view.videoLayer).toOpaque()))
        check(mpv_set_option(handle, "wid", MPV_FORMAT_INT64, &layer), "wid")
        set(handle, "vo", "gpu-next")
        set(handle, "gpu-api", "vulkan")
        set(handle, "gpu-context", "moltenvk")
        // Hardware decoding where VideoToolbox can; mpv falls back to software by itself.
        set(handle, "hwdec", "videotoolbox")
        set(handle, "keep-open", "yes")
        set(handle, "subs-fallback", "yes")
        // On the simulator, screenshots from the decoded frame, subtitles drawn in software: the
        // video output's own reads the picture back from the GPU through MoltenVK, which the
        // simulator's Metal stops the app on (a buffer over host memory, MTLSimDevice). On a device
        // the video output's: a frame VideoToolbox decoded could not be turned into a picture in
        // software ("libswscale initialization failed", iPad, 2026-11-14), so saving a frame failed.
        #if targetEnvironment(simulator)
        set(handle, "screenshot-sw", "yes")
        #endif
        // The audio unit, with AVFoundation's sample buffer renderer as the fallback. The renderer
        // came first while the audio session was made on the main thread, where starting the audio
        // unit waited on the audio server until Core Audio aborted the app; the session is now
        // ready, off the main thread, before the player opens. With the renderer, mpv's sound
        // clock ran ahead after each seek or resume: 1.9 to 2.5 s apart, the picture then shown
        // at 40 frames a second until it caught up (iPad, 2026-11-14, `-perfLog`'s sync lines);
        // the audio unit keeps the two within a millisecond.
        set(handle, "ao", "audiounit,avfoundation")
        // `-mpvAudio no` plays without sound, for a simulator whose audio server stopped answering
        // and for measurements on a device with headphones in. In a release build too, so its
        // numbers compare with a debug build's: a launch argument no viewer can pass.
        if UserDefaults.standard.string(forKey: "mpvAudio") == "no" {
            set(handle, "ao", "null")
        }
        // `-mpvAo <outputs>`: other audio outputs for a device check, as mpv's `ao` takes them.
        if let ao = UserDefaults.standard.string(forKey: "mpvAo") {
            set(handle, "ao", ao)
        }
        // mpv's fast profile: the picture scaled bilinearly and not dithered, as Android's player
        // hands the decoder's frames to the screen to scale. mpv's own scalers took 9 to 14 ms of
        // the GPU for each 1080p frame on the iPhone 13 against 0.4 to 1.3 ms, and a quarter more
        // CPU (26 % against 19 %), for an enlargement of a few per cent a phone's screen does not
        // show (2026-11-13). `-mpvProfile <name>` picks another in a debug build, `none` for mpv's.
        var profile = "fast"
        #if DEBUG
        profile = UserDefaults.standard.string(forKey: "mpvProfile") ?? profile
        #endif
        if profile != "none" { set(handle, "profile", profile) }
        // libplacebo's compiled shaders and pipelines, kept in Caches: mpv's own place for them
        // (~~cache/) is no folder an iOS app may write, so every video opened compiled them all
        // again, one thread busy for about 8 s at up to 440 ms a second (iPad, 2026-11-14). The
        // same shaders, so the picture does not change; iOS may empty Caches, and mpv then makes
        // them again.
        if let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first {
            let folder = caches.appendingPathComponent("mpv", isDirectory: true)
            try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            set(handle, "gpu-shader-cache", "yes")
            set(handle, "gpu-shader-cache-dir", folder.path)
        }
        #if DEBUG
        // `-mpvLog <level>` (v, debug) for a device check; warnings otherwise.
        check(mpv_request_log_messages(handle, UserDefaults.standard.string(forKey: "mpvLog") ?? "warn"), "log level")
        #else
        check(mpv_request_log_messages(handle, "warn"), "log level")
        #endif
        guard check(mpv_initialize(handle), "initialize") else {
            mpv_terminate_destroy(handle)
            return nil
        }
        mpv = handle
        mpv_set_wakeup_callback(handle, { context in
            guard let context else { return }
            Unmanaged<MpvEngine>.fromOpaque(context).takeUnretainedValue().drainEvents()
        }, Unmanaged.passUnretained(self).toOpaque())
        TdStreamProtocol.register(on: handle)
        MediaSourceProtocol.register(on: handle)
        for (name, format) in [
            ("time-pos", MPV_FORMAT_DOUBLE), ("duration", MPV_FORMAT_DOUBLE), ("demuxer-cache-time", MPV_FORMAT_DOUBLE),
            ("pause", MPV_FORMAT_FLAG), ("paused-for-cache", MPV_FORMAT_FLAG), ("seeking", MPV_FORMAT_FLAG),
            ("eof-reached", MPV_FORMAT_FLAG), ("track-list", MPV_FORMAT_NONE), ("chapter-list", MPV_FORMAT_NONE),
            ("sub-text/ass", MPV_FORMAT_STRING), ("current-tracks/sub/codec", MPV_FORMAT_STRING), ("sid", MPV_FORMAT_STRING),
        ] {
            mpv_observe_property(handle, 0, name, format)
        }
        return handle
    }

    /// MPVKit's MoltenVK context reads the layer's size only when mpv sets its video output up
    /// (moltenvk_reconfig), never on a resize, so after the screen turns, or the window changes in
    /// Split View or Stage Manager, mpv would go on drawing at the old size. Turning the video track
    /// off and on sets the output up again at the new size, and mpv draws the current frame again,
    /// paused or not.
    ///
    /// The size compared is the one mpv reports for its output, not the layer's when the report
    /// arrives: on a device the first frame can come before the player's views have their size
    /// (the screen still turning sideways), an output set up at no size draws nothing, and the
    /// layer has its real size by the time the main thread hears of it.
    private func followSize() {
        let now = view.videoLayer.drawableSize
        // mpv reports its output a pixel or two off the layer's size; that is the same size.
        func same(_ a: CGSize, _ b: CGSize) -> Bool { abs(a.width - b.width) <= 2 && abs(a.height - b.height) <= 2 }
        guard mpv != nil, started, let drawn = drawnSize, now.width > 1, now.height > 1, !same(drawn, now), rebuiltFor != now else { return }
        drawnSize = nil
        rebuiltFor = now
        let track = property("vid") ?? "auto"
        setProperty("vid", "no")
        setProperty("vid", track == "no" ? "auto" : track)
    }

    // mpv's events

    /// Called on mpv's thread when events wait; they are read on a queue of ours.
    private func drainEvents() {
        events.async { [weak self] in
            guard let self, let handle = self.mpv else { return }
            while let event = mpv_wait_event(handle, 0), event.pointee.event_id != MPV_EVENT_NONE {
                self.handle(event.pointee)
            }
        }
    }

    private func handle(_ event: mpv_event) {
        switch event.event_id {
        case MPV_EVENT_LOG_MESSAGE:
            if let message = event.data?.assumingMemoryBound(to: mpv_event_log_message.self).pointee {
                let prefix = message.prefix.map { String(cString: $0) } ?? "mpv"
                let text = message.text.map { String(cString: $0) } ?? ""
                log("[\(prefix)] \(text.trimmingCharacters(in: .whitespacesAndNewlines))")
            }
        case MPV_EVENT_FILE_LOADED:
            log("file loaded: \(property("video-codec") ?? "no video"), \(property("audio-codec-name") ?? "no audio")")
            reports?.tracks(tracks: tracks())
            reports?.chapters(chapters: chapters())
            duration = Double(property("duration") ?? "") ?? duration
            reports?.opened(durationMs: milliseconds(duration))
        case MPV_EVENT_VIDEO_RECONFIG:
            let width = Int32(property("dwidth") ?? "") ?? 0
            let height = Int32(property("dheight") ?? "") ?? 0
            if width > 0, height > 0 { reports?.videoSize(width: width, height: height) }
            // The output's own size, read now: the layer may have changed since mpv read it.
            let output = CGSize(width: Double(property("osd-width") ?? "") ?? 0, height: Double(property("osd-height") ?? "") ?? 0)
            log("video output \(Int(output.width))x\(Int(output.height))")
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                self.drawnSize = output
                self.followSize()
            }
        case MPV_EVENT_PLAYBACK_RESTART:
            log("playing, hwdec \(property("hwdec-current") ?? "none")")
            DispatchQueue.main.async { [weak self] in
                guard let self, !self.started else { return }
                self.started = true
                // A size that changed while it was starting: the drawable takes it now.
                self.view.holdsDrawableSize = false
                self.followSize()
                NotificationCenter.default.post(name: .videoEngineStarted, object: nil)
            }
            position = Double(property("time-pos") ?? "") ?? position
            reports?.seekLanded(positionMs: milliseconds(position))
            reports?.buffering(buffering: false)
        case MPV_EVENT_PROPERTY_CHANGE:
            if let change = event.data?.assumingMemoryBound(to: mpv_event_property.self).pointee {
                changed(change)
            }
        case MPV_EVENT_END_FILE:
            // A file that ended or failed before it played lets the size go too.
            DispatchQueue.main.async { [weak self] in
                self?.view.holdsDrawableSize = false
                NotificationCenter.default.post(name: .videoEngineStarted, object: nil)
            }
            if let end = event.data?.assumingMemoryBound(to: mpv_event_end_file.self).pointee {
                log("end of file, reason \(end.reason.rawValue), error \(end.error)")
                if end.reason == MPV_END_FILE_REASON_ERROR {
                    reports?.failed(message: String(cString: mpv_error_string(end.error)))
                } else if end.reason == MPV_END_FILE_REASON_EOF {
                    reports?.ended()
                }
            }
        default:
            break
        }
    }

    /// When the read ahead was last logged, for a device check launched with `-perfLog`.
    private var readAheadLoggedAt = Date.distantPast

    /// How far ahead mpv has read, and how the picture keeps with the sound, every `-perfLog`
    /// seconds (5 at most): mpv's audio and video gap (`avsync`, positive with the sound ahead)
    /// and its frames dropped, by the output and by the decoder, and drawn late.
    private func logReadAhead() {
        let every = UserDefaults.standard.integer(forKey: "perfLog")
        guard every > 0, -readAheadLoggedAt.timeIntervalSinceNow >= Double(min(every, 5)) else { return }
        readAheadLoggedAt = Date()
        tmLog("mpv", "read ahead: %.1f s, GPU per new frame %.2f ms; avsync %@ s, dropped %@ (decoder %@), late %@, ao %@; sound %@, subtitles %@",
              buffered - position, gpuPerFrameMs(), property("avsync") ?? "-", property("frame-drop-count") ?? "-",
              property("decoder-frame-drop-count") ?? "-", property("vo-delayed-frame-count") ?? "-", property("current-ao") ?? "-",
              property("current-tracks/audio/lang") ?? "-", property("current-tracks/sub/lang") ?? "off")
    }

    /// With `-perfLog`: each moment the picture and the sound are 40 ms or more apart, as mpv
    /// sees them, with its frames dropped and drawn late so far.
    private func logSync() {
        guard UserDefaults.standard.integer(forKey: "perfLog") > 0,
              let gap = Double(property("avsync") ?? ""), abs(gap) >= 0.04 else { return }
        tmLog("mpv", "sync: %.3f s apart at %.2f s, dropped %@, late %@", gap, position,
              property("frame-drop-count") ?? "-", property("vo-delayed-frame-count") ?? "-")
    }

    /// What drawing one new frame takes on the GPU, the passes' average times added up (mpv's
    /// `vo-passes`, in nanoseconds); 0 when mpv has not timed any yet.
    private func gpuPerFrameMs() -> Double {
        guard let handle = mpv else { return 0 }
        var node = mpv_node()
        guard mpv_get_property(handle, "vo-passes", MPV_FORMAT_NODE, &node) >= 0 else { return 0 }
        defer { mpv_free_node_contents(&node) }
        guard node.format == MPV_FORMAT_NODE_MAP, let map = node.u.list?.pointee else { return 0 }
        var total: Int64 = 0
        for i in 0..<Int(map.num) where String(cString: map.keys[i]!) == "fresh" {
            guard map.values[i].format == MPV_FORMAT_NODE_ARRAY, let passes = map.values[i].u.list?.pointee else { continue }
            for j in 0..<Int(passes.num) {
                guard passes.values[j].format == MPV_FORMAT_NODE_MAP, let pass = passes.values[j].u.list?.pointee else { continue }
                for k in 0..<Int(pass.num) where String(cString: pass.keys[k]!) == "avg" && pass.values[k].format == MPV_FORMAT_INT64 {
                    total += pass.values[k].u.int64
                }
            }
        }
        return Double(total) / 1_000_000
    }

    /// One of the observed properties changed, read on the events queue.
    private func changed(_ change: mpv_event_property) {
        let name = String(cString: change.name)
        switch (name, change.format) {
        case ("time-pos", MPV_FORMAT_DOUBLE):
            position = change.data.assumingMemoryBound(to: Double.self).pointee
            progress(force: false)
            logSync()
        case ("duration", MPV_FORMAT_DOUBLE):
            duration = change.data.assumingMemoryBound(to: Double.self).pointee
            progress(force: true)
        case ("demuxer-cache-time", MPV_FORMAT_DOUBLE):
            buffered = change.data.assumingMemoryBound(to: Double.self).pointee
            logReadAhead()
        case ("pause", MPV_FORMAT_FLAG):
            reports?.paused(paused: flag(change))
        case ("paused-for-cache", MPV_FORMAT_FLAG), ("seeking", MPV_FORMAT_FLAG):
            if UserDefaults.standard.integer(forKey: "perfLog") > 0 { log("\(name) \(flag(change))") }
            reports?.buffering(buffering: flag(change))
        case ("eof-reached", MPV_FORMAT_FLAG):
            // keep-open stops on the last frame instead of ending the file: this is the end.
            if flag(change) { reports?.ended() }
        case ("track-list", _):
            reports?.tracks(tracks: tracks())
        case ("chapter-list", _):
            reports?.chapters(chapters: chapters())
        case ("sub-text/ass", MPV_FORMAT_STRING):
            reports?.subtitleText(text: string(change))
        case ("sub-text/ass", _):
            reports?.subtitleText(text: nil)
        case ("sid", MPV_FORMAT_STRING):
            if UserDefaults.standard.integer(forKey: "perfLog") > 0 { log("subtitle track now \(string(change) ?? "-")") }
        case ("current-tracks/sub/codec", MPV_FORMAT_STRING):
            showSubtitles(codec: string(change))
        case ("current-tracks/sub/codec", _):
            showSubtitles(codec: nil)
        default:
            break
        }
    }

    private func progress(force: Bool) {
        let now = Date()
        if !force, now.timeIntervalSince(lastSent) < 0.25 { return }
        lastSent = now
        reports?.progress(positionMs: milliseconds(position), durationMs: milliseconds(duration), bufferedMs: milliseconds(buffered))
    }

    /// Every track of the file, as the rules describe one.
    private func tracks() -> [EngineTrack] {
        let count = Int(property("track-list/count") ?? "") ?? 0
        return (0..<count).compactMap { index in
            let item = "track-list/\(index)"
            guard let id = Int32(property("\(item)/id") ?? "") else { return nil }
            let type: IosTrackType
            switch property("\(item)/type") {
            case "audio": type = .audio
            case "sub": type = .subtitle
            case "video": type = .video
            default: return nil
            }
            return EngineTrack(
                id: id,
                type: type,
                title: property("\(item)/title"),
                language: property("\(item)/lang"),
                codec: property("\(item)/codec"),
                channels: Int32(property("\(item)/demux-channel-count") ?? "") ?? 0,
                selected: property("\(item)/selected") == "yes",
                isDefault: property("\(item)/default") == "yes",
                external: property("\(item)/external") == "yes",
                forced: property("\(item)/forced") == "yes"
            )
        }
    }

    private func chapters() -> [EngineChapter] {
        let count = Int(property("chapter-list/count") ?? "") ?? 0
        return (0..<count).map { index in
            EngineChapter(
                startMs: milliseconds(Double(property("chapter-list/\(index)/time") ?? "") ?? 0),
                title: property("chapter-list/\(index)/title")
            )
        }
    }

    private func watchLifecycle() {
        let center = NotificationCenter.default
        // Without a visible layer the video output must stop, or it comes back black. Whether the
        // sound goes on is the player's to say (the rules: an engine carries out commands), as
        // Android's player keeps it for its background playback.
        observers.append(center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            self?.setProperty("vid", "no")
        })
        observers.append(center.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
            self?.setProperty("vid", "auto")
        })
    }

    // libmpv's calls

    /// The volume the viewer sees, 0 to 100, as mpv's: lifted to volume-max with Volume boost on.
    private func mpvVolume(_ percent: Int32) -> Double {
        Double(percent) * (boost ? Double(MpvRecipes.shared.boostedVolumeMax) / 100 : 1)
    }

    private func seconds(_ ms: Int64) -> String { String(format: "%.3f", Double(ms) / 1000) }

    private func milliseconds(_ seconds: Double) -> Int64 { seconds.isFinite ? Int64((seconds * 1000).rounded()) : 0 }

    private func flag(_ change: mpv_event_property) -> Bool {
        change.data.assumingMemoryBound(to: Int32.self).pointee != 0
    }

    /// A string property's value: MPV_FORMAT_STRING's data points at the C string's pointer.
    private func string(_ change: mpv_event_property) -> String? {
        guard let text = change.data?.assumingMemoryBound(to: UnsafePointer<CChar>?.self).pointee else { return nil }
        return String(cString: text)
    }

    private func property(_ name: String) -> String? {
        guard let handle = mpv, let value = mpv_get_property_string(handle, name) else { return nil }
        defer { mpv_free(value) }
        return String(cString: value)
    }

    @discardableResult
    private func setProperty(_ name: String, _ value: String) -> Bool {
        guard let handle = mpv else { return false }
        return check(mpv_set_property_string(handle, name, value), name)
    }

    private func set(_ handle: OpaquePointer, _ name: String, _ value: String) {
        check(mpv_set_property_string(handle, name, value), name)
    }

    @discardableResult
    private func command(_ handle: OpaquePointer, _ words: [String]) -> Bool {
        var arguments: [UnsafePointer<CChar>?] = words.map { UnsafePointer(strdup($0)) }
        arguments.append(nil)
        defer { arguments.forEach { free(UnsafeMutablePointer(mutating: $0)) } }
        return check(mpv_command(handle, &arguments), words.first ?? "command")
    }

    @discardableResult
    private func check(_ status: Int32, _ what: String) -> Bool {
        if status >= 0 { return true }
        log("\(what) failed: \(String(cString: mpv_error_string(status)))")
        return false
    }

    private func log(_ text: String) {
        tmLog("mpv", "%@", text)
    }
}

/// The view mpv draws in: a Metal layer that follows the view's size without implicit animation.
final class MpvSurfaceView: UIView {
    let videoLayer = MpvMetalLayer()
    /// Called after the view changes size.
    var onResize: (() -> Void)?
    private var laidOutSize = CGSize.zero

    /// While true the layer follows the view on the screen but keeps the size mpv draws at: a
    /// drawable that changed size while mpv was making its first frame (the screen turning as
    /// the player comes up) left mpv never starting. Let go, the size catches up at once.
    var holdsDrawableSize = false {
        didSet {
            guard oldValue, !holdsDrawableSize else { return }
            laidOutSize = .zero
            setNeedsLayout()
            layoutIfNeeded()
        }
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        videoLayer.backgroundColor = UIColor.black.cgColor
        videoLayer.framebufferOnly = true
        // Until mpv draws at a new size, the last frame keeps its proportions instead of stretching.
        videoLayer.contentsGravity = .resizeAspect
        layer.addSublayer(videoLayer)
    }

    required init?(coder: NSCoder) {
        nil
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        // Follows the window as it changes size (rotation, Split View, Stage Manager); no implicit
        // animation, or the video lags behind the window. mpv hears of the new size from the
        // engine (onResize, MpvEngine.followSize).
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        let scale = window?.screen.nativeScale ?? traitCollection.displayScale
        videoLayer.contentsScale = scale
        videoLayer.frame = bounds
        // The size mpv renders at (MoltenVK reads the drawable size), in pixels.
        if !holdsDrawableSize {
            videoLayer.drawableSize = CGSize(width: bounds.width * scale, height: bounds.height * scale)
        }
        CATransaction.commit()
        if !holdsDrawableSize, bounds.size != laidOutSize {
            laidOutSize = bounds.size
            onResize?()
        }
    }
}

/// A Metal layer that refuses the 1 by 1 drawable MoltenVK sets to finish a presentation early,
/// which otherwise leaves the video flickering or stuck at one pixel
/// (https://github.com/mpv-player/mpv/pull/13651).
final class MpvMetalLayer: CAMetalLayer {
    override var drawableSize: CGSize {
        get { super.drawableSize }
        set {
            if newValue.width > 1 && newValue.height > 1 { super.drawableSize = newValue }
        }
    }
}

extension Notification.Name {
    /// A video engine is opening a file and has not shown its first frame: the screen is better
    /// left as it is until then (ActivityViewController).
    static let videoEngineStarting = Notification.Name("TMPlayerVideoEngineStarting")
    /// The engine showed its first frame, or the file ended or failed before it did.
    static let videoEngineStarted = Notification.Name("TMPlayerVideoEngineStarted")
}
