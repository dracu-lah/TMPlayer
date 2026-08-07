# TDLib JNI bridge — keep native interface classes (tdl-coroutines ships its own
# consumer rules in the AAR; this is a safety net for the JNI entry points)
-keep class dev.g000sha256.tdl.** { *; }
