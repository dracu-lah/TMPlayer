# ProGuard rules for the desktop release image. Shrinking only: no obfuscation, no optimisation,
# so these rules only have to name what is reached without a visible reference in the bytecode.

# The app itself is small next to its libraries, and it uses JNA and D-Bus interfaces by class
# literal and reflection, so it is kept whole.
-keep class com.tmplayer.** { *; }

# TDLib: the JNI side looks up classes and methods by name from native code, and the tdl-coroutines
# DTOs are serialised and decoded by name. All of it stays.
-keep class org.drinkless.** { *; }
-keep class dev.g000sha256.tdl.** { *; }

# kotlinx.serialization finds a generated serializer through reflection on the companion object.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod, RuntimeVisibleAnnotations
-keepclassmembers class **$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# JNA builds its proxies and structure layouts by reflection, and native code calls back into
# these classes by name. The Library, Structure and Callback subtypes carry their own methods and
# fields, which are the native contract, so they keep every member.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }

# mediamp: the mpv bindings are JNI, and the runtime loader unpacks the native libraries by name.
-keep class org.openani.mediamp.** { *; }

# dbus-java: interfaces are proxied with java.lang.reflect.Proxy, signals and exported objects
# are matched by method name and annotation, and transports are found through ServiceLoader.
-keep class org.freedesktop.dbus.** { *; }
-keep class * implements org.freedesktop.dbus.interfaces.DBusInterface { *; }
-keep class * extends org.freedesktop.dbus.messages.DBusSignal { *; }
-keep class * extends org.freedesktop.dbus.types.UInt16 { *; }
-keepclassmembers class * { @org.freedesktop.dbus.annotations.* *; }

# ServiceLoader providers are named in META-INF/services and never referenced from code.
-keep class * implements kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keep class * implements kotlinx.coroutines.CoroutineExceptionHandler { *; }
-keepnames class * implements org.slf4j.spi.SLF4JServiceProvider
-keep class org.slf4j.** { *; }
-keep class org.slf4j.nop.** { *; }

# FileKit's dialogs reach the platform through JNA and Swing.
-keep class io.github.vinceglb.filekit.** { *; }
-keep class kotlinx.io.** { *; }

# Native methods keep their names whatever class they are in.
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# Optional dependencies that the libraries mention and the app never loads.
-dontwarn **
-ignorewarnings
