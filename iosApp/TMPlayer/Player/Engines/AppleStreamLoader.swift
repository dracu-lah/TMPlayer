import AVFoundation
import TMPlayerKit
import UniformTypeIdentifiers

/// Telegram's stream and Android's data sources for Apple's player. AVURLAsset asks a resource
/// loader for byte ranges of a URL of its own scheme, and this reads them through the same Kotlin
/// streams mpv's protocols read (TdStreamProtocol, MediaSourceProtocol): a read where nothing has
/// arrived yet points TDLib's download there and waits.
///
/// The requests are served one piece at a time, in turn, on a queue of their own: a read blocks
/// until its bytes arrive, and a long request (Apple's player asks for everything to the end) must
/// not keep a seek's request waiting behind it.
final class AppleStreamLoader: NSObject, AVAssetResourceLoaderDelegate {
    static let scheme = "tmplayer-av"

    /// The bytes one answer carries at most, so the requests take turns.
    private static let piece = 512 * 1024

    private let source: ByteSource
    private let contentType: String
    private let reading = DispatchQueue(label: "org.tmplayer.apple.loader", qos: .userInitiated)
    let delegateQueue = DispatchQueue(label: "org.tmplayer.apple.loader.delegate", qos: .userInitiated)

    private let lock = NSLock()
    private var waiting: [AVAssetResourceLoadingRequest] = []
    private var pumping = false
    private var closed = false

    /// The URL Apple's player is given for this loader's source.
    let url: URL

    /// A loader over [source], already open, whose first bytes said [contentType].
    init(source: ByteSource, contentType: String) {
        self.source = source
        self.contentType = contentType
        url = URL(string: "\(AppleStreamLoader.scheme)://video/\(UUID().uuidString)")!
    }

    /// Ends every read; nothing is answered after.
    func close() {
        lock.lock()
        closed = true
        let pending = waiting
        waiting.removeAll()
        lock.unlock()
        pending.forEach { $0.finishLoading(with: AppleStreamLoader.closedError) }
        source.cancel()
        reading.async { [source] in source.close() }
    }

    // AVAssetResourceLoaderDelegate

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest) -> Bool {
        lock.lock()
        if closed {
            lock.unlock()
            return false
        }
        waiting.append(loadingRequest)
        let start = !pumping
        pumping = true
        lock.unlock()
        if start { reading.async { [weak self] in self?.pump() } }
        return true
    }

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, didCancel loadingRequest: AVAssetResourceLoadingRequest) {
        lock.lock()
        waiting.removeAll { $0 === loadingRequest }
        lock.unlock()
    }

    // Reading

    /// Serves the waiting requests a piece at a time, in turn, until none is left.
    private func pump() {
        while true {
            lock.lock()
            if closed || waiting.isEmpty {
                pumping = false
                lock.unlock()
                return
            }
            let request = waiting.removeFirst()
            lock.unlock()
            if request.isCancelled || request.isFinished { continue }
            if serve(request) {
                lock.lock()
                if !closed { waiting.append(request) }
                lock.unlock()
            }
        }
    }

    /// One piece of [request]; true when it wants more.
    private func serve(_ request: AVAssetResourceLoadingRequest) -> Bool {
        let size = source.size
        if let info = request.contentInformationRequest {
            info.contentType = contentType
            info.contentLength = size
            info.isByteRangeAccessSupported = true
        }
        guard let data = request.dataRequest else {
            request.finishLoading()
            return false
        }
        let end = data.requestsAllDataToEndOfResource || size <= 0
            ? size
            : min(size, data.requestedOffset + Int64(data.requestedLength))
        let at = data.currentOffset
        if at >= end {
            request.finishLoading()
            return false
        }
        guard let bytes = source.read(at: at, count: Int(min(end - at, Int64(AppleStreamLoader.piece)))) else {
            request.finishLoading(with: AppleStreamLoader.readError)
            return false
        }
        if bytes.isEmpty {
            request.finishLoading()
            return false
        }
        if request.isCancelled { return false }
        data.respond(with: bytes)
        if data.currentOffset >= end {
            request.finishLoading()
            return false
        }
        return true
    }

    private static let readError = NSError(domain: "TMPlayerAppleLoader", code: 1, userInfo: [NSLocalizedDescriptionKey: "The video's bytes could not be read"])
    private static let closedError = NSError(domain: "TMPlayerAppleLoader", code: 2, userInfo: [NSLocalizedDescriptionKey: "The video was closed"])
}

