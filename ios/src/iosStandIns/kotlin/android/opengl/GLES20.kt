package android.opengl

/** OpenGL ES 2's calls and names, Android's; no OpenGL runs on iOS (the engine draws). */
object GLES20 {
    const val GL_TRIANGLE_STRIP = 5
    const val GL_TRIANGLES = 4
    const val GL_TEXTURE_2D = 3553
    const val GL_TEXTURE0 = 33984

    fun glDrawArrays(mode: Int, first: Int, count: Int) = Unit

    fun glViewport(x: Int, y: Int, width: Int, height: Int) = Unit

    fun glClearColor(red: Float, green: Float, blue: Float, alpha: Float) = Unit
}
