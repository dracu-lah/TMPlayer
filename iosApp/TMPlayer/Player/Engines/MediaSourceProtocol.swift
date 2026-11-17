import Libmpv
import TMPlayerKit

/// mpv's `tmsource://<key>` protocol: a video read through Android's player's own data sources
/// (MediaSourceStreams), as Android's ExoPlayer reads it. A seek only moves the position; the next
/// read is what opens the source again there, as ExoPlayer opens a source after a seek.
enum MediaSourceProtocol {
    static let scheme = "tmsource"

    static func register(on handle: OpaquePointer) {
        mpv_stream_cb_add_ro(handle, scheme, nil) { _, uri, info in
            guard let uri, let info else { return MPV_ERROR_LOADING_FAILED.rawValue }
            let text = String(cString: uri)
            let prefix = "\(MediaSourceProtocol.scheme)://"
            guard text.hasPrefix(prefix), let stream = MediaSourceStreams.shared.open(key: String(text.dropFirst(prefix.count)))
            else { return MPV_ERROR_LOADING_FAILED.rawValue }
            info.pointee.cookie = Unmanaged.passRetained(MediaSourceReader(stream: stream)).toOpaque()
            info.pointee.read_fn = { cookie, buffer, count in sourceReader(cookie)?.read(into: buffer, count: count) ?? -1 }
            info.pointee.seek_fn = { cookie, offset in sourceReader(cookie)?.seek(to: offset) ?? Int64(MPV_ERROR_GENERIC.rawValue) }
            info.pointee.size_fn = { cookie in
                guard let size = sourceReader(cookie)?.size, size > 0 else { return Int64(MPV_ERROR_UNSUPPORTED.rawValue) }
                return size
            }
            info.pointee.cancel_fn = { cookie in sourceReader(cookie)?.cancel() }
            info.pointee.close_fn = { cookie in
                guard let cookie else { return }
                Unmanaged<MediaSourceReader>.fromOpaque(cookie).takeRetainedValue().close()
            }
            return 0
        }
    }
}

private func sourceReader(_ cookie: UnsafeMutableRawPointer?) -> MediaSourceReader? {
    cookie.map { Unmanaged<MediaSourceReader>.fromOpaque($0).takeUnretainedValue() }
}

/// One open reading: the position mpv is at.
private final class MediaSourceReader {
    private let stream: MediaSourceStream
    private var position: Int64 = 0

    init(stream: MediaSourceStream) {
        self.stream = stream
    }

    var size: Int64 { stream.size }

    func read(into buffer: UnsafeMutablePointer<CChar>?, count: UInt64) -> Int64 {
        guard let buffer, count > 0 else { return 0 }
        let got = stream.read(position: position, into: buffer, count: Int64(min(count, UInt64(Int64.max))))
        if got > 0 { position += got }
        return got
    }

    func seek(to offset: Int64) -> Int64 {
        guard offset >= 0, size <= 0 || offset <= size else { return Int64(MPV_ERROR_GENERIC.rawValue) }
        position = offset
        return offset
    }

    func cancel() {
        stream.cancel()
    }

    func close() {
        stream.close()
    }
}
