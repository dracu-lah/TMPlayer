package androidx.media3.common.audio

import java.nio.ByteBuffer

/** How input channels mix into output channels, Media3's. */
class ChannelMixingMatrix(
    private val inputChannelCount: Int,
    private val outputChannelCount: Int,
    coefficients: FloatArray,
) {
    private val coefficients = coefficients.copyOf()

    init {
        require(coefficients.size == inputChannelCount * outputChannelCount) { "Coefficient array length is invalid." }
    }

    fun getInputChannelCount(): Int = inputChannelCount

    companion object {
        /**
         * Media3's constant power mix, keeping the loudness. On iOS the engine does the downmix
         * itself (PipelineFeatures reads only that a fold is wanted), so these coefficients mark
         * the fold and are never applied to the sound; any channel count gets a valid matrix.
         */
        fun createForConstantPower(inputChannelCount: Int, outputChannelCount: Int): ChannelMixingMatrix {
            val half = (1.0 / kotlin.math.sqrt(2.0)).toFloat()
            val coefficients = when {
                inputChannelCount == outputChannelCount -> FloatArray(inputChannelCount * inputChannelCount) { if (it % (inputChannelCount + 1) == 0) 1f else 0f }
                inputChannelCount == 1 && outputChannelCount == 2 -> floatArrayOf(half, half)
                inputChannelCount == 2 && outputChannelCount == 1 -> floatArrayOf(half, half)
                inputChannelCount > 2 && outputChannelCount == 2 -> FloatArray(inputChannelCount * 2) { index ->
                    val input = index / 2
                    val output = index % 2
                    when (input) {
                        0 -> if (output == 0) 1f else 0f
                        1 -> if (output == 1) 1f else 0f
                        2 -> half
                        else -> if ((input % 2 == 0) == (output == 0)) half else 0f
                    }
                }
                else -> FloatArray(inputChannelCount * outputChannelCount) { half }
            }
            return ChannelMixingMatrix(inputChannelCount, outputChannelCount, coefficients)
        }
    }

    fun getOutputChannelCount(): Int = outputChannelCount

    fun getMixingCoefficient(inputChannel: Int, outputChannel: Int): Float = coefficients[inputChannel * outputChannelCount + outputChannel]

    fun isZero(): Boolean = coefficients.all { it == 0f }

    fun isSquare(): Boolean = inputChannelCount == outputChannelCount

    fun isDiagonal(): Boolean = isSquare() && coefficients.indices.all { i ->
        val row = i / outputChannelCount
        val column = i % outputChannelCount
        row == column || coefficients[i] == 0f
    }

    fun isIdentity(): Boolean = isDiagonal() && (0 until inputChannelCount).all { getMixingCoefficient(it, it) == 1f }

    fun scaleBy(scale: Float): ChannelMixingMatrix =
        ChannelMixingMatrix(inputChannelCount, outputChannelCount, FloatArray(coefficients.size) { coefficients[it] * scale })
}

/**
 * Media3's channel mixer, as Android's player builds its downmix to stereo. The engine mixes on
 * iOS: the bridge reads the matrices given here (PipelineFeatures) and asks for a downmix when one
 * folds more channels into two. Sound passes through unchanged if anything queues it here.
 */
class ChannelMixingAudioProcessor : BaseAudioProcessor() {
    private val matrices = LinkedHashMap<Int, ChannelMixingMatrix>()

    fun putChannelMixingMatrix(matrix: ChannelMixingMatrix) {
        matrices[matrix.getInputChannelCount()] = matrix
    }

    /** The matrices put here, by input channel count. */
    fun matrices(): Collection<ChannelMixingMatrix> = matrices.values.toList()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat = inputAudioFormat

    override fun queueInput(inputBuffer: ByteBuffer) {
        val out = replaceOutputBuffer(inputBuffer.remaining())
        out.put(inputBuffer)
        out.flip()
    }
}
