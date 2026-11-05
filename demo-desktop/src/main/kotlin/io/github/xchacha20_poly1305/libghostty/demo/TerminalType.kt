package io.github.xchacha20_poly1305.libghostty.demo

import java.io.IOException
import java.util.concurrent.TimeUnit

internal object TerminalType {
    private val candidates = listOf("xterm-ghostty", "xterm-256color", "xterm", "vt100", "dumb")

    // null means the database could not be queried, rather than a missing entry.
    fun detect(
        isWindows: Boolean = System.getProperty("os.name").startsWith("Windows"),
        probe: (String) -> Boolean? = ::isInstalled,
    ): String {
        for (candidate in candidates) {
            when (probe(candidate)) {
                true -> return candidate
                false -> continue
                null -> break
            }
        }
        return if (isWindows) "xterm-256color" else "dumb"
    }

    private fun isInstalled(name: String): Boolean? {
        val process = try {
            ProcessBuilder("infocmp", name)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (_: IOException) {
            return null
        }
        try {
            process.outputStream.close()
            if (!process.waitFor(250, TimeUnit.MILLISECONDS)) return null
            return process.exitValue() == 0
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
