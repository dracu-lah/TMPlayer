package com.google.common.base

/** Guava's predicate, as Android's player hands one to NextLib. */
fun interface Predicate<T> {
    fun apply(input: T?): Boolean
}
