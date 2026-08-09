# TDLib and NextLib both ship consumer rules in their AARs; these are the app-side additions.

# TDLib's JNI bridge and every request/response type crosses the native boundary by name.
-keep class org.drinkless.tdlib.** { *; }
-keep class dev.g000sha256.tdl.** { *; }

# NextLib's FFmpeg renderers are looked up reflectively by DefaultRenderersFactory.
-keep class io.github.anilbeesetti.nextlib.media3ext.** { *; }

# Media3 does the same for its own extension renderers and extractors.
-dontwarn androidx.media3.**

# Compose isolates Layout.TextInclusionStrategy, which only exists on API 34+, in this
# bridge. R8 8.13 can otherwise inline the bridge into MultiParagraph and make every
# Text fail to load on older TVs even though Compose guards the call by SDK level.
-keep class androidx.compose.ui.text.android.AndroidLayoutApi34 { *; }

# Keeps crash reports from release builds readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
