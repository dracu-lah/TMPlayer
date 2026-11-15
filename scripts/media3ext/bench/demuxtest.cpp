// Host test for the container reader TMPlayer's patch adds to the media3ext AAR (tm4, C2): the same
// ffdemux.h the AAR's JNI glue (ffdemux.cpp) runs, over a file held in memory and answered the way
// the app's FfmpegExtractor answers it: reads at the current position, and any jump elsewhere is a
// reopen (on a phone, TdDataSource opening at that byte, TDLib moving its download there).
//
// demuxtest <clip>                 opens the clip, reads every packet, decodes the first ones of each
//                                  stream with the decoder the app would name, seeks to the middle and
//                                  prints one line: "OK <demuxer> streams=... packets=... open_bytes=...
//                                  seek_bytes=... seek_reopens=... landed=<us> asked=<us>"
// demuxtest <clip> garbage <seed>  the clip truncated, flipped, zeroed or padded with random bytes (by
//                                  a generator seeded with <seed>), with a lying size; any outcome but
//                                  a crash or a hang passes ("GARBAGE-OK ...")
//
// What it checks: the demuxer the probe picks is one of ffdemux.h's list, the streams come out with
// a decoder of this build, every packet's stream and size are in range, a seek to the middle reads
// a small part of the file (no read of the whole file), and lands on or before the asked time.
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "ffdemux.h"

struct Rng {
  explicit Rng(uint64_t seed) : state(seed * 0x9E3779B97F4A7C15ull + 0x1234567ull) {}
  uint64_t next() {
    state ^= state << 13;
    state ^= state >> 7;
    state ^= state << 17;
    return state;
  }
  uint64_t state;
};

// The file and the window the app's loading thread would hold open over it.
struct Source {
  std::vector<uint8_t> bytes;
  int64_t position = 0;
  int64_t bytesRead = 0;
  int reopens = 0;
};

static int sourceRead(void *opaque, uint8_t *buffer, int size) {
  Source *s = static_cast<Source *>(opaque);
  if (s->position >= (int64_t) s->bytes.size()) return AVERROR_EOF;
  int64_t n = std::min<int64_t>(size, (int64_t) s->bytes.size() - s->position);
  memcpy(buffer, s->bytes.data() + s->position, (size_t) n);
  s->position += n;
  s->bytesRead += n;
  return (int) n;
}

static int64_t sourceSeek(void *opaque, int64_t position) {
  Source *s = static_cast<Source *>(opaque);
  // What FfmpegExtractor does: up to 256 KB ahead is read through, anything else reopens.
  int64_t ahead = position - s->position;
  if (ahead >= 0 && ahead <= 256 * 1024) {
    s->bytesRead += ahead;
  } else {
    s->reopens++;
  }
  s->position = position;
  return position;
}

static bool readFile(const char *path, std::vector<uint8_t> *out) {
  FILE *f = fopen(path, "rb");
  if (f == nullptr) return false;
  uint8_t chunk[65536];
  size_t n;
  while ((n = fread(chunk, 1, sizeof chunk, f)) > 0) out->insert(out->end(), chunk, chunk + n);
  fclose(f);
  return true;
}

// Decodes up to [limit] packets of [stream] from the start, the way the app's decoder would get them.
static int decodeSome(tmdemux::Demuxer *d, int stream, int limit) {
  const AVCodecParameters *par = d->context->streams[stream]->codecpar;
  const AVCodec *codec = avcodec_find_decoder(par->codec_id);
  if (codec == nullptr) return -1;
  AVCodecContext *ctx = avcodec_alloc_context3(codec);
  avcodec_parameters_to_context(ctx, par);
  if (avcodec_open2(ctx, codec, nullptr) < 0) {
    avcodec_free_context(&ctx);
    return -1;
  }
  AVFrame *frame = av_frame_alloc();
  AVPacket *pkt = av_packet_alloc();
  int frames = 0, fed = 0;
  tmdemux::Packet p;
  while (fed < limit && tmdemux::read(d, &p) == 0) {
    if (p.stream != stream) continue;
    fed++;
    av_new_packet(pkt, p.size);
    memcpy(pkt->data, p.data, (size_t) p.size);
    if (p.key) pkt->flags |= AV_PKT_FLAG_KEY;
    if (avcodec_send_packet(ctx, pkt) >= 0) {
      while (avcodec_receive_frame(ctx, frame) >= 0) frames++;
    }
    av_packet_unref(pkt);
  }
  avcodec_send_packet(ctx, nullptr);
  while (avcodec_receive_frame(ctx, frame) >= 0) frames++;
  av_packet_free(&pkt);
  av_frame_free(&frame);
  avcodec_free_context(&ctx);
  return frames;
}

static void damage(std::vector<uint8_t> *bytes, uint64_t seed, int64_t *claimedSize) {
  Rng rng(seed);
  size_t size = bytes->size();
  switch (seed % 5) {
    case 0:  // cut short anywhere
      bytes->resize(size == 0 ? 0 : rng.next() % size);
      break;
    case 1:  // bytes flipped all over, the header included
      for (int i = 0; i < 64 && size > 0; i++) (*bytes)[rng.next() % size] ^= (uint8_t) (1 + rng.next() % 255);
      break;
    case 2: {  // a range zeroed
      if (size == 0) break;
      size_t at = rng.next() % size;
      size_t len = std::min<size_t>(size - at, 1 + rng.next() % 65536);
      memset(bytes->data() + at, 0, len);
      break;
    }
    case 3:  // the real head, then random bytes
      for (size_t i = std::min<size_t>(size, 4096); i < size; i++) (*bytes)[i] = (uint8_t) rng.next();
      break;
    default:  // random bytes in the header's sizes and counts
      for (int i = 0; i < 32 && size > 0; i++) (*bytes)[rng.next() % std::min<size_t>(size, 8192)] = (uint8_t) rng.next();
      break;
  }
  // A size that lies, as a cut-short download's would.
  *claimedSize = (rng.next() % 2) ? (int64_t) bytes->size() * 4 + 12345 : (int64_t) bytes->size();
}

