# Draft: androidx/media issue for HX72 (not filed; the user files it)

**Title:** SIGSEGV SEGV_MAPERR in memcpy from SampleDataQueue.readData on the playback thread (media3 1.11.1, OPPO CPH2727, Android 16)

**Body:**

A native crash on the playback thread while a renderer copies sample data into a MediaCodec input buffer.

- media3 1.11.1 (`media3-exoplayer`), app release build (R8), arm64
- Device: OPPO CPH2727 (A5), Android 16 (ColorOS 16), Snapdragon 6s 4G Gen 1
- One event so far (2026-11-01), progressive playback, MediaCodec video renderer plus an FFmpeg audio decoder (`SimpleDecoder` thread alive)
- Signal: SIGSEGV, SEGV_MAPERR, in `__memcpy_aarch64_simd`

De-obfuscated Java frames, top first:

```
java.nio.DirectByteBuffer.put
androidx.media3.exoplayer.source.SampleDataQueue.readData(SampleDataQueue.java:411-415)   // target.put(allocation.data, offset, toCopy)
androidx.media3.exoplayer.source.SampleDataQueue.readSampleData
androidx.media3.exoplayer.source.SampleQueue.read
androidx.media3.exoplayer.source.ProgressiveMediaPeriod$SampleStreamImpl.readData
androidx.media3.exoplayer.BaseRenderer.readSource
androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.lambda$feedInputBuffer$0
androidx.media3.exoplayer.ExoPlayerImplInternal.handleMessage
```

Why this does not look like a size bug: `ByteBuffer.put(byte[], int, int)` bounds-checks in Java and
`MediaCodecRenderer` checks capacity first, so a too-small target would throw, not fault. A
SEGV_MAPERR in the memcpy suggests the Java `DirectByteBuffer` still looked valid while the codec's
native memory under it was already unmapped (codec reclaimed or the codec2 service died while the
playback thread was mid-copy).

Question: is there a supported way to detect that the codec's input buffer went away before the copy
(or a guard around the copy), or is this considered a platform/vendor codec bug?

Closest I found: a codec-side `__memcpy_aarch64_simd` crash after an OS update,
forum.developer.samsung.com/t/35878.

Before filing: check the count in Sentry (issue 157823285), strip app package names if wanted, and
add a tombstone excerpt.
