// Host benchmark for the media3ext AAR's AV1 path (HX2c): the decode as FfmpegVideoDecoder sets
// it up and the per-frame step ffmpegRenderFrame runs, through the same FFmpeg 9.0.1 and dav1d
// 1.5.4 built for the host (build-host-ffmpeg.sh) and the light mode's own header (fflight.h from
// the patched nextlib tree). See run-bench.sh for the cases and how to read them.
//
// bench decode  <clip> <threads> <lowdelay|delayN|auto> <grain|nograin> [convert-mode]
//   Feeds the clip one packet per call and takes at most one frame back per packet, as Media3's
//   SimpleDecoder does, and prints frames per second, the frames lost at the end (SimpleDecoder
//   never drains) and how long the first frame took. With a convert mode each frame also goes
//   through that conversion on the feeding thread, as the render does on ExoPlayer's thread.
// bench seek <clip> <threads> <lowdelay|delayN|auto> <grain|nograin>
//   Flushes and decodes from a keyframe to the last frame of its group, dropping the frames
//   before it as Media3 does after a seek, and prints how long the target frame took.
// bench convert <clip> <frames> [c]
//   Times each conversion alone on the clip's first frames; "c" turns off FFmpeg's SIMD.
#include <sys/resource.h>

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <string>
#include <vector>

#include "fflight.h"

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/cpu.h>
#include <libavutil/imgutils.h>
#include <libavutil/opt.h>
#include <libswscale/swscale.h>
}

using Clock = std::chrono::steady_clock;

static double Ms(Clock::time_point a, Clock::time_point b) {
    return std::chrono::duration<double, std::milli>(b - a).count();
}

// The conversions. "rgba" is ffmpegRenderFrame before HX2c (and still without the light mode);
// the rest are the light mode's.
enum class Convert { None, Rgba, Yv12, LightRgba, Yuv420p };

static Convert ParseConvert(const std::string &name) {
    if (name == "rgba") return Convert::Rgba;
    if (name == "yv12") return Convert::Yv12;
    if (name == "light-rgba") return Convert::LightRgba;
    if (name == "yuv420p") return Convert::Yuv420p;
    return Convert::None;
}

// ScaleContext::Get from ffvideo.cpp, for one output format.
static SwsContext *MakeSws(const AVFrame *frame, AVPixelFormat src_format, AVPixelFormat output_format) {
    const int matrix = frame->colorspace == AVCOL_SPC_BT470BG || frame->colorspace == AVCOL_SPC_SMPTE170M
            ? SWS_CS_ITU601 : frame->colorspace == AVCOL_SPC_BT2020_NCL ? SWS_CS_BT2020 : SWS_CS_ITU709;
    const int full_range = frame->color_range == AVCOL_RANGE_JPEG;
    SwsContext *context = sws_alloc_context();
    const int *coefficients = sws_getCoefficients(matrix);
    if (av_opt_set_int(context, "srcw", frame->width, 0) < 0 ||
        av_opt_set_int(context, "srch", frame->height, 0) < 0 ||
        av_opt_set_int(context, "src_format", src_format, 0) < 0 ||
        av_opt_set_int(context, "dstw", frame->width, 0) < 0 ||
        av_opt_set_int(context, "dsth", frame->height, 0) < 0 ||
        av_opt_set_int(context, "dst_format", output_format, 0) < 0 ||
        av_opt_set_int(context, "sws_flags", SWS_BILINEAR, 0) < 0 ||
        sws_setColorspaceDetails(context, coefficients, full_range, coefficients,
                output_format == AV_PIX_FMT_RGBA, 0, 1 << 16, 1 << 16) < 0 ||
        sws_init_context(context, nullptr, nullptr) < 0) {
        fprintf(stderr, "sws init failed\n");
        exit(1);
    }
    return context;
}

struct Converter {
    ~Converter() {
        sws_freeContext(rgba);
        sws_freeContext(rgba8);
        sws_freeContext(yuv);
    }

