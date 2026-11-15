package com.tmplayer.player.demux;

import androidx.annotation.Nullable;

import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary;

/**
 * The JNI side of TMPlayer's container reader: libavformat inside the FFmpeg extension's native
 * library (tm4 of scripts/build-media3ext.sh, media3ext/src/main/cpp/ffdemux.cpp in its patch).
 * The functions are bound by this class's name, so it lives here, in the app, and an app built
 * against nextlib's Maven Central AAR, which has none of them, still compiles: {@link #available}
 * is false there. Every call on one handle comes from one thread ({@link FfmpegExtractor}'s demux
 * thread); the layout of each array is ffdemux.cpp's.
 */
public final class FfmpegDemuxNative {

  /** ffdemux.h's kApiVersion this class is written for. */
  static final int API_VERSION = 1;

  private static volatile Boolean available;

  private FfmpegDemuxNative() {}

  /** Whether the native library has the container reader this class expects. Never throws. */
  public static boolean available() {
    Boolean known = available;
    if (known != null) return known;
    boolean result;
    try {
      result = FfmpegLibrary.isAvailable() && nativeApiVersion() == API_VERSION;
    } catch (Throwable notThere) {
      // UnsatisfiedLinkError with the Maven Central AAR, or a library that failed to load.
      result = false;
    }
    available = result;
    return result;
  }

  static native int nativeApiVersion();

  @Nullable
  static native String nativeProbe(byte[] data, int length);

  /**
   * Opens [format] over [io], whose {@code int read(int length)} fills [buffer] from its start and
   * returns the count (-1 at the end, -2 to give up), and whose {@code long seek(long position)}
   * returns the position (or below 0). 0 when the file is refused, with libavformat's code in
   * error[0].
   */
  static native long nativeOpen(String format, ReaderIo io, byte[] buffer, long size, int[] error);

  @Nullable
  static native long[] nativeInfo(long handle);

  @Nullable
  static native long[] nativeStream(long handle, int index);

  @Nullable
  static native String nativeText(long handle, int index, int what);

  @Nullable
  static native byte[] nativeExtraData(long handle, int index);

  @Nullable
  static native long[] nativeIndex(long handle, int stream);

  static native int nativeRead(long handle, long[] meta);

  static native int nativeCopy(long handle, byte[] out);

  static native int nativeSeek(long handle, long timeUs, long bytePosition);

  static native void nativeClose(long handle);
}
