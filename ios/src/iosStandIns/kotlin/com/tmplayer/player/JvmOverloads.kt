package com.tmplayer.player

/**
 * The JVM's @JvmOverloads, which Kotlin on iOS does not have: upstream's Views mark their
 * constructors with it for Java callers, and on iOS there are none, so it does nothing here.
 */
@Target(AnnotationTarget.CONSTRUCTOR, AnnotationTarget.FUNCTION)
annotation class JvmOverloads
