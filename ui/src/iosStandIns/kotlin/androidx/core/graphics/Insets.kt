package androidx.core.graphics

/** Four distances in from a window's edges, in pixels, Android's. */
class Insets private constructor(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    override fun equals(other: Any?): Boolean =
        other is Insets && other.left == left && other.top == top && other.right == right && other.bottom == bottom

    override fun hashCode(): Int = ((left * 31 + top) * 31 + right) * 31 + bottom

    override fun toString(): String = "Insets{left=$left, top=$top, right=$right, bottom=$bottom}"

    companion object {
        val NONE = Insets(0, 0, 0, 0)

        fun of(left: Int, top: Int, right: Int, bottom: Int): Insets =
            if (left == 0 && top == 0 && right == 0 && bottom == 0) NONE else Insets(left, top, right, bottom)

        fun max(a: Insets, b: Insets): Insets =
            of(maxOf(a.left, b.left), maxOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
    }
}
