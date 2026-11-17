import Darwin
import Libmpv
import TMPlayerKit

/// mpv's `tdfile://<file id>` protocol: a Telegram video read while TDLib is still downloading it.
///
/// mpv's demuxer thread reads; each read asks the Kotlin VideoStream how many bytes from the read
/// position are on the disk (which moves TDLib's download there when there are none, and waits),
/// then reads them from TDLib's partial file. A seek only moves the position: the next read is what
/// points the download at the new place, as with :core's TdByteWindow on Android and the desktop.
enum TdStreamProtocol {
    static let scheme = "tdfile"

    static func register(on handle: OpaquePointer) {
        mpv_stream_cb_add_ro(handle, scheme, nil) { _, uri, info in
            guard let uri, let info else { return MPV_ERROR_LOADING_FAILED.rawValue }
            let text = String(cString: uri)
            guard text.hasPrefix("\(TdStreamProtocol.scheme)://"),
                  let fileId = Int32(text.dropFirst(TdStreamProtocol.scheme.count + 3)),
                  let stream = VideoStreams.shared.open(fileId: fileId)
            else { return MPV_ERROR_LOADING_FAILED.rawValue }
            info.pointee.cookie = Unmanaged.passRetained(TdStreamReader(stream: stream)).toOpaque()
            info.pointee.read_fn = { cookie, buffer, count in reader(cookie)?.read(into: buffer, count: count) ?? -1 }
            info.pointee.seek_fn = { cookie, offset in reader(cookie)?.seek(to: offset) ?? Int64(MPV_ERROR_GENERIC.rawValue) }
            info.pointee.size_fn = { cookie in reader(cookie)?.size ?? Int64(MPV_ERROR_UNSUPPORTED.rawValue) }
            info.pointee.cancel_fn = { cookie in reader(cookie)?.cancel() }
            info.pointee.close_fn = { cookie in
                guard let cookie else { return }
                Unmanaged<TdStreamReader>.fromOpaque(cookie).takeRetainedValue().close()
            }
            return 0
        }
    }
}

private func reader(_ cookie: UnsafeMutableRawPointer?) -> TdStreamReader? {
    cookie.map { Unmanaged<TdStreamReader>.fromOpaque($0).takeUnretainedValue() }
}

/// One open stream: the read position and the partial file, opened once TDLib has made it.
private final class TdStreamReader {
    private let stream: VideoStream
    private var position: Int64 = 0
    private var descriptor: Int32 = -1

    init(stream: VideoStream) {
        self.stream = stream
    }

    var size: Int64 { stream.size }

    func read(into buffer: UnsafeMutablePointer<CChar>?, count: UInt64) -> Int64 {
        guard let buffer, count > 0 else { return 0 }
        let available = stream.available(position: position)
        if available <= 0 { return available } // 0 is the end of the file, -1 an error
        if descriptor < 0 {
            guard let path = stream.localPath() else { return -1 }
            descriptor = Darwin.open(path, O_RDONLY)
            if descriptor < 0 { return -1 }
        }
        let wanted = Int(min(UInt64(available), count))
        let got = pread(descriptor, buffer, wanted, off_t(position))
        if got <= 0 { return -1 }
        position += Int64(got)
        return Int64(got)
    }

    func seek(to offset: Int64) -> Int64 {
        guard offset >= 0, offset <= size else { return Int64(MPV_ERROR_GENERIC.rawValue) }
        position = offset
        return offset
    }

    func cancel() {
        stream.cancel()
    }

    func close() {
        if descriptor >= 0 { Darwin.close(descriptor) }
        descriptor = -1
        stream.close()
    }
}
