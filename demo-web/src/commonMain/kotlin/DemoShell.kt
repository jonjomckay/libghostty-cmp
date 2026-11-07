/** A deterministic in-memory shell. It only produces terminal bytes; Ghostty handles VT and rendering. */
internal class DemoShell(private val output: (String) -> Unit) {
    private val directories = setOf("/", "/home", "/home/guest", "/home/guest/examples")
    private val files = mapOf(
        "/home/guest/README.txt" to "Welcome to libghostty on the web.\nThis shell is simulated; the terminal core is real Ghostty.\nNo server, no account, no network connection required.\nTry: cat examples/ansi.txt\n",
        "/home/guest/你好.txt" to "你好，世界！\n中文、组合字符 e\u0301、全角文字与 ANSI 控制序列都由 Ghostty 处理。\n",
        "/home/guest/examples/ansi.txt" to "\u001b[1mBold\u001b[0m  \u001b[3mItalic\u001b[0m  \u001b[4mUnderline\u001b[0m\n\u001b[31mRed\u001b[0m  \u001b[32mGreen\u001b[0m  \u001b[34mBlue\u001b[0m  \u001b[38;2;180;140;255mTrue color\u001b[0m\n",
    )
    private var directory = "/home/guest"
    private var line = ""
    private var cursor = 0
    private val history = mutableListOf<String>()
    private var historyPosition = 0
    private var savedLine = ""
    private var escape = ""
    private var paste = false
    private val utf8 = mutableListOf<Byte>()
    private var utf8Remaining = 0
    private var closed = false

    fun start() {
        output("\u001b]0;Ghostty Web Terminal\u0007\u001b[?2004h\u001b[1;38;2;172;191;250mGhostty, in your browser.\u001b[0m\r\n")
        output("Real terminal emulation · simulated shell · works offline\r\nType \u001b[36mhelp\u001b[0m for commands. Try the examples above.\r\n\r\n")
        prompt()
    }

    fun close() {
        closed = true
    }

    fun input(data: ByteArray) {
        if (closed) return
        for (byte in data) {
            val value = byte.toInt() and 255
            if (utf8Remaining > 0) {
                if (value in 0x80..0xbf) {
                    utf8 += byte; utf8Remaining--
                    if (utf8Remaining == 0) {
                        character(utf8.toByteArray().decodeToString())
                        utf8.clear()
                    }
                    continue
                }
                utf8.clear(); utf8Remaining = 0
            }
            if (value >= 0xc2) {
                utf8Remaining = when (value) {
                    in 0xc2..0xdf -> 1
                    in 0xe0..0xef -> 2
                    in 0xf0..0xf4 -> 3
                    else -> 0
                }
                if (utf8Remaining > 0) {
                    utf8 += byte
                    continue
                }
            }
            character(value.toChar().toString())
        }
    }

    fun example(command: String) {
        if (closed) return
        line = command
        cursor = line.length
        redraw()
        submit()
    }

    private fun character(text: String) {
        if (escape.isNotEmpty()) {
            escape += text
            if (escape == "\u001b[") return
            if (escape.length > 2 && text[0] in '@'..'~') {
                sequence(escape); escape = ""
            } else if (escape.length > 32 || (escape.length == 2 && text != "[")) {
                escape = ""
            }
            return
        }
        when (text) {
            "\u001b" -> escape = text
            "\r", "\n" -> if (paste) {
                insert(" ")
            } else {
                submit()
            }

            "\u007f", "\b" -> if (cursor > 0) {
                val previous = previousCharacter(cursor)
                line = line.removeRange(previous, cursor)
                cursor = previous; redraw()
            }

            "\u0003" -> {
                output("^C\r\n")
                line = ""
                cursor = 0
                prompt()
            }

            "\u000c" -> {
                output("\u001b[2J\u001b[H")
                redraw()
            }

            "\u0001" -> {
                cursor = 0
                redraw()
            }

            "\u0005" -> {
                cursor = line.length
                redraw()
            }

            "\u0015" -> {
                line = line.substring(cursor)
                cursor = 0; redraw()
            }

            "\t" -> complete()

            else -> if (text[0] >= ' ') insert(text)
        }
    }