/// Bytes of a video at any place, read blocking: what the loader and the format check need.
protocol ByteSource: AnyObject {
    /// The size in bytes, 0 or less when unknown.
    var size: Int64 { get }
    /// Up to [count] bytes at [offset]; empty at the end, nil on a failure.
    func read(at offset: Int64, count: Int) -> Data?
    /// Wakes a waiting read so it gives up; the source reads nothing after.
    func cancel()
    func close()
}

enum ByteSources {
    /// The source behind `tdfile://<id>` (TDLib's download) or `tmsource://<key>` (Android's data
    /// sources), or nil for anything else.
    static func open(_ source: String) -> ByteSource? {
        if source.hasPrefix("\(TdStreamProtocol.scheme)://") {
            guard let fileId = Int32(source.dropFirst(TdStreamProtocol.scheme.count + 3)),
                  let stream = VideoStreams.shared.open(fileId: fileId)
            else { return nil }
            return TdByteSource(stream: stream)
        }
        if source.hasPrefix("\(MediaSourceProtocol.scheme)://") {
            guard let stream = MediaSourceStreams.shared.open(key: String(source.dropFirst(MediaSourceProtocol.scheme.count + 3)))
            else { return nil }
            return MediaByteSource(stream: stream)
        }
        return nil
    }
}

/// TDLib's download of one file, read from its partial file as the bytes arrive.
private final class TdByteSource: ByteSource {
    private let stream: VideoStream
    private var descriptor: Int32 = -1
    private let lock = NSLock()

    init(stream: VideoStream) {
        self.stream = stream
    }

    var size: Int64 { stream.size }

    func read(at offset: Int64, count: Int) -> Data? {
        guard count > 0 else { return Data() }
        let available = stream.available(position: offset)
        if available == 0 { return Data() }
        if available < 0 { return nil }
        lock.lock()
        defer { lock.unlock() }
        if descriptor < 0 {
            guard let path = stream.localPath() else { return nil }
            descriptor = Darwin.open(path, O_RDONLY)
            if descriptor < 0 { return nil }
        }
        let wanted = Int(min(available, Int64(count)))
        var bytes = Data(count: wanted)
        let got = bytes.withUnsafeMutableBytes { pread(descriptor, $0.baseAddress, wanted, off_t(offset)) }
        if got <= 0 { return nil }
        if got < wanted { bytes.count = got }
        return bytes
    }

    func cancel() { stream.cancel() }

    func close() {
        lock.lock()
        if descriptor >= 0 { Darwin.close(descriptor) }
        descriptor = -1
        lock.unlock()
        stream.close()
    }
}

/// One of Android's player's data sources, read as its ExoPlayer would.
private final class MediaByteSource: ByteSource {
    private let stream: MediaSourceStream

    init(stream: MediaSourceStream) {
        self.stream = stream
    }

    var size: Int64 { stream.size }

    func read(at offset: Int64, count: Int) -> Data? {
        guard count > 0 else { return Data() }
        var bytes = Data(count: count)
        var filled = 0
        // The stream hands at most its own buffer at a time; a piece is gathered from a few reads.
        while filled < count {
            let got = bytes.withUnsafeMutableBytes { buffer -> Int64 in
                let start = buffer.baseAddress!.advanced(by: filled).assumingMemoryBound(to: CChar.self)
                return stream.read(position: offset + Int64(filled), into: start, count: Int64(count - filled))
            }
            if got < 0 { return filled > 0 ? bytes.prefix(filled) : nil }
            if got == 0 { break }
            filled += Int(got)
        }
        return bytes.prefix(filled)
    }

    func cancel() { stream.cancel() }

    func close() { stream.close() }
}
