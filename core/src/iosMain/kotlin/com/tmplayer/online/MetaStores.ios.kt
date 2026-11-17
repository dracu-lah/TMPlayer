package com.tmplayer.online

/** A picture kept by a [MetaCacheStore]: its file, as on the JVM (java.io.File is a stand in on iOS). */
actual typealias CachedImage = java.io.File

actual val defaultTransport: HttpTransport get() = UrlSessionTransport

/** The JVM's transport is named by core's JVM half; on iOS the name is URLSession's. */
typealias UrlConnectionTransport = UrlSessionTransport
