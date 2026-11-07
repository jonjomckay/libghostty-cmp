package io.github.xchacha20_poly1305.libghostty

import java.net.URI
import java.net.URISyntaxException

internal actual class SessionLock {
    actual fun <T> withLock(block: () -> T): T = synchronized(this, block)
}

internal actual fun fileUriPath(raw: String): String? = try {
    URI(raw).path
} catch (_: URISyntaxException) {
    raw.substring("file://".length).let { rest -> rest.indexOf('/').takeIf { it >= 0 }?.let(rest::substring) }
}
