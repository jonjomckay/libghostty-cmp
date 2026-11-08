package io.github.xchacha20_poly1305.libghostty.demo.shared

import kotlin.test.*

class DemoShellTest {
    @Test fun echoOnlyInterpretsEscapesWithFlag() {
        val out = StringBuilder()
        val shell = DemoShell { out.append(it) }
        shell.example("echo '\\e[31mred\\e[0m'")
        assertContains(out.toString(), "\\e[31mred\\e[0m\r\n")
        out.clear()
        shell.example("echo -en '\\x1b[31mred\\e[0m\\rX\\t\\b\\\\'")
        assertTrue(out.toString().endsWith("\u001b[31mred\u001b[0m\rX\t\b\\\u001b[38;2;118;207;169mguest\u001b[0m:\u001b[38;2;141;171;255m~\u001b[0m $ "))
    }
    @Test fun tokenizerPreservesEchoEscapesAndEmptyQuotedArguments() {
        assertEquals(listOf("echo", "-e", "\\e[31mhello world\\e[0m", ""), DemoShell.tokenize("echo -e \"\\e[31mhello world\\e[0m\" ''"))
        assertFailsWith<IllegalArgumentException> { DemoShell.tokenize("cat 'unclosed") }
    }
    @Test fun directoriesAreNormalizedAndCannotEscapeRoot() {
        val out = StringBuilder(); val shell = DemoShell { out.append(it) }
        shell.example("cd examples/../../../../../../")
        out.clear(); shell.example("pwd")
        assertContains(out.toString(), "\r\n/\r\n")
        out.clear(); shell.example("cat /home/guest/你好.txt")
        assertContains(out.toString(), "你好，世界！")
    }
    @Test fun inputHandlesSplitUtf8AndSplitEscapeSequences() {
        val out = StringBuilder(); val shell = DemoShell { out.append(it) }
        for (byte in "echo 中文\r".encodeToByteArray()) shell.input(byteArrayOf(byte))
        assertContains(out.toString(), "\r\n中文\r\n")
        out.clear()
        for (byte in "\u001b[A\r".encodeToByteArray()) shell.input(byteArrayOf(byte))
        assertContains(out.toString(), "\r\n中文\r\n")
    }
    @Test fun bracketedPasteDoesNotExecuteUntilEnter() {
        val out = StringBuilder(); val shell = DemoShell { out.append(it) }
        shell.input("\u001b[200~echo pasted\ntext\u001b[201~".encodeToByteArray())
        assertFalse(out.toString().contains("\r\npasted text\r\n"))
        shell.input("\r".encodeToByteArray())
        assertContains(out.toString(), "\r\npasted text\r\n")
    }
    @Test fun malformedHexRemainsLiteral() {
        assertEquals("\\xQZ\\x1\\q", DemoShell.decodeEscapes("\\xQZ\\x1\\q"))
    }
    @Test fun tabCompletesCommandsAndPaths() {
        val out = StringBuilder(); val shell = DemoShell { out.append(it) }
        shell.input("pw\t\r".encodeToByteArray())
        assertContains(out.toString(), "\r\n/home/guest\r\n")
        out.clear()
        shell.input("cat examples/a\t\r".encodeToByteArray())
        assertContains(out.toString(), "\u001b[1mBold")
        out.clear()
        shell.input("cd ex\t\r".encodeToByteArray())
        shell.input("pwd\r".encodeToByteArray())
        assertContains(out.toString(), "\r\n/home/guest/examples\r\n")
    }
    @Test fun showcaseIncludesEmojiSequencesAndHyperlinks() {
        val out = StringBuilder(); val shell = DemoShell { out.append(it) }
        shell.example("showcase")
        assertContains(out.toString(), "👨‍👩‍👧‍👦")
        assertContains(out.toString(), "\u001b]8;;https://github.com/")
    }
    @Test fun closedShellIgnoresInputAndExampleCommands() {
        val out = StringBuilder()
        val shell = DemoShell { out.append(it) }
        shell.start()
        shell.close()
        out.clear()
        shell.input("echo ignored\r".encodeToByteArray())
        shell.example("showcase")
        assertEquals("", out.toString())
    }
    @Test fun arrowEditingAndCtrlCancelUseTheSameInputStream() {
        val out = StringBuilder()
        val shell = DemoShell { out.append(it) }
        shell.input("echo ac\u001b[Db\r".encodeToByteArray())
        assertContains(out.toString(), "\r\nabc\r\n")
        out.clear()
        shell.input("echo cancelled\u0003echo kept\r".encodeToByteArray())
        assertContains(out.toString(), "^C\r\n")
        assertContains(out.toString(), "\r\nkept\r\n")
    }
}
