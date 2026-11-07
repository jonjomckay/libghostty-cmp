package io.github.xchacha20_poly1305.libghostty

/** Little-endian snapshot storage; JVM uses a direct buffer, Wasm copies from linear memory. */
internal expect class SnapshotBuffer(capacity: Int) {
    fun capacity(): Int
    fun position(): Int
    fun position(value: Int)
    fun getInt(offset: Int): Int
    fun getLong(offset: Int): Long
    val int: Int
    val long: Long
    val short: Short
    val char: Char
}
