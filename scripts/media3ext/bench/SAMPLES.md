# Real sample files for the FFmpeg extension's host tests

`run-codec-tests.sh` and `run-demux-tests.sh` make their clips with the host's own ffmpeg, so they
cannot test a decoder or a container that ffmpeg has no encoder or muxer for. These files from
FFmpeg's public sample suite (https://samples.ffmpeg.org/, the same files its FATE tests use) fill
that gap. Only the URLs are here, never the files: `run-sample-tests.sh <work dir> <samples dir>`
downloads each one into a directory of its own (`samples.tsv` lists them), decodes it with
`codectest` the way the AAR's JNI glue does, compares the md5 with the reference ffmpeg 9.0.1's
decode of the same file (bit for bit), feeds 4 damaged copies of its packets to the decoder, and for
a container `FfmpegExtractor` reads it also runs `demuxtest` (small reads, decode of the first
packets, a seek to the middle, a seek back to the start, 12 damaged copies of the file).

Measured 2026-11-15 (worker w14, FFmpeg 9.0.1 host build, tm4 sources): 25 of the 25 targets have a
real sample and decode bit for bit; 1 container (Vivo) plays but cannot seek. A second pass (worker w15, same day) added the two decoders
Vivo's sound needs (G.723.1, Siren) and the MPEG-1 sequence header fix. Details below the table.

All paths are below `https://samples.ffmpeg.org/`. "match" is the md5 of the glue's decode equal to
the reference ffmpeg's. "demux ok" is `demuxtest` passing every check (open, decode, seek to the
middle, the demuxer's own name in the list of `ffdemux.h`).

| Target | Path | Size | Codec in container | Result |
| --- | --- | --- | --- | --- |
| WMV 3 (WMV9) | V-codecs/WMV9/wmv-samples/sample-wmv9-main-MP@LL.wmv | 1.2 MB | wmv3 in ASF | match, demux ok |
| WMV 3 | V-codecs/WMV9/wmv-samples/sample-wmv9-simple-SP@ML.wmv | 1.1 MB | wmv3 in ASF | match, demux ok |
| VC-1 | V-codecs/WMV9/interlaced/480i30__codec_WVC1__mode_2__framerate_29.970__type_2__preproc_17.wmv | 1.4 MB | vc1 (advanced, interlaced) in ASF | match, demux ok |
| SVQ3 | V-codecs/SVQ3/gl2.mov | 0.5 MB | svq3 in MOV | match |
| SVQ3 | V-codecs/SVQ3/blade_2_m480.mov | 0.4 MB | svq3 in MOV | match |
| VP6 | V-codecs/VP6/vp6-test/predator2_vp60.avi | 78 KB | vp6 in AVI | match |
| VP6 | V-codecs/VP6/vp6-test/predator2_vp61.avi | 76 KB | vp6 in AVI | match |
| VP6F | archive/all/flv+vp6f+nellymoser++nellymosertag.flv | 1.9 MB | vp6f and nellymoser in FLV | match (both) |
| VP6F | FLV/flash8/clip1024.flv | 109 KB | vp6f in FLV | match |
| RealVideo 3 | real/VC-RV30/eiqu-56k.rm | 252 KB | rv30 and cook in RM | match, demux seek note 2 |
| RealVideo 3 | real/VC-RV30/sample.rm | 215 KB | rv30 and cook in RM | match, demux seek note 2 and 3 |
| RealVideo 4 | real/VC-RV40/20040506.rm | 1.0 MB | rv40 and cook in RM | match, demux seek note 1 |
| RealVideo 1 (seen on the way) | real/AC-28_8/A0000040.rm, real/AC-sipr/plumbbob1_hi.rm | 425 KB, 48 KB | rv10 | match |
| MJPEG-B | V-codecs/MJPEGs/mjpa-b.mov/mjpegb_2.mov | 252 KB | mjpegb in MOV | match |
| H.263+ | archive/all/avi+h263+++h263p_more_artifacts.avi | 495 KB | H.263+ in AVI (labelled H.263) | match with `h263` and with `h263p` |
| H.263+ | archive/all/avi+h263+++h263p_aic_mqm.avi | 677 KB | H.263+ with AIC, MQM | match with `h263` and with `h263p` |
| WMA Pro | A-codecs/WMA9/wmapro/classical_16_16_1_8000_off_0_off_1_29.wma | 208 KB | wmapro in ASF | match, demux ok |
| WMA Pro | A-codecs/WMA9/wmapro/classical_22_16_1_14000_v3c_0_extend_0_29.wma | 345 KB | wmapro in ASF | match, demux ok |
| WMA Lossless | A-codecs/lossless/luckynight.wma | 6.3 MB | wmalossless in ASF | match, demux ok (over the 5 MB wish: no smaller one found) |
| MP1 | A-codecs/mp1-sample.mp1 | 28 KB | mp1 raw | match |
| MP1 | A-codecs/MP3/mpeg_layer1_audio.mpg | 3.7 MB | mp1 and mpeg1video in MPEG-PS | match (also mpeg1video), demux ok after a seek (note 4) |
| Cook | real/AC-cook/FUN_RM_32.rm | 68 KB | cook in RM | match, demux ok |
| Cook | real/AC-cook/mc_sich_ra8_20.rm | 97 KB | cook in RM | match, demux ok |
| RealAudio 2.0 | real/AC-28_8/luckynightmono2.ra | 115 KB | real_288 in .ra | match, demux seek note 5 |
| RealAudio 2.0 | real/AC-28_8/A0000040.rm | 425 KB | real_288 and rv10 in RM | match, demux ok |
| QCELP | A-codecs/qclp/test.3g2 | 44 KB | qcelp in 3G2 | match |
| QCELP | A-codecs/qcp/qcpclip.qcp | 103 KB | qcelp in QCP | match |
| SIPR | real/AC-sipr/intro.rm | 241 KB | sipr in RM | match, demux ok |
| SIPR | real/AC-sipr/plumbbob1_hi.rm | 47 KB | sipr and rv10 in RM | match, demux ok |
| ATRAC3 | A-codecs/ATRAC3/mc_sich_at3_066.wav | 283 KB | atrac3 in WAV | match |
| ATRAC3 | real/AC-atrc/mc_sich_ra8_105.rm | 461 KB | atrac3 in RM | match, demux ok |
| ATRAC3+ | A-codecs/ATRAC3+/at3p_sample5.oma | 323 KB | atrac3plus in OMA | match |
| ATRAC3+ | A-codecs/ATRAC3/SND0.AT3 | 76 KB | atrac3plus in RIFF AT3 (a PSP file, not ATRAC3) | match |
| APE | monkeyaudio/sh3.ape | 1.4 MB | ape | match, demux ok |
| IVR | real/ivr/SherwinWilliamsCommercial.ivr | 817 KB | rv40 and cook in IVR | match, demux seek note 1 |
| xWMA | xwma/ergon.xwma | 156 KB | wmav2 in xWMA | match, demux ok |
| NSV | nsv/witchblade-51kbps.nsv | 377 KB | vp3 and mp3 | match, demux ok |
| NSV | nsv/NullsoftBand-SoloMeansOne.nsv | 1.8 MB | vp3 and mp3 | match, demux ok |
| NSV | nsv/64vp6.nsv | 438 KB | vp6 and aac | match, demux note 6 |
| NSV | nsv/sample.nsv | 200 KB | vp6 and aac | match, demux seek note 5 and 6 |
| NSV | nsv/deer-210.nsv | 1.4 MB | vp3 | demux ok; decode equals the reference except the last 2 frames (the file ends inside a frame, the glue conceals with err_recognition ignore) |
| Vivo | vivo/bush.viv | 90 KB | h263 and g723_1 in Vivo | h263 and g723_1 match; demux seek note 7 |
| Vivo | vivo/gclinton.viv | 54 KB | siren in Vivo (no picture) | siren matches; demux seek note 7 |
| SMJPEG | game-formats/smjpeg/scenwin.mjpg (the server keeps it gzipped: gunzip first) | 526 KB | mjpeg and adpcm_ima_smjpeg | match (both), demux ok |

