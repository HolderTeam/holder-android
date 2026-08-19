package team.holder.android

private val ATX_HEADING = Regex("^#{1,6}[ \t].*")

/**
 * Extracts the title implied by a card's body under "first line is the title" mode: the first
 * non-blank line, verbatim, with a leading Markdown heading marker stripped if present. Doesn't
 * require valid Markdown -- any first line is accepted as the title as-is.
 */
fun titleFromFirstLine(content: String): String {
    val line = content.lineSequence().firstOrNull { it.isNotBlank() } ?: return ""
    val trimmed = line.trim()
    return if (ATX_HEADING.matches(trimmed)) trimmed.trimStart('#').trim() else trimmed
}

/**
 * Splits a card body that starts with a `# Title` heading (as written by [combineTitleAndBody])
 * into the remaining body, for "Separate title" editing where that heading line is redundant
 * with a distinct Title field. Returns null if the body doesn't start with a heading -- there's
 * nothing to hide, so the caller should show the body unchanged.
 */
fun splitLeadingHeading(content: String): String? {
    val firstNewline = content.indexOf('\n')
    val firstLine = if (firstNewline == -1) content else content.substring(0, firstNewline)
    if (!ATX_HEADING.matches(firstLine.trim())) return null
    if (firstNewline == -1) return ""
    var rest = content.substring(firstNewline + 1)
    if (rest.startsWith("\n")) rest = rest.substring(1)
    return rest
}

/**
 * Reconstructs a card body with the title embedded as a leading `# Title` heading, the
 * convention every Holder client expects a card's first line to follow.
 */
fun combineTitleAndBody(title: String, body: String): String =
    if (body.isEmpty()) "# $title\n\n" else "# $title\n\n$body"
