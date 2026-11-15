// Host test for the decoders TMPlayer's patch adds to the media3ext AAR (tm3): opens a clip, builds
// the decoder the way the AAR's JNI glue does (ffaudio.cpp, ffvideo.cpp: the container's extra data
// copied with zeroed padding, the sample rate, channels, block size, bit rate, bits per coded
// sample and picture size handed over by ffcodecparams.h, error concealment off, one packet per
// call), decodes the whole stream and prints what came out.
//
// codectest <clip> <decoder> [threads]
//   prints one line: "OK <decoder> <video|audio> units=<frames or samples> md5=<hex>" where the md5
//   is of the raw decoder output (video: every frame's planes in the decoder's own pixel format,
//   packed with no padding; audio: signed 16-bit interleaved, converted by libswresample as the
//   AAR does), or "FAIL <decoder> <why>". run-codec-tests.sh compares the md5 with the one the
//   host's own ffmpeg gets from the same clip and decoder.
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "ffcodecparams.h"

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/channel_layout.h>
#include <libavutil/imgutils.h>
#include <libavutil/md5.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
}

static int Fail(const char *decoder, const char *why) {
    printf("FAIL %s %s\n", decoder, why);
    return 1;
}

struct Hasher {
    Hasher() : md5(av_md5_alloc()) { av_md5_init(md5); }
    ~Hasher() { av_free(md5); }
    void Update(const uint8_t *data, size_t size) { av_md5_update(md5, data, size); }
    std::string Finish() {
        uint8_t digest[16];
        av_md5_final(md5, digest);
        char text[33];
        for (int i = 0; i < 16; i++) snprintf(text + i * 2, 3, "%02x", digest[i]);
        return text;
    }
    AVMD5 *md5;
};

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: codectest <clip> <decoder> [threads]\n");
        return 2;
    }
    const char *path = argv[1];
    const char *name = argv[2];
    const int threads = argc > 3 ? atoi(argv[3]) : 2;
    av_log_set_level(AV_LOG_ERROR);

    const AVCodec *codec = avcodec_find_decoder_by_name(name);
    if (!codec) return Fail(name, "no such decoder in this FFmpeg build");
    const bool video = codec->type == AVMEDIA_TYPE_VIDEO;

    AVFormatContext *format = nullptr;
    if (avformat_open_input(&format, path, nullptr, nullptr) < 0) return Fail(name, "cannot open the clip");
    if (avformat_find_stream_info(format, nullptr) < 0) return Fail(name, "no stream info");
    int stream = -1;
    for (unsigned i = 0; i < format->nb_streams; i++) {
        if (format->streams[i]->codecpar->codec_id == codec->id) {
            stream = static_cast<int>(i);
            break;
        }
    }
    if (stream < 0) return Fail(name, "the clip has no stream for this decoder");
    const AVCodecParameters *par = format->streams[stream]->codecpar;

    // What FfmpegAudioDecoder / FfmpegVideoDecoder and the JNI glue hand over, from the container.
    AVCodecContext *context = avcodec_alloc_context3(codec);
    if (!context) return Fail(name, "out of memory");
    if (par->extradata_size > 0) {
        context->extradata = static_cast<uint8_t *>(av_mallocz(static_cast<size_t>(par->extradata_size) + AV_INPUT_BUFFER_PADDING_SIZE));
        memcpy(context->extradata, par->extradata, par->extradata_size);
        context->extradata_size = par->extradata_size;
    }
    if (video) {
        ffparams::ApplyVideo(context, par->width, par->height, par->bits_per_coded_sample, par->codec_tag);
    } else if (!getenv("TM_NOPARAMS")) {
        // The Java side passes the Format's sample rate, channel count and average bit rate, and
        // the block size and bits per coded sample of the extractor.
        ffparams::ApplyAudio(context, par->sample_rate, par->ch_layout.nb_channels, par->block_align,
                             par->bit_rate, par->bits_per_coded_sample);
    }
    context->thread_count = threads;
    context->err_recognition = AV_EF_IGNORE_ERR;
    if (avcodec_open2(context, codec, nullptr) < 0) return Fail(name, "avcodec_open2 failed");

    Hasher hasher;
    long long units = 0;
    FILE *dump = getenv("TM_DUMP") ? fopen(getenv("TM_DUMP"), "wb") : nullptr;
    SwrContext *swr = nullptr;
    std::vector<uint8_t> pcm;
    AVFrame *frame = av_frame_alloc();
    AVPacket *packet = av_packet_alloc();

    auto drain = [&]() -> int {
        for (;;) {
            int result = avcodec_receive_frame(context, frame);
            if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) return 0;
            if (result < 0) return result;
            if (video) {
                const int size = av_image_get_buffer_size(static_cast<AVPixelFormat>(frame->format), frame->width, frame->height, 1);
                if (size <= 0) return AVERROR(EINVAL);
                pcm.resize(size);
                av_image_copy_to_buffer(pcm.data(), size, frame->data, frame->linesize,
                                        static_cast<AVPixelFormat>(frame->format), frame->width, frame->height, 1);
                hasher.Update(pcm.data(), size);
                units++;
            } else {
                if (!swr) {
                    // ffaudio.cpp: the same layout and rate in and out, 16-bit out.
                    swr = swr_alloc();
                    AVChannelLayout layout = context->ch_layout;
                    av_opt_set_chlayout(swr, "in_chlayout", &layout, 0);
                    av_opt_set_chlayout(swr, "out_chlayout", &layout, 0);
                    av_opt_set_int(swr, "in_sample_rate", context->sample_rate, 0);
                    av_opt_set_int(swr, "out_sample_rate", context->sample_rate, 0);
                    av_opt_set_int(swr, "in_sample_fmt", context->sample_fmt, 0);
                    av_opt_set_int(swr, "out_sample_fmt", AV_SAMPLE_FMT_S16, 0);
                    if (swr_init(swr) < 0) return AVERROR(EINVAL);
                }
                const int channels = context->ch_layout.nb_channels;
                pcm.resize(static_cast<size_t>(frame->nb_samples) * channels * 2);
                uint8_t *out = pcm.data();
                const int got = swr_convert(swr, &out, frame->nb_samples,
                                            const_cast<const uint8_t **>(frame->extended_data), frame->nb_samples);
                if (got < 0) return got;
                hasher.Update(pcm.data(), static_cast<size_t>(got) * channels * 2);
                if (dump) fwrite(pcm.data(), 1, static_cast<size_t>(got) * channels * 2, dump);
                units += got;
            }
            av_frame_unref(frame);
        }
    };

    while (av_read_frame(format, packet) >= 0) {
        if (packet->stream_index == stream) {
            int result = avcodec_send_packet(context, packet);
            // A bad packet is skipped, as the AAR does (AVERROR_INVALIDDATA is not fatal).
            if (result < 0 && result != AVERROR_INVALIDDATA) {
                char text[128];
                av_strerror(result, text, sizeof text);
                return Fail(name, text);
            }
            if (result >= 0 && drain() < 0) return Fail(name, "receive_frame failed");
        }
        av_packet_unref(packet);
    }
    avcodec_send_packet(context, nullptr);
    if (drain() < 0) return Fail(name, "receive_frame failed at the end");
    if (units == 0) return Fail(name, "decoded nothing");

    printf("OK %s %s units=%lld md5=%s\n", name, video ? "video" : "audio", units, hasher.Finish().c_str());
    return 0;
}