Damaged-input runs on every one of these (4 seeds per decoder, 12 per container): no crash, no hang.

## Notes on the demux column

1. **A seek back to the start reads nothing** (IVR sample, RealVideo 4 sample): the file's own index
   points into the middle of a packet, `av_read_frame` answers "Impossibly sized packet" and the
   reader cannot recover. The reference ffmpeg program fails on both files the same way (`-ss 1`).
2. A restart skips the packets before the first key frame the index knows (2272 to 2200, 607 to
   511). Harmless.
3. The RM header of `sample.rm` claims 45 minutes for a 9 second clip, so the asked middle is
   22 minutes and the seek lands at 0.4 s. A lying duration is the file's, not the glue's.
4. Before w15, `mpeg1video` of this file decoded no picture after any seek: its only sequence header
   is in the first frame and FFmpeg gives the stream no extra data (`extradata_size` 0), so a
   decoder started after a seek had none. `ffdemux.h` `open()` now copies that header into the
   extra data (`fillMpegSequenceHeader`), and `demuxtest` fails an MPEG program stream whose video
   has none: 23 pictures decode from the start, and 1375 frames match the reference as before.
5. Seek to the middle lands at 0 or reads nothing: a `.ra` file and `nsv/sample.nsv` have no index
   and no duration (NSV reports 0), so the app's SeekMap is unseekable for them (duration 0) and
   Media3 does not ask for a middle seek.
6. These two NSV files carry timestamps that start at 107 to 131 s with the picture 24 s behind the
   sound: the file's own sync field, FFmpeg reads it the same. The three other NSV files start at 0.
7. **Vivo cannot seek at all**: 5 of 5 sample files read no packet after a seek, from a fresh
   demuxer too, and the reference ffmpeg program fails `-ss` the same way. Vivo 1 files carry
   H.263 with G.723.1 sound, Vivo 2 files Siren sound and a picture FFmpeg does not know.

## What the Kotlin side does about it (this commit)

* `FfmpegExtractor` opens the file again once when a seek to the start (a replay, the bar dragged
  to 0, a loop) is followed by a read that fails with no packet in between. Test:
  `FfmpegExtractorTest` (a fake reader that cannot rewind: reopened once, and a file that stays
  broken ends instead of looping).
* `ContainerSeekMap` reports Vivo as unseekable (`NO_TIME_SEEK`), so the bar does not offer a drag
  that ends the video.

## Added in the same tm4 (never released, so no tm5), w15

* `ENABLED_DECODERS` has `g723_1` and `siren` (both LGPL in FFmpeg 9.0.1: no `--enable-gpl` needed).
  Both decode the Vivo samples bit for bit against the reference ffmpeg (`bush.viv` G.723.1,
  `gclinton.viv` Siren), and their damaged-packet runs pass. The Kotlin side names them like every
  other decoder (`audio/x-ffmpeg-g723_1`, `audio/x-ffmpeg-siren`), nothing to map by hand.
* `ffdemux.h` `open()` fills the extra data of an `mpeg1video` or `mpeg2video` stream that has
  none from the first sequence header (`00 00 01 B3` up to `B8` or `00`) in its first video
  packets (at most 256 packets or 4 MB), then goes back to where the data starts. The app hands
  the header to the device's MPEG-2 decoder as codec data too.
