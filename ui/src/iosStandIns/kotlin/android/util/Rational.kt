package android.util

/** A ratio of two whole numbers, Android's. */
class Rational(val numerator: Int, val denominator: Int) {
    fun floatValue(): Float = numerator.toFloat() / denominator

    fun doubleValue(): Double = numerator.toDouble() / denominator

    override fun equals(other: Any?): Boolean = other is Rational && other.numerator.toLong() * denominator == numerator.toLong() * other.denominator

    override fun hashCode(): Int = floatValue().hashCode()

    override fun toString(): String = "$numerator/$denominator"
}

/** A width and height, Android's. */
class Size(val width: Int, val height: Int) {
    override fun equals(other: Any?): Boolean = other is Size && other.width == width && other.height == height

    override fun hashCode(): Int = width * 31 + height

    override fun toString(): String = "${width}x$height"
}
