package io.github.xchacha20_poly1305.libghostty

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal actual class SnapshotBuffer actual constructor(capacity: Int) {
    val storage: ByteBuffer = ByteBuffer.allocateDirect(capacity).order(ByteOrder.LITTLE_ENDIAN)
    actual fun capacity(): Int = storage.capacity()
    actual fun position(): Int = storage.position()
    actual fun position(value: Int) { storage.position(value) }
    actual fun getInt(offset: Int): Int = storage.getInt(offset)
    actual fun getLong(offset: Int): Long = storage.getLong(offset)
    actual val int: Int get() = storage.int
    actual val long: Long get() = storage.long
    actual val short: Short get() = storage.short
    actual val char: Char get() = storage.char
}
