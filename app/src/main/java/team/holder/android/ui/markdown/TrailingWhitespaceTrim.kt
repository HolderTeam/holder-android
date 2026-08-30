package team.holder.android.ui.markdown

// Only the trailing run at the very end of a line -- content earlier in the line is untouched.
private val TRAILING_WHITESPACE_REGEX = Regex("[ \t]+$")

/**
 * Save-time cleanup of trailing whitespace, gated by the three [team.holder.android.HolderSettings]
 * toggles -- never applied while a card is being edited; [HolderMarkdownEditor] leaves its
 * [androidx.compose.foundation.text.input.TextFieldState] untouched byte-for-byte, and this only
 * ever runs once, on the final string, right before it's persisted.
 *
 * Three independent decisions, most to least specific:
 *  1. [preserve] is the escape hatch: if on, the text is returned exactly as typed or pasted,
 *     and neither of the other two parameters has any effect.
 *  2. Otherwise, every ordinary line has its meaningless trailing whitespace removed -- 0 or 1
 *     trailing spaces, any trailing tab, or a whitespace-only line collapses to nothing. A
 *     genuine hard-break run (2 or more literal spaces, no tab in the run) is instead
 *     canonicalized down to exactly 2, since CommonMark only ever distinguishes "two or more"
 *     from fewer -- unless [trimTwoSpaceLineEndings] is on, in which case that run is stripped
 *     to 0 too, so the two-space hard-break convention can never survive a save.
 *  3. Lines inside a fenced code block are exempt from both of the above by default -- trailing
 *     whitespace there may be literal pasted content (a diff, ASCII art) -- unless
 *     [trimWhitespaceInCodeBlocks] opts back in, in which case every trailing space and tab on
 *     those lines is stripped unconditionally; the hard-break distinction from rule 2 doesn't
 *     apply inside a code block, so there's nothing to canonicalize, just strip.
 *
 * Indented (4-space) code blocks aren't recognized -- only fenced ones, matching
 * [FENCED_CODE_BLOCK_REGEX]'s own scope in [HolderMarkdownEditor]'s highlighter. Telling an
 * indented code block apart from an ordinary indented paragraph needs real block-level parsing
 * this line-by-line scanner doesn't do.
 */
internal fun trimTrailingWhitespaceForSave(
    text: String,
    preserve: Boolean,
    trimTwoSpaceLineEndings: Boolean,
    trimWhitespaceInCodeBlocks: Boolean,
): String {
    if (preserve) return text

    val codeLineIndices = buildSet {
        for (match in FENCED_CODE_BLOCK_REGEX.findAll(text)) {
            val startLine = text.substring(0, match.range.first).count { it == '\n' }
            val endLine = text.substring(0, match.range.last + 1).count { it == '\n' }
            for (line in startLine..endLine) add(line)
        }
    }

    return text.split("\n").mapIndexed { index, line ->
        if (index in codeLineIndices) {
            if (trimWhitespaceInCodeBlocks) line.trimEnd(' ', '\t') else line
        } else {
            trimOrdinaryLine(line, trimTwoSpaceLineEndings)
        }
    }.joinToString("\n")
}

private fun trimOrdinaryLine(line: String, trimTwoSpaceLineEndings: Boolean): String {
    if (line.isBlank()) return ""
    val match = TRAILING_WHITESPACE_REGEX.find(line) ?: return line
    val run = match.value
    val isHardBreakRun = run.length >= 2 && run.all { it == ' ' }
    val replacement = when {
        !isHardBreakRun -> ""
        trimTwoSpaceLineEndings -> ""
        else -> "  "
    }
    return line.substring(0, match.range.first) + replacement
}
