package androidx.media3.common.util

/** A width and height, Media3's. */
class Size(val width: Int, val height: Int) {
    fun getWidth(): Int = width

    fun getHeight(): Int = height

    override fun equals(other: Any?): Boolean = other is Size && other.width == width && other.height == height

    override fun hashCode(): Int = width * 31 + height

    companion object {
        val UNKNOWN = Size(-1, -1)
        val ZERO = Size(0, 0)
    }
}

/** Media3's OpenGL helpers; there is no OpenGL pipeline on iOS (the engine draws), so none runs. */
object GlUtil {
    const val HOMOGENEOUS_COORDINATE_VECTOR_SIZE = 4

    /** Media3's OpenGL error. */
    class GlException(message: String) : Exception(message)

    fun getNormalizedCoordinateBounds(): FloatArray = floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 0f, 1f, -1f, 1f, 0f, 1f, 1f, 1f, 0f, 1f)

    fun create4x4IdentityMatrix(): FloatArray = FloatArray(16) { if (it % 5 == 0) 1f else 0f }

    fun checkGlError() = Unit
}

/** A compiled OpenGL program, Media3's; making one on iOS fails as it does without a GL context. */
class GlProgram(vertexShaderGlsl: String, fragmentShaderGlsl: String) {
    init {
        throw GlUtil.GlException("No OpenGL on iOS: the engine draws the picture")
    }

    fun use() = Unit

    fun setBufferAttribute(name: String, values: FloatArray, size: Int) = Unit

    fun setSamplerTexIdUniform(name: String, texId: Int, texUnitIndex: Int) = Unit

    fun setFloatUniform(name: String, value: Float) = Unit

    fun setFloatsUniform(name: String, value: FloatArray) = Unit

    fun setIntUniform(name: String, value: Int) = Unit

    fun bindAttributesAndUniforms() = Unit

    fun delete() = Unit
}
