package team.holder.android.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

class TrailingWhitespaceTrimTest {

    private fun trim(
        text: String,
        preserve: Boolean = false,
        trimTwoSpaceLineEndings: Boolean = false,
        trimWhitespaceInCodeBlocks: Boolean = false,
    ) = trimTrailingWhitespaceForSave(text, preserve, trimTwoSpaceLineEndings, trimWhitespaceInCodeBlocks)

    @Test
    fun `default mode leaves a line with no trailing whitespace unchanged`() {
        assertEquals("foo", trim("foo"))
    }

    @Test
    fun `default mode removes a single trailing space`() {
        assertEquals("foo", trim("foo "))
    }

    @Test
    fun `default mode preserves exactly two trailing spaces`() {
        assertEquals("foo  ", trim("foo  "))
    }

    @Test
    fun `default mode reduces three or more trailing spaces to two`() {
        assertEquals("foo  ", trim("foo    "))
    }

    @Test
    fun `default mode removes trailing tabs`() {
        assertEquals("foo", trim("foo\t"))
    }

    @Test
    fun `default mode does not credit spaces before a trailing tab as a hard break`() {
        assertEquals("foo", trim("foo  \t"))
    }

    @Test
    fun `default mode cleans a whitespace-only line to empty`() {
        assertEquals("", trim("   \t "))
    }

    @Test
    fun `default mode processes every line independently`() {
        assertEquals("a\nb  \nc", trim("a \nb    \nc\t"))
    }

    @Test
    fun `preserve on leaves everything exactly as typed`() {
        val raw = "a \nb    \nc\t\n   "
        assertEquals(raw, trim(raw, preserve = true))
    }

    @Test
    fun `preserve on overrides the other two settings`() {
        val raw = "foo  "
        assertEquals(raw, trim(raw, preserve = true, trimTwoSpaceLineEndings = true, trimWhitespaceInCodeBlocks = true))
    }

    @Test
    fun `trim two-space line endings strips a genuine hard-break run to zero`() {
        assertEquals("foo", trim("foo  ", trimTwoSpaceLineEndings = true))
        assertEquals("foo", trim("foo     ", trimTwoSpaceLineEndings = true))
    }

    @Test
    fun `trim two-space line endings does not change ordinary trailing whitespace handling`() {
        assertEquals("foo", trim("foo ", trimTwoSpaceLineEndings = true))
        assertEquals("foo", trim("foo\t", trimTwoSpaceLineEndings = true))
    }

    @Test
    fun `code block lines are untouched by default even with trailing whitespace`() {
        val raw = "before\n```\ncode  \nmore\t\n```\nafter "
        val expected = "before\n```\ncode  \nmore\t\n```\nafter"
        assertEquals(expected, trim(raw))
    }

    @Test
    fun `trim whitespace in code blocks strips everything there unconditionally`() {
        val raw = "```\ncode  \nmore\t\n```"
        val expected = "```\ncode\nmore\n```"
        assertEquals(expected, trim(raw, trimWhitespaceInCodeBlocks = true))
    }

    @Test
    fun `trim whitespace in code blocks does not canonicalize -- it always strips fully`() {
        // Unlike ordinary lines, a two-space run inside code has no hard-break meaning, so it
        // goes to zero here even though trimTwoSpaceLineEndings is off.
        assertEquals("```\ncode\n```", trim("```\ncode  \n```", trimWhitespaceInCodeBlocks = true))
    }

    @Test
    fun `code block fence lines themselves are excluded from ordinary trimming too`() {
        // The opening fence line has no trailing whitespace here, but content immediately
        // outside the fence should still be cleaned normally.
        val raw = "```\ncode\n```\nafter "
        assertEquals("```\ncode\n```\nafter", trim(raw))
    }

    @Test
    fun `two independent code blocks are both exempt by default`() {
        val raw = "```\na  \n```\ntext\n```\nb  \n```"
        val expected = "```\na  \n```\ntext\n```\nb  \n```"
        assertEquals(expected, trim(raw))
    }

    @Test
    fun `an unfenced trailing whitespace line between two code blocks is still cleaned`() {
        val raw = "```\na\n```\nmid \n```\nb\n```"
        val expected = "```\na\n```\nmid\n```\nb\n```"
        assertEquals(expected, trim(raw))
    }
}