    private fun complete() {
        val before = line.substring(0, cursor)
        val start = before.indexOfLast { it.isWhitespace() } + 1
        val token = before.substring(start)
        // Completion operates on an unquoted token; quoted paths remain available through ordinary editing.
        if (token.any { it == '\'' || it == '"' }) return
        val commandPosition = before.take(start).isBlank()
        val command = line.substringBefore(' ')
        val matches = if (commandPosition && '/' !in token) {
            listOf("help", "clear", "echo", "pwd", "ls", "cd", "cat", "history", "showcase").filter {
                it.startsWith(
                    token
                )
            }
        } else {
            val prefix = token.substringBeforeLast('/', "").let { if ('/' in token) "$it/" else "" }
            val name = token.substringAfterLast('/')
            val parent = resolve(prefix.ifEmpty { directory })
            val parentPrefix = if (parent == "/") "/" else "$parent/"
            (directories + if (command == "cd") emptySet() else files.keys)
                .asSequence()
                .filter { it.startsWith(parentPrefix) && it != parent }
                .map { it.removePrefix(parentPrefix).substringBefore('/') }.distinct()
                .filter { it.startsWith(name) }.sorted()
                .map { prefix + it + if (parentPrefix + it in directories) "/" else "" }
                .toList()
        }
        if (matches.isEmpty()) {
            output("\u0007")
            return
        }
        var common = matches.first()
        for (match in matches.drop(1)) {
            var length = 0
            while (length < minOf(common.length, match.length) && common[length] == match[length]) length++
            common = common.take(length)
        }
        if (common.length > token.length || matches.size == 1) {
            val completion = common + if (matches.size == 1 && !common.endsWith('/')) " " else ""
            line = line.substring(0, start) + completion + line.substring(cursor)
            cursor = start + completion.length
        } else {
            output("\r\n" + matches.joinToString("  ") + "\r\n")
        }
        redraw()
    }

    private fun sequence(sequence: String) {
        when (sequence) {
            "\u001b[200~" -> paste = true
            "\u001b[201~" -> paste = false
            "\u001b[A" -> {
                if (historyPosition == history.size) {
                    savedLine = line
                }
                if (historyPosition > 0) {
                    line = history[--historyPosition]; cursor = line.length
                    redraw()
                }
            }

            "\u001b[B" -> if (historyPosition < history.size) {
                historyPosition++; line = if (historyPosition == history.size) {
                    savedLine
                } else {
                    history[historyPosition]
                }
                cursor = line.length
                redraw()
            }

            "\u001b[D" -> if (cursor > 0) {
                cursor = previousCharacter(cursor)
                redraw()
            }

            "\u001b[C" -> if (cursor < line.length) {
                cursor = nextCharacter(cursor)
                redraw()
            }

            "\u001b[H", "\u001b[1~" -> {
                cursor = 0
                redraw()
            }

            "\u001b[F", "\u001b[4~" -> {
                cursor = line.length
                redraw()
            }

            "\u001b[3~" -> if (cursor < line.length) {
                line = line.removeRange(cursor, nextCharacter(cursor))
                redraw()
            }
        }
    }

    private fun previousCharacter(index: Int): Int =
        if (index > 1 && line[index - 1].isLowSurrogate() && line[index - 2].isHighSurrogate()) {
            index - 2
        } else {
            index - 1
        }

    private fun nextCharacter(index: Int): Int =
        if (index + 1 < line.length && line[index].isHighSurrogate() && line[index + 1].isLowSurrogate()) {
            index + 2
        } else {
            index + 1
        }

    private fun insert(text: String) {
        if (line.length + text.length > 4096) return
        line = line.substring(0, cursor) + text + line.substring(cursor)
        cursor += text.length
        redraw()
    }

    private fun prompt() {
        output(
            "\u001b[38;2;118;207;169mguest\u001b[0m:\u001b[38;2;141;171;255m${
                if (directory == "/home/guest" || directory.startsWith(
                        "/home/guest/"
                    )
                ) "~" + directory.removePrefix("/home/guest") else directory
            }\u001b[0m $ "
        )
    }

