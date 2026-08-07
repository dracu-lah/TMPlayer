# TDLib and NextLib both ship consumer rules in their AARs; these are the app-side additions.

# TDLib's JNI bridge and every request/response type crosses the native boundary by name.
-keep class org.drinkless.tdlib.** { *; }
-keep class dev.g000sha256.tdl.** { *; }

# NextLib's FFmpeg renderers are looked up reflectively by DefaultRenderersFactory.
-keep class io.github.anilbeesetti.nextlib.media3ext.** { *; }

# Media3 does the same for its own extension renderers and extractors.
-dontwarn androidx.media3.**

# Keeps crash reports from release builds readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
