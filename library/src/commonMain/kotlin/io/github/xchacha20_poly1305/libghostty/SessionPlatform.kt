package io.github.xchacha20_poly1305.libghostty

internal expect class SessionLock() {
    fun <T> withLock(block: () -> T): T
}

internal expect fun fileUriPath(raw: String): String?