    void Run(Convert mode, const AVFrame *frame) {
        const int w = frame->width, h = frame->height;
        if (window.empty()) {
            // A window buffer: RGBA, or YV12 with Android's layout (chroma stride the luma
            // stride halved and aligned to 16, V before U).
            window.resize(static_cast<size_t>(w) * h * 4 + 64);
            stride = (w + 15) & ~15;
            reduced.resize(static_cast<size_t>(stride) * h * 3 / 2 + 64);
        }
        uint8_t *bits = window.data();
        switch (mode) {
            case Convert::Rgba: {
                if (!rgba) rgba = MakeSws(frame, static_cast<AVPixelFormat>(frame->format), AV_PIX_FMT_RGBA);
                uint8_t *dest[] = {bits, nullptr, nullptr, nullptr};
                int strides[] = {w * 4, 0, 0, 0};
                sws_scale(rgba, frame->data, frame->linesize, 0, h, dest, strides);
                break;
            }
            case Convert::Yv12: {
                const int c_stride = ((stride / 2) + 15) & ~15;
                uint8_t *v = bits + static_cast<size_t>(stride) * h;
                uint8_t *u = v + static_cast<size_t>(c_stride) * ((h + 1) / 2);
                uint8_t *dest[] = {bits, u, v};
                int strides[] = {stride, c_stride, c_stride};
                if (!fflight::CopyTo420(frame, dest, strides)) {
                    fprintf(stderr, "frame not 4:2:0 limited\n");
                    exit(1);
                }
                break;
            }
            case Convert::LightRgba: {
                // The light mode's fallback where a window takes no YV12: the 10 to 8 bit step,
                // then swscale's 8-bit 4:2:0 to RGBA, which has NEON code on ARM.
                const int c_stride = ((stride / 2) + 15) & ~15;
                uint8_t *planes[] = {reduced.data(), reduced.data() + static_cast<size_t>(stride) * h,
                                     reduced.data() + static_cast<size_t>(stride) * h + static_cast<size_t>(c_stride) * ((h + 1) / 2)};
                int plane_strides[] = {stride, c_stride, c_stride};
                fflight::CopyTo420(frame, planes, plane_strides);
                if (!rgba8) rgba8 = MakeSws(frame, AV_PIX_FMT_YUV420P, AV_PIX_FMT_RGBA);
                const uint8_t *src[] = {planes[0], planes[1], planes[2], nullptr};
                int src_strides[] = {stride, c_stride, c_stride, 0};
                uint8_t *dest[] = {bits, nullptr, nullptr, nullptr};
                int strides[] = {w * 4, 0, 0, 0};
                sws_scale(rgba8, src, src_strides, 0, h, dest, strides);
                break;
            }
            case Convert::Yuv420p: {
                if (!yuv) yuv = MakeSws(frame, static_cast<AVPixelFormat>(frame->format), AV_PIX_FMT_YUV420P);
                const int c_stride = ((stride / 2) + 15) & ~15;
                uint8_t *dest[] = {bits, bits + static_cast<size_t>(stride) * h,
                                   bits + static_cast<size_t>(stride) * h + static_cast<size_t>(c_stride) * ((h + 1) / 2), nullptr};
                int strides[] = {stride, c_stride, c_stride, 0};
                sws_scale(yuv, frame->data, frame->linesize, 0, h, dest, strides);
                break;
            }
            case Convert::None:
                break;
        }
    }

    SwsContext *rgba{};
    SwsContext *rgba8{};
    SwsContext *yuv{};
    std::vector<uint8_t> window;
    std::vector<uint8_t> reduced;
    int stride = 0;
};

struct Clip {
    explicit Clip(const char *path) {
        if (avformat_open_input(&format, path, nullptr, nullptr) < 0 ||
            avformat_find_stream_info(format, nullptr) < 0) {
            fprintf(stderr, "cannot open %s\n", path);
            exit(1);
        }
        stream = av_find_best_stream(format, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
        AVPacket *packet = av_packet_alloc();
        while (av_read_frame(format, packet) >= 0) {
            if (packet->stream_index == stream) packets.push_back(av_packet_clone(packet));
            av_packet_unref(packet);
        }
        av_packet_free(&packet);
    }

    ~Clip() {
        for (AVPacket *packet : packets) av_packet_free(&packet);
        avformat_close_input(&format);
    }

    AVFormatContext *format{};
    int stream = -1;
    std::vector<AVPacket *> packets;
};

// createVideoContext from ffvideo.cpp: before HX2c every AV1 decoder got LOW_DELAY; the light
// mode sets max_frame_delay instead and exports the film grain rather than applying it.
static AVCodecContext *OpenDecoder(const Clip &clip, int threads, const std::string &delay, bool grain) {
    const AVCodec *codec = avcodec_find_decoder_by_name("libdav1d");
    AVCodecContext *context = avcodec_alloc_context3(codec);
    avcodec_parameters_to_context(context, clip.format->streams[clip.stream]->codecpar);
    context->thread_count = threads;
    if (delay == "lowdelay") {
        context->flags |= AV_CODEC_FLAG_LOW_DELAY;
    } else if (delay.rfind("delay", 0) == 0) {
        av_opt_set_int(context, "max_frame_delay", atoi(delay.c_str() + 5), AV_OPT_SEARCH_CHILDREN);
    }
    if (!grain) context->export_side_data |= AV_CODEC_EXPORT_DATA_FILM_GRAIN;
    context->err_recognition = AV_EF_IGNORE_ERR;
    if (avcodec_open2(context, codec, nullptr) < 0) {
        fprintf(stderr, "avcodec_open2 failed\n");
        exit(1);
    }
    return context;
}

// JniContext::SendPacket and ReceiveFrame: one packet in, at most one frame out.
struct Feeder {
    explicit Feeder(AVCodecContext *context) : context(context) {}
    ~Feeder() {
        for (AVFrame *frame : pending) av_frame_free(&frame);
    }

