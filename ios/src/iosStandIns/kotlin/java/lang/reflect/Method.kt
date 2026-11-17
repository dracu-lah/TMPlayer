package java.lang.reflect

/** A Java method found by reflection, which Kotlin on iOS has none of. */
abstract class Method {
    abstract fun invoke(obj: Any?, vararg args: Any?): Any?
}