int main(int argc, char **argv) {
  if (argc < 2) {
    fprintf(stderr, "usage: demuxtest <clip> [garbage <seed>]\n");
    return 2;
  }
  av_log_set_level(AV_LOG_QUIET);
  Source source;
  if (!readFile(argv[1], &source.bytes)) {
    printf("FAIL cannot read %s\n", argv[1]);
    return 1;
  }
  bool garbage = argc >= 4 && strcmp(argv[2], "garbage") == 0;
  int64_t claimedSize = (int64_t) source.bytes.size();
  if (garbage) damage(&source.bytes, strtoull(argv[3], nullptr, 10), &claimedSize);

  const char *format = tmdemux::probe(source.bytes.data(), (int) std::min<size_t>(source.bytes.size(), tmdemux::kProbeBytes));
  if (format == nullptr) {
    printf(garbage ? "GARBAGE-OK not recognised\n" : "FAIL not recognised\n");
    return garbage ? 0 : 1;
  }
  tmdemux::Io io;
  io.opaque = &source;
  io.read = sourceRead;
  io.seek = sourceSeek;
  io.size = claimedSize;
  int error = 0;
  tmdemux::Demuxer *d = tmdemux::open(format, io, &error);
  if (d == nullptr) {
    printf(garbage ? "GARBAGE-OK %s refused (%d)\n" : "FAIL %s refused (%d)\n", format, error);
    return garbage ? 0 : 1;
  }
  int64_t openBytes = source.bytesRead;
  int streams = (int) d->context->nb_streams;
  std::string names;
  bool ok = true;
  for (int i = 0; i < streams; i++) {
    const AVCodecParameters *par = d->context->streams[i]->codecpar;
    const AVCodec *codec = avcodec_find_decoder(par->codec_id);
    const char *type = av_get_media_type_string(par->codec_type);
    names += std::string(i ? "," : "") + (type ? type[0] : '?') + ":" + (codec ? codec->name : avcodec_get_name(par->codec_id));
    if (par->extradata_size > tmdemux::kMaxExtraData) ok = false;
  }

  // Every packet, in range.
  long packets = 0;
  tmdemux::Packet p;
  int ret;
  while ((ret = tmdemux::read(d, &p)) == 0) {
    if (p.stream < 0 || p.stream >= streams || p.size < 0 || p.size > tmdemux::kMaxPacket) ok = false;
    packets++;
    if (packets > 10000000) break;
  }
  if (garbage) {
    // A seek in a damaged file must come back too.
    tmdemux::seek(d, tmdemux::durationUs(d) > 0 ? tmdemux::durationUs(d) / 2 : 0, claimedSize / 2);
    for (int i = 0; i < 100 && tmdemux::read(d, &p) == 0; i++) {}
    printf("GARBAGE-OK %s streams=%d packets=%ld\n", format, streams, packets);
    tmdemux::close(d);
    return 0;
  }

  // Decode the first packets of each stream, from the start.
  std::string decoded;
  int primary = tmdemux::mainStream(d);
  for (int i = 0; i < streams; i++) {
    if (tmdemux::seek(d, 0, 0) < 0) ok = false;
    int frames = decodeSome(d, i, 40);
    decoded += std::string(i ? "," : "") + std::to_string(frames);
    if (frames <= 0) ok = false;
  }

  // Seek to the middle, as a stream that is only partly there would.
  int64_t duration = tmdemux::durationUs(d);
  int64_t asked = duration > 0 ? duration / 2 : 0;
  source.bytesRead = 0;
  source.reopens = 0;
  int64_t landed = INT64_MIN;
  if (tmdemux::seek(d, asked, -1) < 0) ok = false;
  while (tmdemux::read(d, &p) == 0) {
    if (p.stream == primary && p.timeUs != INT64_MIN) {
      landed = p.timeUs;
      break;
    }
  }
  int64_t seekBytes = source.bytesRead;
  // Seeking may read the index and a few probes, never most of the file.
  if (seekBytes > (int64_t) source.bytes.size() / 2 + 512 * 1024) ok = false;
  // Never after the asked place (by more than a frame), never far before it (a 2 s clip's GOP).
  if (landed == INT64_MIN || landed > asked + 100000 || landed < asked - 5000000) ok = false;

  printf("%s %s streams=%d [%s] packets=%ld decoded=[%s] size=%zu open_bytes=%lld seek_bytes=%lld seek_reopens=%d asked=%lld landed=%lld\n",
         ok ? "OK" : "FAIL", format, streams, names.c_str(), packets, decoded.c_str(), source.bytes.size(),
         (long long) openBytes, (long long) seekBytes, source.reopens, (long long) asked, (long long) landed);
  tmdemux::close(d);
  return ok ? 0 : 1;
}
