package androidx.media3.common.util

/** Media3's byte array with a read position, as much as the stand ins name it. */
class ParsableByteArray(var data: ByteArray, private var limit: Int) {
    constructor() : this(ByteArray(0), 0)

    constructor(limit: Int) : this(ByteArray(limit), limit)

    constructor(data: ByteArray) : this(data, data.size)

    private var position = 0

    fun getPosition(): Int = position

    fun setPosition(position: Int) {
        this.position = position.coerceIn(0, limit)
    }

    fun limit(): Int = limit

    fun bytesLeft(): Int = limit - position

    fun skipBytes(bytes: Int) = setPosition(position + bytes)

    fun readBytes(buffer: ByteArray, offset: Int, length: Int) {
        data.copyInto(buffer, offset, position, position + length)
        position += length
    }

    fun reset(limit: Int) {
        if (data.size < limit) data = ByteArray(limit)
        this.limit = limit
        position = 0
    }
}
