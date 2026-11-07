@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.xchacha20_poly1305.libghostty

import kotlin.js.Promise
import kotlin.js.JsString
import kotlinx.coroutines.await

/** Browser clipboard policy. Program reads are denied; program writes require explicit confirmation. */
public class GhosttyBrowserClipboard(
    private val approveProgramWrite: (String) -> Boolean = { confirmClipboardWrite() },
) : GhosttyClipboard {
    override fun hasText(): Boolean = false
    override fun getText(): String? = null
    override fun readText(onResult: (String?) -> Unit) { readClipboardForUser(onResult) }
    override fun setText(text: String) { writeClipboard(text) }
    override fun setTextForProgram(text: String) {
        if (approveProgramWrite(text)) setText(text)
    }

    /** Must be called from a user gesture. Ordinary browser paste events also work when this API is unavailable. */
    public suspend fun readText(): String = readClipboard().await().toString()
}

@JsFun("() => window.confirm('Allow the terminal program to replace your clipboard?')")
private external fun confirmClipboardWrite(): Boolean
@JsFun("""(text) => {
    const fallback = () => {
        const input = document.createElement('textarea');
        input.value = text; input.style.position = 'fixed'; input.style.opacity = '0';
        document.body.append(input); input.select(); document.execCommand('copy'); input.remove();
    };
    if (navigator.clipboard?.writeText) navigator.clipboard.writeText(text).catch(fallback); else fallback();
}""")
private external fun writeClipboard(text: String)
@JsFun("() => navigator.clipboard.readText()")
private external fun readClipboard(): Promise<JsString>

@JsFun("""(onResult) => {
    if (!navigator.clipboard?.readText) { onResult(null); return; }
    navigator.clipboard.readText().then(text => onResult(text)).catch(() => onResult(null));
}""")
private external fun readClipboardForUser(onResult: (String?) -> Unit)