    private fun redraw() {
        output("\r\u001b[2K"); prompt(); output(line)
        // Ask Ghostty to move by display cells, including CJK wide characters.
        val suffix = line.substring(cursor)
        var cells = 0
        var i = 0
        while (i < suffix.length) {
            val ch = suffix[i]
            cells += if (ch.code in 0x2e80..0xffff && !ch.isSurrogate()) {
                2
            } else {
                1
            }
            i += if (ch.isHighSurrogate() && i + 1 < suffix.length && suffix[i + 1].isLowSurrogate()) {
                2
            } else {
                1
            }
        }
        if (cells > 0) {
            output("\u001b[${cells}D")
        }
    }

    private fun submit() {
        output("\r\n")
        val command = line
        line = ""
        cursor = 0
        if (command.isNotBlank()) {
            if (history.lastOrNull() != command) {
                history += command
            }
            if (history.size > 200) history.removeAt(0)
            try {
                execute(tokenize(command))
            } catch (e: IllegalArgumentException) {
                printLine(e.message ?: "Invalid command")
            }
        }
        historyPosition = history.size
        savedLine = ""
        prompt()
    }

    private fun printLine(text: String) {
        output(text.replace("\n", "\r\n") + "\r\n")
    }

    private fun execute(args: List<String>) {
        if (args.isEmpty()) return
        when (args[0]) {
            "help" -> printLine("help                 Show this help\nclear                Clear the viewport\necho [-e] [-n] TEXT   Print text; -e interprets \\e \\xHH \\n \\r \\t \\b \\\\\npwd / ls [PATH]      Explore the in-memory filesystem\ncd [PATH]            Change directory\ncat FILE...          Display a file\nhistory              Show command history\nshowcase             Show Unicode, emoji, styles and colors\n\nTab: complete commands and paths · Up/Down: history · Left/Right: edit · Ctrl+C: cancel · Ctrl+L: clear\nCopy: Ctrl+Shift+C (Mac: Cmd+C) · Paste: Ctrl+V / Cmd+V\nThis is a demo shell, not an operating system.")
            "showcase" -> {
                printLine("\u001b[1mText & Unicode\u001b[0m\nHello, world!  你好，世界！  日本語  한국어\ne\u0301  a\u0308  o\u0302  —  ┌─┬─┐  →  ∞\n😀 😎 🚀 🎉 ❤️ 👍🏽 👨‍👩‍👧‍👦 🇨🇳 🇺🇳\n")
                output(files.getValue("/home/guest/examples/ansi.txt").replace("\n", "\r\n"))
                printLine("\n\u001b[1m16-color palette\u001b[0m")
                for (i in 0..15) {
                    output(
                        "\u001b[48;5;${i}m  ${
                            i.toString().padStart(2)
                        }  \u001b[0m" + if (i == 7 || i == 15) "\r\n" else ""
                    )
                }
                printLine("\n\u001b[1mTrue-color gradient\u001b[0m")
                for (i in 0..47) {
                    output("\u001b[48;2;${35 + i * 4};${150 + i * 2};${220 - i * 2}m ")
                }
                output("\u001b[0m\r\n\r\nControl characters: 12345\rABC → overwrites the start of this line\r\n")
                printLine("Link: \u001b]8;;https://github.com/xchacha20-poly1305/libghostty-cmp\u001b\\libghostty-cmp on GitHub\u001b]8;;\u001b\\")
            }

            "clear" -> output("\u001b[2J\u001b[H")
            "pwd" -> printLine(directory)
            "echo" -> {
                var escapes = false
                var newline = true
                var start = 1
                while (start < args.size
                    && args[start].startsWith('-')
                    && args[start].length > 1
                    && args[start].drop(1).all { it == 'e' || it == 'n' || it == 'E' }
                ) {
                    for (flag in args[start].drop(1)) {
                        when (flag) {
                            'e' -> escapes = true
                            'E' -> escapes = false
                            'n' -> newline = false
                        }
                    }
                    start++
                }
                val value = args.drop(start).joinToString(" ")
                output(if (escapes) decodeEscapes(value) else value)
                if (newline) {
                    output("\r\n")
                }
            }

            "ls" -> {
                val path = resolve(args.getOrElse(1) { directory })
                if (path in files) {
                    printLine(path.substringAfterLast('/'))
                    return
                }
                require(path in directories) { "ls: no such file or directory: $path" }
                val prefix = if (path == "/") {
                    "/"
                } else {
                    "$path/"
                }
                val entries = (directories + files.keys).filter { it.startsWith(prefix) && it != path }
                    .map { it.removePrefix(prefix).substringBefore('/') }.distinct().sorted()
                printLine(entries.joinToString("  ") { name ->
                    if (prefix + name in directories) {
                        "\u001b[34m$name/\u001b[0m"
                    } else {
                        name
                    }
                })
            }

            "cd" -> {
                val path = resolve(args.getOrElse(1) { "/home/guest" })
                require(path in directories) { "cd: no such directory: $path" }
                directory = path
            }

            "cat" -> {
                require(args.size > 1) { "cat: specify a file" }
                for (name in args.drop(1)) {
                    val content = files[resolve(name)] ?: throw IllegalArgumentException("cat: no such file: $name")
                    output(content.replace("\n", "\r\n"))
                }
            }

            "history" -> printLine(history.mapIndexed { i, entry ->
                "${i + 1}  $entry"
            }.joinToString("\n"))

            else -> printLine("${args[0]}: command not found. Type help.")
        }
    }

