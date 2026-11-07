package io.github.xchacha20_poly1305.libghostty.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.*
import io.github.xchacha20_poly1305.libghostty.GhosttyKey

/**
 * The editor the input method edits: just its own composition, which the terminal draws at the cursor until the
 * input method commits it.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal class CompositionRequest(private val controller: TerminalController, private val retainCommittedText: Boolean = false) : PlatformTextInputMethodRequest {

    private val processor = EditProcessor()
    private var editorValue by mutableStateOf(TextFieldValue())

    fun reset() {
        editorValue = TextFieldValue()
        processor.reset(editorValue, null)
    }

    override val state: TextEditorState = object : TextEditorState {
        override val text: String
            get() = if (retainCommittedText) editorValue.text else controller.composingText.orEmpty()
        override val length: Int
            get() = text.length

        override fun get(index: Int): Char = text[index]

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = text.subSequence(startIndex, endIndex)

        override val selection: TextRange
            get() = if (retainCommittedText) editorValue.selection else TextRange(text.length)
        override val composition: TextRange?
            get() = if (retainCommittedText) editorValue.composition else controller.composingText?.let { TextRange(0, it.length) }
    }

    override val value: () -> TextFieldValue = {
        if (retainCommittedText) editorValue else TextFieldValue(state.text, state.selection, state.composition)
    }

    override val imeOptions: ImeOptions = ImeOptions(autoCorrect = false, imeAction = ImeAction.None)

    override val onEditCommand: (List<EditCommand>) -> Unit = { commands ->
        if (retainCommittedText) editorValue = processor.apply(commands)
        for (command in commands) when (command) {
            is CommitTextCommand -> { controller.setComposing(null); controller.sendText(command.text) }
            is SetComposingTextCommand -> controller.setComposing(command.text)
            is FinishComposingTextCommand -> {
                val composing = controller.composingText
                controller.setComposing(null)
                if (composing != null) controller.sendText(composing)
            }
            is BackspaceCommand -> controller.sendKey(GhosttyKey.BACKSPACE)
            is DeleteSurroundingTextCommand -> repeat(command.lengthBeforeCursor) { controller.sendKey(GhosttyKey.BACKSPACE) }
            is DeleteSurroundingTextInCodePointsCommand -> repeat(command.lengthBeforeCursor) { controller.sendKey(GhosttyKey.BACKSPACE) }
        }
    }

    override val onImeAction: ((ImeAction) -> Unit)? = null

    override val textLayoutResult: () -> TextLayoutResult? = { null }

    /** Where candidate windows go: the cursor cell. */
    override val focusedRectInRoot: () -> Rect? = { controller.cursorRectInRoot() }

    override val textFieldRectInRoot: () -> Rect? = { controller.cursorRectInRoot() }

    override val textClippingRectInRoot: () -> Rect? = { null }

    override val unclippedTextOffsetInRoot: () -> Offset? = { null }

    override val editText: (block: TextEditingScope.() -> Unit) -> Unit = { block ->
        Snapshot.withMutableSnapshot { EditingScope().block() }
    }

    private inner class EditingScope : TextEditingScope {
        override fun commitText(text: CharSequence, newCursorPosition: Int) {
            controller.setComposing(null)
            controller.sendText(text.toString())
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int) {
            controller.setComposing(text.toString())
        }

        override fun finishComposingText() {
            val composing = controller.composingText ?: return
            controller.setComposing(null)
            controller.sendText(composing)
        }

        override fun deleteSurroundingTextInCodePoints(lengthBeforeCursor: Int, lengthAfterCursor: Int) {
            repeat(lengthBeforeCursor) { controller.sendKey(GhosttyKey.BACKSPACE) }
        }

        override fun setSelection(start: Int, end: Int) {}

        override fun setComposingRegion(start: Int, end: Int) {}
    }
}
