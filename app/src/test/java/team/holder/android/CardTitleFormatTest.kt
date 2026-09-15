package team.holder.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardTitleFormatTest {
    @Test
    fun titleFromFirstLine_usesFirstNonBlankLine() {
        assertEquals("A title", titleFromFirstLine("\n  A title  \nbody"))
    }

    @Test
    fun titleFromFirstLine_stripsMarkdownHeadingMarkers() {
        assertEquals("A title", titleFromFirstLine("### A title\nbody"))
    }

    @Test
    fun titleFromFirstLine_acceptsHeadingLevelsOneThroughSix() {
        for (level in 1..6) {
            assertEquals("Title $level", titleFromFirstLine("${"#".repeat(level)} Title $level"))
        }
    }

    @Test
    fun titleFromFirstLine_doesNotTreatHashWithoutFollowingWhitespaceAsHeading() {
        assertEquals("#Not a heading", titleFromFirstLine("#Not a heading"))
    }

    @Test
    fun titleFromFirstLine_handlesEmptyAndUnicodeContent() {
        assertEquals("", titleFromFirstLine(" \n\t\n"))
        assertEquals("Привет 🌍", titleFromFirstLine("Привет 🌍\r\nbody"))
    }

    @Test
    fun titleFromFirstLine_skipsALeadingResourceReferenceLine() {
        // Attaching a photo as the very first thing typed into a fresh card (see
        // ensureCardCreated in CardEditScreen.kt) would otherwise hand back the raw
        // `![label](holder://resource/<id>)` syntax itself as the "title".
        assertEquals(
            "Real title",
            titleFromFirstLine("![photo](holder://resource/abc-123)\n\nReal title\nbody"),
        )
        assertEquals("", titleFromFirstLine("![photo](holder://resource/abc-123)"))
    }

    @Test
    fun splitLeadingHeading_returnsBodyWithoutHeadingAndOneBlankSeparator() {
        assertEquals("Body\nnext", splitLeadingHeading("# Title\n\nBody\nnext"))
    }

    @Test
    fun splitLeadingHeading_returnsEmptyBodyForHeadingOnlyContent() {
        assertEquals("", splitLeadingHeading("# Title"))
    }

    @Test
    fun splitLeadingHeading_returnsNullWhenContentDoesNotStartWithHeading() {
        assertNull(splitLeadingHeading("Body\n# A later heading"))
        assertNull(splitLeadingHeading("##Title"))
    }

    @Test
    fun combineTitleAndBody_writesHolderHeadingConvention() {
        assertEquals("# Title\n\nBody", combineTitleAndBody("Title", "Body"))
        assertEquals("# Title\n\n", combineTitleAndBody("Title", ""))
    }

    @Test
    fun splitAndCombine_preserveRepresentativeSeparateTitleContent() {
        val stored = combineTitleAndBody("My card", "The body")

        assertEquals("The body", splitLeadingHeading(stored))
        assertEquals("My card", titleFromFirstLine(stored))
    }
}
