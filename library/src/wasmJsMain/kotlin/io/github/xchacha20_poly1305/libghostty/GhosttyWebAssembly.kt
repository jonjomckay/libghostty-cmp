@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.xchacha20_poly1305.libghostty

import kotlin.js.JsAny
import kotlin.js.Promise
import kotlinx.coroutines.await

/** Loads the Ghostty Wasm backend before creating a [GhosttyTerminalSession]. Browser event-loop only. */
public object GhosttyWebAssembly {
    /** Load the Wasm binary shipped with this version of the library. Hosts may override its asset URL. */
    public suspend fun load(url: String = "ghostty-vt.wasm") {
        initializeWasm(fetchBytes(url).await()).await()
    }

    /** Initialize from bytes, allowing hosts to provide cached or embedded resources without networking. */
    public suspend fun initialize(bytes: ByteArray) {
        initializeWasm(bytes.toBrowserBytes()).await()
    }
}

@JsFun("""async (bytes) => {
    if (!globalThis.__libghosttyWasmLoading) {
        globalThis.__libghosttyWasmLoading = WebAssembly.instantiate(bytes, {}).then(({instance}) => {
            if (instance.exports.ghostty_abi_version() !== 1) throw new Error('Ghostty Wasm ABI mismatch');
            globalThis.__libghosttyWasm = instance.exports;
        }).catch(error => { delete globalThis.__libghosttyWasmLoading; throw error; });
    }
    await globalThis.__libghosttyWasmLoading;
}""")
private external fun initializeWasm(bytes: JsAny): Promise<JsAny?>

@JsFun("async (url) => { const response = await fetch(url); if (!response.ok) throw new Error('Cannot load Ghostty: ' + response.status); return new Uint8Array(await response.arrayBuffer()); }")
private external fun fetchBytes(url: String): Promise<JsAny>

@JsFun("(size) => new Uint8Array(size)")
private external fun newBrowserBytes(size: Int): JsAny
@JsFun("(bytes, index, value) => { bytes[index] = value; }")
private external fun setBrowserByte(bytes: JsAny, index: Int, value: Int)
@JsFun("(bytes, index) => bytes[index]")
private external fun browserByte(bytes: JsAny, index: Int): Int
@JsFun("(bytes) => bytes.length")
private external fun browserByteCount(bytes: JsAny): Int

private fun ByteArray.toBrowserBytes(): JsAny = newBrowserBytes(size).also { bytes ->
    for (i in indices) setBrowserByte(bytes, i, this[i].toInt() and 255)
}

private external interface WasmResult : JsAny {
    val value: Int
    val bytes: JsAny
}

@JsFun("""(op, handle, a, b, c, d, e, f, input) => {
    const wasm = globalThis.__libghosttyWasm;
    if (!wasm) throw new Error('Call GhosttyWebAssembly.load() before creating a terminal');
    const pointer = input.length ? wasm.ghostty_alloc(input.length) : 0;
    if (input.length && !pointer) throw new Error('Ghostty Wasm allocation failed');
    try {
        if (input.length) new Uint8Array(wasm.memory.buffer, pointer, input.length).set(input);
        const value = wasm.ghostty_call(op, handle, a, b, c, d, e, f, pointer, input.length);
        const length = wasm.ghostty_result_len();
        const bytes = new Uint8Array(wasm.memory.buffer, wasm.ghostty_result_ptr(), length).slice();
        return {value, bytes};
    } finally {
        if (input.length) wasm.ghostty_free(pointer, input.length);
    }
}""")
private external fun callWasm(op: Int, handle: Int, a: Int, b: Int, c: Int, d: Int, e: Int, f: Int, input: JsAny): WasmResult

internal actual fun platformBackend(): GhosttyBackend = GhosttyWasmBackend