    AVFrame *Feed(AVPacket *packet) {
        int result;
        while ((result = avcodec_send_packet(context, packet)) == AVERROR(EAGAIN)) {
            AVFrame *frame = av_frame_alloc();
            result = avcodec_receive_frame(context, frame);
            if (result < 0) {
                av_frame_free(&frame);
                if (result == AVERROR(EAGAIN)) continue;
                return nullptr;
            }
            pending.push_back(frame);
        }
        if (!pending.empty()) {
            AVFrame *frame = pending.front();
            pending.pop_front();
            return frame;
        }
        AVFrame *frame = av_frame_alloc();
        if (avcodec_receive_frame(context, frame) < 0) {
            av_frame_free(&frame);
            return nullptr;
        }
        return frame;
    }

    AVCodecContext *context;
    std::deque<AVFrame *> pending;
};

static int Decode(const Clip &clip, int threads, const std::string &delay, bool grain, Convert convert) {
    AVCodecContext *context = OpenDecoder(clip, threads, delay, grain);
    Feeder feeder(context);
    Converter converter;
    int frames = 0, first_after = -1;
    double first_ms = 0;
    const auto start = Clock::now();
    for (size_t i = 0; i < clip.packets.size(); i++) {
        AVFrame *frame = feeder.Feed(clip.packets[i]);
        if (!frame) continue;
        if (frames == 0) {
            first_ms = Ms(start, Clock::now());
            first_after = static_cast<int>(i) + 1;
        }
        converter.Run(convert, frame);
        av_frame_free(&frame);
        frames++;
    }
    const double total = Ms(start, Clock::now());
    rusage usage{};
    getrusage(RUSAGE_SELF, &usage);
    printf("decode threads=%d %s %s convert=%d: %d frames of %zu packets (%zu lost at the end), "
           "%.1f fps, first frame after %d packets in %.1f ms, peak RSS %ld MB\n",
           threads, delay.c_str(), grain ? "grain" : "nograin", static_cast<int>(convert), frames,
           clip.packets.size(), clip.packets.size() - frames, frames * 1000.0 / total, first_after, first_ms,
           usage.ru_maxrss / 1024);
    avcodec_free_context(&context);
    return 0;
}

// A seek as Media3 does it: flush, then feed from the keyframe before the target and drop every
// frame before it, until the target frame comes out. The target is the last frame of the second
// keyframe's group, the slowest seek of that group.
static int Seek(const Clip &clip, int threads, const std::string &delay, bool grain) {
    AVCodecContext *context = OpenDecoder(clip, threads, delay, grain);
    std::vector<size_t> keys;
    for (size_t i = 0; i < clip.packets.size(); i++) {
        if (clip.packets[i]->flags & AV_PKT_FLAG_KEY) keys.push_back(i);
    }
    if (keys.size() < 3) {
        fprintf(stderr, "the clip needs three keyframes\n");
        return 1;
    }
    {
        Feeder warm(context);
        for (size_t i = 0; i < keys[1]; i++) {
            if (AVFrame *frame = warm.Feed(clip.packets[i])) av_frame_free(&frame);
        }
    }
    avcodec_flush_buffers(context);
    const int64_t target = clip.packets[keys[2] - 1]->pts;
    Feeder feeder(context);
    const auto start = Clock::now();
    double ms = -1;
    for (size_t i = keys[1]; i < clip.packets.size() && ms < 0; i++) {
        AVFrame *frame = feeder.Feed(clip.packets[i]);
        if (frame && frame->pts >= target) ms = Ms(start, Clock::now());
        av_frame_free(&frame);
    }
    printf("seek threads=%d %s %s: %zu frames from the keyframe to the target, shown after %.1f ms\n",
           threads, delay.c_str(), grain ? "grain" : "nograin", keys[2] - keys[1], ms);
    avcodec_free_context(&context);
    return 0;
}

static int ConvertOnly(const Clip &clip, int count, bool c_only) {
    AVCodecContext *context = OpenDecoder(clip, 4, "lowdelay", true);
    Feeder feeder(context);
    std::vector<AVFrame *> frames;
    for (size_t i = 0; i < clip.packets.size() && static_cast<int>(frames.size()) < count; i++) {
        if (AVFrame *frame = feeder.Feed(clip.packets[i])) frames.push_back(frame);
    }
    if (frames.empty()) return 1;
    if (c_only) av_force_cpu_flags(0);
    const AVFrame *first = frames[0];
    printf("convert %dx%d %s, %zu frames, %s\n", first->width, first->height,
           av_get_pix_fmt_name(static_cast<AVPixelFormat>(first->format)), frames.size(),
           c_only ? "FFmpeg SIMD off" : "FFmpeg SIMD on");
    const char *names[] = {"rgba (sws, before)", "yuv420p (sws, before)", "yv12 (light)", "light-rgba (light fallback)"};
    const Convert modes[] = {Convert::Rgba, Convert::Yuv420p, Convert::Yv12, Convert::LightRgba};
    for (int m = 0; m < 4; m++) {
        Converter converter;
        converter.Run(modes[m], frames[0]);  // set up outside the timing
        const int rounds = 5;
        const auto start = Clock::now();
        for (int r = 0; r < rounds; r++) {
            for (AVFrame *frame : frames) converter.Run(modes[m], frame);
        }
        const double ms = Ms(start, Clock::now()) / (rounds * frames.size());
        printf("  %-28s %7.2f ms a frame, %7.1f fps\n", names[m], ms, 1000.0 / ms);
    }
    // How far the light mode's picture is from the one before: RGBA from the 10 to 8 bit step
    // against RGBA straight from 10 bits, on the first frame.
    Converter before, light;
    before.Run(Convert::Rgba, frames[0]);
    light.Run(Convert::LightRgba, frames[0]);
    const size_t bytes = static_cast<size_t>(first->width) * first->height * 4;
    long long sum = 0;
    int worst = 0;
    for (size_t i = 0; i < bytes; i++) {
        if (i % 4 == 3) continue;  // alpha
        const int d = abs(before.window[i] - light.window[i]);
        sum += d;
        if (d > worst) worst = d;
    }
    printf("  light-rgba against rgba: mean difference %.2f, largest %d (of 255)\n",
           sum / (bytes * 0.75), worst);
    for (AVFrame *frame : frames) av_frame_free(&frame);
    avcodec_free_context(&context);
    return 0;
}

int main(int argc, char **argv) {
    av_log_set_level(AV_LOG_ERROR);
    if (argc >= 6 && !strcmp(argv[1], "decode")) {
        Clip clip(argv[2]);
        return Decode(clip, atoi(argv[3]), argv[4], !strcmp(argv[5], "grain"),
                      argc > 6 ? ParseConvert(argv[6]) : Convert::None);
    }
    if (argc >= 6 && !strcmp(argv[1], "seek")) {
        Clip clip(argv[2]);
        return Seek(clip, atoi(argv[3]), argv[4], !strcmp(argv[5], "grain"));
    }
    if (argc >= 4 && !strcmp(argv[1], "convert")) {
        Clip clip(argv[2]);
        return ConvertOnly(clip, atoi(argv[3]), argc > 4 && !strcmp(argv[4], "c"));
    }
    fprintf(stderr, "usage: bench decode <clip> <threads> <lowdelay|delayN|auto> <grain|nograin> [rgba|yv12|light-rgba|yuv420p]\n"
                    "       bench seek <clip> <threads> <lowdelay|delayN|auto> <grain|nograin>\n"
                    "       bench convert <clip> <frames> [c]\n");
    return 2;
}
