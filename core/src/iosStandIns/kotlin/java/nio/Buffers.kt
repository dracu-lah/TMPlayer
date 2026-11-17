package java.nio

/** Byte order for [ByteBuffer], as java.nio.ByteOrder. */
class ByteOrder private constructor(private val label: String) {
    override fun toString(): String = label

    companion object {
        val BIG_ENDIAN = ByteOrder("BIG_ENDIAN")
        val LITTLE_ENDIAN = ByteOrder("LITTLE_ENDIAN")
        fun nativeOrder(): ByteOrder = LITTLE_ENDIAN
    }
}

/** A heap ByteBuffer: the subset of java.nio.ByteBuffer core's JVM half uses. */
class ByteBuffer private constructor(private val bytes: ByteArray, private val offset: Int, capacity: Int) {
    private val cap = capacity
    private var pos = 0
    private var lim = capacity
    private var littleEndian = false

    fun capacity(): Int = cap
    fun position(): Int = pos
    fun position(newPosition: Int): ByteBuffer = apply {
        require(newPosition in 0..lim) { "position $newPosition outside 0..$lim" }
        pos = newPosition
    }
    fun limit(): Int = lim
    fun limit(newLimit: Int): ByteBuffer = apply {
        require(newLimit in 0..cap) { "limit $newLimit outside 0..$cap" }
        lim = newLimit
        if (pos > lim) pos = lim
    }
    fun remaining(): Int = lim - pos
    fun hasRemaining(): Boolean = pos < lim
    fun clear(): ByteBuffer = apply { pos = 0; lim = cap }
    fun flip(): ByteBuffer = apply { lim = pos; pos = 0 }
    fun rewind(): ByteBuffer = apply { pos = 0 }
    fun hasArray(): Boolean = true
    fun array(): ByteArray = bytes
    fun arrayOffset(): Int = offset

    fun order(): ByteOrder = if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
    fun order(order: ByteOrder): ByteBuffer = apply { littleEndian = order == ByteOrder.LITTLE_ENDIAN }

    private fun at(index: Int, size: Int): Int {
        if (index < 0 || index + size > lim) throw BufferUnderflowException()
        return offset + index
    }

    private fun take(size: Int): Int {
        if (pos + size > lim) throw BufferUnderflowException()
        return (offset + pos).also { pos += size }
    }

    private fun readUnsigned(start: Int, size: Int): Long {
        var value = 0L
        for (i in 0 until size) {
            val b = bytes[start + if (littleEndian) size - 1 - i else i].toLong() and 0xFF
            value = (value shl 8) or b
        }
        return value
    }

    private fun writeUnsigned(start: Int, size: Int, value: Long) {
        for (i in 0 until size) {
            val shift = 8 * (if (littleEndian) i else size - 1 - i)
            bytes[start + i] = (value ushr shift).toByte()
        }
    }

    fun get(): Byte = bytes[take(1)]
    fun get(index: Int): Byte = bytes[at(index, 1)]
    fun get(destination: ByteArray): ByteBuffer = get(destination, 0, destination.size)
    fun get(destination: ByteArray, destinationOffset: Int, length: Int): ByteBuffer = apply {
        val start = take(length)
        bytes.copyInto(destination, destinationOffset, start, start + length)
    }
    fun getShort(): Short = readUnsigned(take(2), 2).toShort()
    fun getShort(index: Int): Short = readUnsigned(at(index, 2), 2).toShort()
    fun getInt(): Int = readUnsigned(take(4), 4).toInt()
    fun getInt(index: Int): Int = readUnsigned(at(index, 4), 4).toInt()
    fun getLong(): Long = readUnsigned(take(8), 8)
    fun getLong(index: Int): Long = readUnsigned(at(index, 8), 8)

    fun put(value: Byte): ByteBuffer = apply { bytes[take(1)] = value }
    fun put(index: Int, value: Byte): ByteBuffer = apply { bytes[at(index, 1)] = value }
    fun put(source: ByteArray): ByteBuffer = put(source, 0, source.size)
    fun put(source: ByteArray, sourceOffset: Int, length: Int): ByteBuffer = apply {
        if (remaining() < length) throw BufferOverflowException()
        source.copyInto(bytes, offset + pos, sourceOffset, sourceOffset + length)
        pos += length
    }
    fun put(source: ByteBuffer): ByteBuffer = apply {
        val count = source.remaining()
        val chunk = ByteArray(count)
        source.get(chunk)
        put(chunk)
    }
    fun putShort(value: Short): ByteBuffer = apply { writeUnsigned(take(2), 2, value.toLong()) }
    fun putInt(value: Int): ByteBuffer = apply { writeUnsigned(take(4), 4, value.toLong()) }
    fun putInt(index: Int, value: Int): ByteBuffer = apply { writeUnsigned(at(index, 4), 4, value.toLong()) }
    fun putLong(value: Long): ByteBuffer = apply { writeUnsigned(take(8), 8, value) }
    fun putLong(index: Int, value: Long): ByteBuffer = apply { writeUnsigned(at(index, 8), 8, value) }
    fun putShort(index: Int, value: Short): ByteBuffer = apply { writeUnsigned(at(index, 2), 2, value.toLong()) }
    fun getFloat(): Float = Float.fromBits(getInt())
    fun getFloat(index: Int): Float = Float.fromBits(getInt(index))
    fun putFloat(value: Float): ByteBuffer = putInt(value.toRawBits())
    fun putFloat(index: Int, value: Float): ByteBuffer = putInt(index, value.toRawBits())

