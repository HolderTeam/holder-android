package team.holder.android.ui.markdown

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

private val HEADING_REGEX = Regex("(?m)^#{1,6}[ \t].*$")
private val BOLD_REGEX = Regex("\\*\\*[^*\n]+\\*\\*|__[^_\n]+__")
private val ITALIC_REGEX = Regex("(?<!\\*)\\*[^*\n]+\\*(?!\\*)|(?<!_)_[^_\n]+_(?!_)")
private val INLINE_CODE_REGEX = Regex("`[^`\n]+`")
private val WIKILINK_REGEX = Regex("\\[\\[[^\\]\n]+\\]\\]")
private val MD_LINK_REGEX = Regex("\\[[^\\]\n]*\\]\\([^)\n]*\\)")

/**
 * Colors the raw Markdown source without touching the underlying text -- what's stored is
 * exactly what's displayed, just decorated. Re-scans the full buffer on every change rather
 * than parsing incrementally; card bodies are small enough that this is cheap.
 */
private class HolderMarkdownHighlighter(
    private val headingColor: Color,
    private val linkColor: Color,
    private val codeColor: Color,
) : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        val text = asCharSequence()
        for (match in HEADING_REGEX.findAll(text)) {
            addStyle(SpanStyle(color = headingColor, fontWeight = FontWeight.Bold), match.range.first, match.range.last + 1)
        }
        for (match in BOLD_REGEX.findAll(text)) {
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), match.range.first, match.range.last + 1)
        }
        for (match in ITALIC_REGEX.findAll(text)) {
            addStyle(SpanStyle(fontStyle = FontStyle.Italic), match.range.first, match.range.last + 1)
        }
        for (match in INLINE_CODE_REGEX.findAll(text)) {
            addStyle(
                SpanStyle(color = codeColor, fontFamily = FontFamily.Monospace),
                match.range.first,
                match.range.last + 1,
            )
        }
        for (match in MD_LINK_REGEX.findAll(text)) {
            addStyle(SpanStyle(color = linkColor), match.range.first, match.range.last + 1)
        }
        for (match in WIKILINK_REGEX.findAll(text)) {
            addStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.Medium), match.range.first, match.range.last + 1)
        }
    }
}

private data class ListMarker(val markerText: String, val content: String)

private val BULLET_LINE = Regex("^(\\s*)([-*+]) (.*)$")
private val ORDERED_LINE = Regex("^(\\s*)(\\d+)\\. (.*)$")

private fun detectListMarker(line: String): ListMarker? {
    BULLET_LINE.matchEntire(line)?.let { m ->
        val (indent, bullet, content) = m.destructured
        return ListMarker("$indent$bullet ", content)
    }
    ORDERED_LINE.matchEntire(line)?.let { m ->
        val (indent, number, content) = m.destructured
        val next = (number.toIntOrNull() ?: 0) + 1
        return ListMarker("$indent$next. ", content)
    }
    return null
}

/**
 * Pressing Enter inside a list item continues the list with the next marker (same bullet, or
 * the incremented number); pressing Enter on an already-empty item removes its marker instead
 * of piling up empty ones, ending the list.
 */
private object ListContinuation : InputTransformation {
    @OptIn(ExperimentalFoundationApi::class)
    override fun TextFieldBuffer.transformInput() {
        if (changes.changeCount != 1) return
        val newRange = changes.getRange(0)
        val originalRange = changes.getOriginalRange(0)
        if (originalRange.length != 0 || newRange.length != 1) return
        if (charAt(newRange.start) != '\n') return

        val text = asCharSequence()
        val searchFrom = newRange.start - 1
        val lineStart = (if (searchFrom < 0) -1 else text.lastIndexOf('\n', searchFrom)) + 1
        val precedingLine = text.substring(lineStart, newRange.start)
        val marker = detectListMarker(precedingLine) ?: return

        if (marker.content.isBlank()) {
            replace(lineStart, newRange.start, "")
            placeCursorBeforeCharAt(lineStart + 1)
        } else {
            val insertAt = newRange.start + 1
            replace(insertAt, insertAt, marker.markerText)
            placeCursorBeforeCharAt(insertAt + marker.markerText.length)
        }
    }
}

/**
 * Source editor for a card's raw Markdown text. Tapping only places the cursor -- links are
 * never followed here, only in [HolderMarkdownViewer].
 */
@Composable
fun HolderMarkdownEditor(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    placeholder: String = "Write in Markdown…",
) {
    val colorScheme = MaterialTheme.colorScheme
    val highlighter = remember(colorScheme) {
        HolderMarkdownHighlighter(
            headingColor = colorScheme.primary,
            linkColor = colorScheme.tertiary,
            codeColor = colorScheme.secondary,
        )
    }

    Box(modifier = modifier) {
        if (state.text.isEmpty()) {
            Text(text = placeholder, color = colorScheme.onSurfaceVariant, style = LocalTextStyle.current)
        }
        BasicTextField(
            state = state,
            modifier = Modifier.fillMaxSize(),
            textStyle = LocalTextStyle.current.copy(color = colorScheme.onSurface),
            inputTransformation = ListContinuation,
            outputTransformation = highlighter,
        )
    }
}