    private fun resolve(path: String): String {
        val expanded = if (path == "~" || path.startsWith("~/")) {
            "/home/guest" + path.drop(1)
        } else {
            path
        }
        val absolute = if (expanded.startsWith('/')) {
            expanded
        } else {
            "$directory/$expanded"
        }
        val parts = mutableListOf<String>()
        for (part in absolute.split('/')) when (part) {
            "", "." -> {}
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts += part
        }
        return "/" + parts.joinToString("/")
    }

    companion object {
        /** Quoting is intentionally small; backslashes are retained for echo -e. */
        fun tokenize(command: String): List<String> {
            val result = mutableListOf<String>()
            val word = StringBuilder()
            var quote: Char? = null
            var started = false
            var i = 0
            while (i < command.length) {
                val ch = command[i++]
                when {
                    ch == quote -> quote = null
                    quote == '\'' -> word.append(ch)
                    ch == '\\' && i < command.length && command[i] == quote -> {
                        word.append(command[i++]); started = true
                    }

                    quote == '"' -> word.append(ch)

                    ch == '\'' || ch == '"' -> {
                        quote = ch; started = true
                    }

                    ch.isWhitespace() -> if (started) {
                        result += word.toString(); word.clear()
                        started = false
                    }

                    ch == '\\' && i < command.length && command[i].isWhitespace() -> {
                        word.append(command[i++])
                        started = true
                    }

                    else -> {
                        word.append(ch)
                        started = true
                    }
                }
            }
            require(quote == null) { "Unclosed quote" }
            if (started) {
                result += word.toString()
            }
            return result
        }

        fun decodeEscapes(text: String): String = buildString {
            var i = 0
            while (i < text.length) {
                val ch = text[i++]
                if (ch != '\\' || i == text.length) {
                    append(ch)
                    continue
                }
                when (val next = text[i++]) {
                    'e', 'E' -> append('\u001b')
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    'b' -> append('\b')
                    '\\' -> append('\\')
                    'x' -> {
                        val hex = text.substring(i, minOf(i + 2, text.length))
                        val code = if (hex.length == 2) {
                            hex.toIntOrNull(16)
                        } else {
                            null
                        }
                        if (code != null) {
                            append(code.toChar())
                            i += 2
                        } else {
                            append("\\x")
                        }
                    }

                    else -> {
                        append('\\')
                        append(next)
                    }
                }
            }
        }
    }
}