    /** 16-bit samples from the position on, in this buffer's byte order, sharing its bytes (PCM audio). */
    fun asShortBuffer(): ShortBuffer = ShortBuffer(this, pos, remaining() / 2)

    /** 32-bit floats from the position on, in this buffer's byte order, sharing its bytes (PCM audio). */
    fun asFloatBuffer(): FloatBuffer = FloatBuffer(this, pos, remaining() / 4)

    /** A buffer over the same bytes from the position to the limit. */
    fun slice(): ByteBuffer = ByteBuffer(bytes, offset + pos, remaining()).order(order())
    fun duplicate(): ByteBuffer = ByteBuffer(bytes, offset, cap).also {
        it.lim = lim
        it.pos = pos
        it.littleEndian = littleEndian
    }

    companion object {
        fun allocate(capacity: Int): ByteBuffer = ByteBuffer(ByteArray(capacity), 0, capacity)
        fun allocateDirect(capacity: Int): ByteBuffer = allocate(capacity)
        fun wrap(array: ByteArray): ByteBuffer = ByteBuffer(array, 0, array.size)
        fun wrap(array: ByteArray, offset: Int, length: Int): ByteBuffer =
            ByteBuffer(array, 0, array.size).also {
                it.pos = offset
                it.lim = offset + length
            }
    }
}

/** The characters a CharsetDecoder produced; core only turns it into a String. */
class CharBuffer internal constructor(private val text: String) : CharSequence by text {
    override fun toString(): String = text

    companion object {
        fun wrap(text: CharSequence): CharBuffer = CharBuffer(text.toString())
    }
}

open class BufferUnderflowException : RuntimeException()

open class BufferOverflowException : RuntimeException()

/** A view of a ByteBuffer's bytes as 16-bit values, java.nio's, with its own position. */
class ShortBuffer internal constructor(private val bytes: ByteBuffer, private val start: Int, private val count: Int) {
    private var pos = 0

    fun position(): Int = pos
    fun position(newPosition: Int): ShortBuffer = apply { pos = newPosition.coerceIn(0, count) }
    fun limit(): Int = count
    fun capacity(): Int = count
    fun remaining(): Int = count - pos
    fun hasRemaining(): Boolean = pos < count
    fun get(): Short = get(pos).also { pos++ }
    fun get(index: Int): Short = bytes.getShort(start + index * 2)
    fun get(destination: ShortArray, offset: Int, length: Int): ShortBuffer = apply {
        if (length > remaining()) throw BufferUnderflowException()
        for (i in 0 until length) destination[offset + i] = get()
    }
    fun put(value: Short): ShortBuffer = apply { put(pos, value); pos++ }
    fun put(index: Int, value: Short): ShortBuffer = apply { bytes.putShort(start + index * 2, value) }
    fun put(source: ShortArray, offset: Int, length: Int): ShortBuffer = apply {
        if (length > remaining()) throw BufferOverflowException()
        for (i in 0 until length) put(source[offset + i])
    }
}

/** A view of a ByteBuffer's bytes as 32-bit floats, java.nio's, with its own position. */
class FloatBuffer internal constructor(private val bytes: ByteBuffer, private val start: Int, private val count: Int) {
    private var pos = 0

    fun position(): Int = pos
    fun position(newPosition: Int): FloatBuffer = apply { pos = newPosition.coerceIn(0, count) }
    fun limit(): Int = count
    fun capacity(): Int = count
    fun remaining(): Int = count - pos
    fun hasRemaining(): Boolean = pos < count
    fun get(): Float = get(pos).also { pos++ }
    fun get(index: Int): Float = bytes.getFloat(start + index * 4)
    fun get(destination: FloatArray, offset: Int, length: Int): FloatBuffer = apply {
        if (length > remaining()) throw BufferUnderflowException()
        for (i in 0 until length) destination[offset + i] = get()
    }
    fun put(value: Float): FloatBuffer = apply { put(pos, value); pos++ }
    fun put(index: Int, value: Float): FloatBuffer = apply { bytes.putFloat(start + index * 4, value) }
    fun put(source: FloatArray, offset: Int, length: Int): FloatBuffer = apply {
        if (length > remaining()) throw BufferOverflowException()
        for (i in 0 until length) put(source[offset + i])
    }
}
