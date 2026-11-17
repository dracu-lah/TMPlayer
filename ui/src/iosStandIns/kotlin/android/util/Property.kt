package android.util

/** A named property of a type, read and set, Android's. */
abstract class Property<T, V>(val type: kotlin.reflect.KClass<*>?, val name: String) {
    abstract fun get(target: T): V

    open fun set(target: T, value: V) {
        throw UnsupportedOperationException("Property $name is read-only")
    }

    open val isReadOnly: Boolean get() = false
}
