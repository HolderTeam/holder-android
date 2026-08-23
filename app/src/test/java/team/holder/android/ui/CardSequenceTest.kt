package team.holder.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import team.holder.android.HolderCard

private fun card(
    id: String,
    parentCardId: String? = null,
    createdAt: Long = 0L,
    updatedAt: Long = createdAt,
    sortKey: Double = 0.0,
) = HolderCard(
    cardId = id,
    projectId = "project-1",
    title = id,
    parentCardId = parentCardId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    sortKey = sortKey,
)

class CardSequenceTest {
    @Test
    fun nextAndPrevious_walkAllCardsByRecencyRegardlessOfParent() {
        val a = card("a", updatedAt = 1L)
        val b = card("b", parentCardId = "other", updatedAt = 2L)
        val c = card("c", updatedAt = 3L)
        val all = listOf(a, b, c)

        val links = cardSequenceLinks("b", "other", all)

        assertEquals(c, links.next)
        assertEquals(a, links.previous)
    }

    @Test
    fun nextAndPrevious_areNullAtEitherEndOfRecencyOrder() {
        val oldest = card("oldest", updatedAt = 1L)
        val newest = card("newest", updatedAt = 2L)
        val all = listOf(oldest, newest)

        assertNull(cardSequenceLinks("newest", null, all).next)
        assertNull(cardSequenceLinks("oldest", null, all).previous)
    }

    @Test
    fun nextAndPrevious_areNullWhenCardIsMissingFromTheList() {
        val all = listOf(card("a", updatedAt = 1L), card("b", updatedAt = 2L))

        val links = cardSequenceLinks("missing", null, all)

        assertNull(links.next)
        assertNull(links.previous)
    }

    @Test
    fun followsAndPrecedes_walkOnlySiblingsSharingTheSameParentBySortKey() {
        val first = card("first", parentCardId = "parent", sortKey = 0.0)
        val middle = card("middle", parentCardId = "parent", sortKey = 1.0)
        val last = card("last", parentCardId = "parent", sortKey = 2.0)
        val stranger = card("stranger", parentCardId = "other-parent", sortKey = 1.5)
        val all = listOf(first, middle, last, stranger)

        val links = cardSequenceLinks("middle", "parent", all)

        assertEquals(last, links.follows)
        assertEquals(first, links.precedes)
    }

    @Test
    fun followsAndPrecedes_areNullForAnOnlyChild() {
        val onlyChild = card("only", parentCardId = "parent", sortKey = 0.0)
        val all = listOf(onlyChild)

        val links = cardSequenceLinks("only", "parent", all)

        assertNull(links.follows)
        assertNull(links.precedes)
    }

    @Test
    fun followsAndPrecedes_breakSortKeyTiesByMostRecentlyUpdatedFirst() {
        val staleTie = card("stale-tie", parentCardId = "parent", sortKey = 1.0, updatedAt = 1L)
        val freshTie = card("fresh-tie", parentCardId = "parent", sortKey = 1.0, updatedAt = 2L)
        val subject = card("subject", parentCardId = "parent", sortKey = 0.0)
        val all = listOf(subject, staleTie, freshTie)

        val links = cardSequenceLinks("subject", "parent", all)

        assertEquals(freshTie, links.follows)
    }

    @Test
    fun followsIsDroppedWhenItWouldDuplicateNext_andPrecedesWhenItWouldDuplicatePrevious() {
        // Two siblings where sort_key order matches update-recency order, so Follows/Precedes
        // would point at the exact same card as Next/Previous.
        val older = card("older", parentCardId = "parent", sortKey = 0.0, updatedAt = 1L)
        val subject = card("subject", parentCardId = "parent", sortKey = 1.0, updatedAt = 2L)
        val newer = card("newer", parentCardId = "parent", sortKey = 2.0, updatedAt = 3L)
        val all = listOf(older, subject, newer)

        val links = cardSequenceLinks("subject", "parent", all)

        assertEquals(newer, links.next)
        assertEquals(older, links.previous)
        assertNull(links.follows)
        assertNull(links.precedes)
    }

    @Test
    fun followsAndPrecedes_surviveDedupWhenTheyDifferFromNextAndPrevious() {
        // Sibling sort order disagrees with recency order, so Follows/Precedes carry
        // information Next/Previous don't.
        val siblingNewer = card("sibling-newer", parentCardId = "parent", sortKey = 1.0, updatedAt = 1L)
        val subject = card("subject", parentCardId = "parent", sortKey = 0.0, updatedAt = 3L)
        val unrelatedMostRecent = card("unrelated-most-recent", updatedAt = 4L)
        val all = listOf(subject, siblingNewer, unrelatedMostRecent)

        val links = cardSequenceLinks("subject", "parent", all)

        assertEquals(unrelatedMostRecent, links.next)
        assertEquals(siblingNewer, links.follows)
    }
}
