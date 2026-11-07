package io.github.xchacha20_poly1305.libghostty.compose

import io.github.xchacha20_poly1305.libghostty.GhosttyKey

/** What a key types on a US layout without modifiers, which the Kitty keyboard protocol reports. */
internal val GhosttyKey.unshiftedCodepoint: Int
    get() {
        val name = name
        return when {
            name.startsWith("KEY_") && name.length == 5 -> name[4].lowercaseChar().code
            name.startsWith("DIGIT_") -> name[6].code
            else -> when (this) {
                GhosttyKey.SPACE -> ' '.code
                GhosttyKey.MINUS -> '-'.code
                GhosttyKey.EQUAL -> '='.code
                GhosttyKey.BRACKET_LEFT -> '['.code
                GhosttyKey.BRACKET_RIGHT -> ']'.code
                GhosttyKey.BACKSLASH -> '\\'.code
                GhosttyKey.SEMICOLON -> ';'.code
                GhosttyKey.QUOTE -> '\''.code
                GhosttyKey.BACKQUOTE -> '`'.code
                GhosttyKey.COMMA -> ','.code
                GhosttyKey.PERIOD -> '.'.code
                GhosttyKey.SLASH -> '/'.code
                else -> 0
            }
        }
    }
