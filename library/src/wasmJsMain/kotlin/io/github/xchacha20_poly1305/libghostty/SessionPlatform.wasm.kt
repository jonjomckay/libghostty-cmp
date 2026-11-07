@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.xchacha20_poly1305.libghostty

// Browser sessions run on the browser event loop. No Kotlin threads share the terminal.
internal actual class SessionLock {
    actual fun <T> withLock(block: () -> T): T = block()
}

internal actual fun fileUriPath(raw: String): String? {
    val rest = raw.removePrefix("file://")
    val start = rest.indexOf('/')
    if (start < 0) return null
    val path = rest.substring(start).substringBefore('?').substringBefore('#')
    return decodeUriPath(path)
}

@JsFun("(text) => { try { return decodeURIComponent(text); } catch (_) { return text; } }")
private external fun decodeUriPath(text: String): String

internal actual fun logError(tag: String, message: String, error: Throwable?) {
    println("$tag: $message ${error?.message.orEmpty()}")
}
