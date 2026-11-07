package io.github.xchacha20_poly1305.libghostty

internal actual class SnapshotBuffer actual constructor(capacity: Int) {
    val bytes = ByteArray(capacity)
    private var cursor = 0
    actual fun capacity(): Int = bytes.size
    actual fun position(): Int = cursor
    actual fun position(value: Int) { cursor = value }
    actual fun getInt(offset: Int): Int = (bytes[offset].toInt() and 255) or
        ((bytes[offset + 1].toInt() and 255) shl 8) or
        ((bytes[offset + 2].toInt() and 255) shl 16) or (bytes[offset + 3].toInt() shl 24)
    actual fun getLong(offset: Int): Long = (getInt(offset).toLong() and 0xffffffffL) or
        (getInt(offset + 4).toLong() shl 32)
    actual val int: Int get() = getInt(cursor).also { cursor += 4 }
    actual val long: Long get() = getLong(cursor).also { cursor += 8 }
    actual val short: Short get() = ((bytes[cursor].toInt() and 255) or
        (bytes[cursor + 1].toInt() shl 8)).toShort().also { cursor += 2 }
    actual val char: Char get() = short.toInt().toChar()
}
