package team.holder.android.ui.markdown

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

private val HEADING_REGEX = Regex("(?m)^#{1,6}[ \t].*$")
private val BOLD_REGEX = Regex("\\*\\*[^*\n]+\\*\\*|__[^_\n]+__")
private val ITALIC_REGEX = Regex("(?<!\\*)\\*[^*\n]+\\*(?!\\*)|(?<!_)_[^_\n]+_(?!_)")
private val STRIKETHROUGH_REGEX = Regex("~~[^~\n]+~~")
private val INLINE_CODE_REGEX = Regex("`[^`\n]+`")
// Fence-to-fence, so other token regexes below can be suppressed inside one -- without this, a
// code identifier like some_function_name renders as italic, and a code comment's # as a heading.
// No visual style of its own yet (no monospace/background); this is purely an exclusion zone.
private val FENCED_CODE_BLOCK_REGEX = Regex("(?m)^```[^\n]*\n[\\s\\S]*?^```[ \t]*$")
private val WIKILINK_REGEX = Regex("\\[\\[[^\\]\n]+\\]\\]")
private val MD_LINK_REGEX = Regex("\\[[^\\]\n]*\\]\\([^)\n]*\\)")
// Cosmetic only -- just flags bare URLs while typing to match what autolink will pick up in
// view mode; the actual link boundary is decided by the real autolink parser, not this.
private val BARE_URL_REGEX = Regex("\\bhttps?://[^\\s<>\"]+")

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
        val fencedRanges = FENCED_CODE_BLOCK_REGEX.findAll(text).map { it.range }.toList()
        fun insideFence(range: IntRange) = fencedRanges.any { range.first >= it.first && range.last <= it.last }

        for (match in HEADING_REGEX.findAll(text)) {
            if (insideFence(match.range)) continue
            addStyle(SpanStyle(color = headingColor, fontWeight = FontWeight.Bold), match.range.first, match.range.last + 1)
        }
        for (match in BOLD_REGEX.findAll(text)) {
            if (insideFence(match.range)) continue
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), match.range.first, match.range.last + 1)
        }
        for (match in ITALIC_REGEX.findAll(text)) {
            if (insideFence(match.range)) continue
            addStyle(SpanStyle(fontStyle = FontStyle.Italic), match.range.first, match.range.last + 1)
        }
        for (match in STRIKETHROUGH_REGEX.findAll(text)) {
            if (insideFence(match.range)) continue
            addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), match.range.first, match.range.last + 1)
        }
        for (match in INLINE_CODE_REGEX.findAll(text)) {
            if (insideFence(match.range)) continue
            addStyle(
                SpanStyle(color = codeColor, fontFamily = FontFamily.Monospace),
                match.range.first,
                match.range.last + 1,
            )
        }
        for (match in MD_LINK_REGEX.findAll(text)) {
            if (insideFence(match.range)) continue
            addStyle(SpanStyle(color = linkColor), match.range.first, match.range.last + 1)
        }
        for (match in BARE_URL_REGEX.findAll(text)) {
            if (insideFence(match.range)) continue
            addStyle(SpanStyle(color = linkColor), match.range.first, match.range.last + 1)
        }
        for (match in WIKILINK_REGEX.findAll(text)) {
            if (insideFence(match.range)) continue
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

private val HEADING_LINE_REGEX = Regex("^#{1,6}[ \t].*")

/** Wraps the current selection in `prefix`/`suffix`, or inserts an empty pair with the cursor
 * placed between them if nothing's selected. */
@OptIn(ExperimentalFoundationApi::class)
private fun TextFieldState.wrapSelection(prefix: String, suffix: String = prefix) {
    edit {
        val sel = selection
        if (sel.collapsed) {
            replace(sel.start, sel.start, prefix + suffix)
            placeCursorBeforeCharAt(sel.start + prefix.length)
        } else {
            val start = sel.min
            val end = sel.max
            replace(start, start, prefix)
            val newEnd = end + prefix.length
            replace(newEnd, newEnd, suffix)
            selection = TextRange(start + prefix.length, newEnd)
        }
    }
}

/** Adds a leading `# ` to the current line, or removes an existing heading marker if there's
 * already one -- a simple toggle rather than cycling through heading levels. */
@OptIn(ExperimentalFoundationApi::class)
private fun TextFieldState.toggleHeading() {
    edit {
        val text = asCharSequence()
        val searchFrom = selection.start - 1
        val lineStart = (if (searchFrom < 0) -1 else text.lastIndexOf('\n', searchFrom)) + 1
        val lineEnd = text.indexOf('\n', lineStart).let { if (it == -1) text.length else it }
        val line = text.substring(lineStart, lineEnd)
        if (HEADING_LINE_REGEX.matches(line)) {
            replace(lineStart, lineEnd, line.replaceFirst(Regex("^#{1,6} "), ""))
        } else {
            replace(lineStart, lineStart, "# ")
        }
    }
}

/**
 * A row of quick-insert buttons for touch typing, since raw Markdown syntax (`**`, `` ` ``,
 * `[[`) is tedious to type by hand on a soft keyboard. Acts on [state] directly; the caller
 * decides which field that is (only the body makes sense to format).
 */
@Composable
fun MarkdownFormattingToolbar(state: TextFieldState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { state.wrapSelection("**") }) {
            Text("B", fontWeight = FontWeight.Bold)
        }
        IconButton(onClick = { state.wrapSelection("*") }) {
            Text("I", fontStyle = FontStyle.Italic)
        }
        IconButton(onClick = { state.wrapSelection("~~") }) {
            Text("S", textDecoration = TextDecoration.LineThrough)
        }
        IconButton(onClick = { state.wrapSelection("`") }) {
            Text("</>", fontFamily = FontFamily.Monospace)
        }
        IconButton(onClick = { state.toggleHeading() }) {
            Text("H", fontWeight = FontWeight.Bold)
        }
        IconButton(onClick = { state.wrapSelection("[[", "]]") }) {
            Text("[[ ]]")
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
            cursorBrush = SolidColor(colorScheme.primary),
            inputTransformation = ListContinuation,
            outputTransformation = highlighter,
        )
    }
}
