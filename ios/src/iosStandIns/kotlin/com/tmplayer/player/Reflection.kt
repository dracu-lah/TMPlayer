package com.tmplayer.player

import kotlin.reflect.KClass

/**
 * Java's Class.getMethod. Kotlin on iOS has no reflection, so no method is ever found, the answer a
 * JVM gives for a method the class does not have; Android's player calls it inside runCatching for
 * a method an optional build may lack, and goes on without it.
 */
fun KClass<*>.getMethod(name: String, vararg parameterTypes: KClass<*>): java.lang.reflect.Method =
    throw NoSuchMethodException("$name (no reflection on iOS)")

class NoSuchMethodException(message: String) : Exception(message)
