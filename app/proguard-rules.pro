# TDLib and NextLib both ship consumer rules in their AARs; these are the app-side additions.

# TDLib's JNI bridge crosses the native boundary by name, so it has to survive verbatim.
-keep class org.drinkless.tdlib.** { *; }

# The Kotlin wrapper does not: it is ordinary Kotlin calling that bridge, and keeping the whole
# package pinned several thousand generated DTO classes, with their fields and methods, into every
# release build. Only the ones this app actually references are needed, and R8 works those out.
-dontwarn dev.g000sha256.tdl.**

# NextLib's FFmpeg renderers are looked up reflectively by DefaultRenderersFactory.
-keep class io.github.anilbeesetti.nextlib.media3ext.** { *; }

# The container reader's JNI (tm4, ffdemux.cpp) is bound by this class's name, and calls back
# read(int) and seek(long) on the ReaderIo it is handed, by name.
-keep class com.tmplayer.player.demux.FfmpegDemuxNative { *; }
-keep interface com.tmplayer.player.demux.ReaderIo { *; }
-keepclassmembers class * implements com.tmplayer.player.demux.ReaderIo {
    int read(int);
    long seek(long);
}

# Media3 does the same for its own extension renderers and extractors.
-dontwarn androidx.media3.**

# Compose isolates Layout.TextInclusionStrategy, which only exists on API 34+, in this
# bridge. R8 8.13 can otherwise inline the bridge into MultiParagraph and make every
# Text fail to load on older TVs even though Compose guards the call by SDK level.
-keep class androidx.compose.ui.text.android.AndroidLayoutApi34 { *; }

# Keeps crash reports from release builds readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The Cast framework makes its options provider from the class name in the manifest (CP73). The
# F-Droid build has neither the framework nor the class, and the rule then matches nothing.
-keep class com.tmplayer.cast.CastOptionsProvider { <init>(); }

# libVLC (PR2, only in a -PtmVlc=true build): its native code calls back into these classes by
# name (events, dialogs, the video output), and the AAR brings no rules of its own. Without the
# library the rule matches nothing.
-keep class org.videolan.libvlc.** { *; }
-dontwarn org.videolan.libvlc.**