// Mirrored by native/src/wasm.zig; bump the ABI version if the wire contract changes.
private enum class WasmOperation(val code: Int) {
    CREATE(0),
    FREE(1),
    WRITE(2),
    RESIZE(3),
    TAKE_EVENTS(4),
    TITLE(5),
    TAKE_CLIPBOARD(6),
    PWD(7),
    TAKE_NOTIFICATION(8),
    PROGRESS(9),
    COLOR_SCHEME(10),
    FOCUS(11),
    HYPERLINK(12),
    THEME(13),
    PARSE_COLOR(15),
    SCROLL(16),
    SCROLL_BOTTOM(17),
    SCROLLBAR(18),
    VIEWPORT_STATE(19),
    SNAPSHOT(20),
    KITTY_PLACEMENTS(21),
    KITTY_IMAGE(22),
    ENCODE_KEY(23),
    ENCODE_MOUSE(24),
    SELECT_WORD(25),
    SELECT_ALL(26),
    SET_SELECTION(27),
    CLEAR_SELECTION(28),
    SELECTION_TEXT(29),
    VIEWPORT_TEXT(30),
    IS_PASTE_SAFE(31),
    ENCODE_PASTE(32),
    TRANSCRIPT(33),
    BACKGROUND(34),
}

private object GhosttyWasmBackend : GhosttyBackend {
    private fun call(op: WasmOperation, handle: Long = 0, a: Int = 0, b: Int = 0, c: Int = 0,
                     d: Int = 0, e: Int = 0, f: Int = 0, data: ByteArray = byteArrayOf()): WasmResult =
        callWasm(op.code, handle.toInt(), a, b, c, d, e, f, data.toBrowserBytes())
    private fun WasmResult.data(): ByteArray? = browserByteCount(bytes).takeIf { it > 0 }?.let { size ->
        ByteArray(size) { browserByte(bytes, it).toByte() }
    }
    private fun WasmResult.ints(): IntArray? = data()?.let { data ->
        IntArray(data.size / 4) { index -> readInt(data, index * 4) }
    }
    private fun WasmResult.longs(): LongArray? = data()?.let { data ->
        LongArray(data.size / 8) { index -> (readInt(data, index * 8).toLong() and 0xffffffffL) or
            (readInt(data, index * 8 + 4).toLong() shl 32) }
    }
    private fun readInt(data: ByteArray, offset: Int): Int = (0..3).fold(0) { value, i ->
        value or ((data[offset + i].toInt() and 255) shl (i * 8))
    }
    override fun nativeCreate(cols: Int, rows: Int, maxScrollbackLines: Long, xtversion: String,
        terminfoName: String?, cursorStyle: Int, cursorBlink: Boolean, graphemeCluster: Boolean,
        callbacks: GhosttyVtCallbacks?): Long = call(WasmOperation.CREATE, a = cols, b = rows,
        c = maxScrollbackLines.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), d = cursorStyle,
        e = if (cursorBlink) 1 else 0, f = if (graphemeCluster) 1 else 0,
        data = (xtversion + '\u0000' + terminfoName.orEmpty()).encodeToByteArray()).value.toUInt().toLong()
        .also { check(it != 0L) { "Cannot create Ghostty Wasm terminal" } }
    override fun nativeFree(handle: Long) { call(WasmOperation.FREE, handle) }
    override fun nativeWrite(handle: Long, data: ByteArray, offset: Int, length: Int): ByteArray? {
        require(offset >= 0 && length >= 0 && offset <= data.size - length)
        return call(WasmOperation.WRITE, handle, data = data.copyOfRange(offset, offset + length)).data()
    }
    override fun nativeResize(handle: Long, cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) { call(WasmOperation.RESIZE, handle, cols, rows, cellWidthPx, cellHeightPx) }
    override fun nativeTakeEventFlags(handle: Long): Int = call(WasmOperation.TAKE_EVENTS, handle).value
    override fun nativeGetTitle(handle: Long): ByteArray? = call(WasmOperation.TITLE, handle).data()
    override fun nativeTakeClipboard(handle: Long): ByteArray? = call(WasmOperation.TAKE_CLIPBOARD, handle).data()
    override fun nativeGetPwd(handle: Long): ByteArray? = call(WasmOperation.PWD, handle).data()
    override fun nativeTakeNotification(handle: Long): ByteArray? = call(WasmOperation.TAKE_NOTIFICATION, handle).data()
    override fun nativeGetProgress(handle: Long): IntArray? = call(WasmOperation.PROGRESS, handle).ints()
    override fun nativeSetColorScheme(handle: Long, dark: Boolean): ByteArray? = call(WasmOperation.COLOR_SCHEME, handle, if (dark) 1 else 0).data()
    override fun nativeEncodeFocus(handle: Long, gained: Boolean): ByteArray? = call(WasmOperation.FOCUS, handle, if (gained) 1 else 0).data()
    override fun nativeHyperlinkAt(handle: Long, col: Int, row: Int): ByteArray? = call(WasmOperation.HYPERLINK, handle, col, row).data()
    override fun nativeSetTheme(handle: Long, foreground: Long, background: Long, cursor: Long, palette: LongArray?) {
        val values = longArrayOf(foreground, background, cursor) + (palette ?: longArrayOf())
        val data = ByteArray(values.size * 8) { index -> (values[index / 8] ushr ((index % 8) * 8)).toByte() }
        call(WasmOperation.THEME, handle, data = data)
    }
    override fun nativeParseColor(color: String): Int = call(WasmOperation.PARSE_COLOR, data = color.encodeToByteArray()).value
    override fun nativeScroll(handle: Long, deltaRows: Int) { call(WasmOperation.SCROLL, handle, deltaRows) }
    override fun nativeScrollToBottom(handle: Long) { call(WasmOperation.SCROLL_BOTTOM, handle) }
    override fun nativeScrollbar(handle: Long): LongArray? = call(WasmOperation.SCROLLBAR, handle).longs()
    override fun nativeViewportState(handle: Long): Int = call(WasmOperation.VIEWPORT_STATE, handle).value
    override fun nativeSnapshot(handle: Long, buffer: SnapshotBuffer, full: Boolean): Int {
        val result = call(WasmOperation.SNAPSHOT, handle, buffer.capacity(), if (full) 1 else 0)
        result.data()?.copyInto(buffer.bytes)
        return result.value
    }
    override fun nativeKittyPlacements(handle: Long): LongArray? = null
    override fun nativeKittyImage(handle: Long, imageId: Int): IntArray? = null
    override fun nativeEncodeKey(handle: Long, key: Int, action: Int, mods: Int, unshiftedCodepoint: Int,
        composing: Boolean, utf8: ByteArray?): ByteArray? = call(WasmOperation.ENCODE_KEY, handle, key, action, mods,
        unshiftedCodepoint, if (composing) 1 else 0, data = utf8 ?: byteArrayOf()).data()
    override fun nativeEncodeMouse(handle: Long, action: Int, button: Int, col: Int, row: Int, mods: Int): ByteArray? = call(WasmOperation.ENCODE_MOUSE, handle, action, button, col, row, mods).data()
    override fun nativeSelectWord(handle: Long, col: Int, row: Int): Boolean = call(WasmOperation.SELECT_WORD, handle, col, row).value != 0
    override fun nativeSelectAll(handle: Long): Boolean = call(WasmOperation.SELECT_ALL, handle).value != 0
    override fun nativeSetSelection(handle: Long, anchorCol: Int, anchorRow: Int, col: Int, row: Int): Boolean = call(WasmOperation.SET_SELECTION, handle, anchorCol, anchorRow, col, row).value != 0
    override fun nativeClearSelection(handle: Long) { call(WasmOperation.CLEAR_SELECTION, handle) }
    override fun nativeSelectionText(handle: Long): ByteArray? = call(WasmOperation.SELECTION_TEXT, handle).data()
    override fun nativeViewportText(handle: Long): ByteArray? = call(WasmOperation.VIEWPORT_TEXT, handle).data()
    override fun nativeIsPasteSafe(data: ByteArray): Boolean = call(WasmOperation.IS_PASTE_SAFE, data = data).value != 0
    override fun nativeEncodePaste(handle: Long, data: ByteArray): ByteArray? = call(WasmOperation.ENCODE_PASTE, handle, data = data).data()
    override fun nativeTranscriptText(handle: Long): ByteArray? = call(WasmOperation.TRANSCRIPT, handle).data()
    override fun nativeGetBackgroundColor(handle: Long): Int = call(WasmOperation.BACKGROUND, handle).value
}
