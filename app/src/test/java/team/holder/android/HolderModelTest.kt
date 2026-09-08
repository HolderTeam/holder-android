package team.holder.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HolderModelTest {
    @Test
    fun project_preservesNullableRemoteAndUsesValueEquality() {
        val project = HolderProject(
            projectId = "project-1",
            name = "Home",
            gitRemoteUrl = null,
            privacyMode = "plain",
        )

        assertNull(project.gitRemoteUrl)
        assertEquals(project, project.copy())
    }

    @Test
    fun card_preservesNullableParentAndUsesValueEquality() {
        val card = HolderCard(
            cardId = "card-1",
            projectId = "project-1",
            title = "Welcome",
            parentCardId = null,
            createdAt = 1_700_000_000L,
            updatedAt = 1_700_000_000L,
            sortKey = 0.0,
        )

        assertNull(card.parentCardId)
        assertEquals(card, card.copy())
    }

    @Test
    fun searchResult_preservesAllDisplayedFields() {
        val result = HolderSearchResult(
            cardId = "card-1",
            title = "Welcome",
            snippet = "A matching excerpt",
        )

        assertEquals("card-1", result.cardId)
        assertEquals("Welcome", result.title)
        assertEquals("A matching excerpt", result.snippet)
    }

    @Test
    fun cardHistoryEntry_preservesGroupedSavesAndVisibleParentOids() {
        val save = HolderCardHistorySave(
            oid = "a".repeat(40),
            parentOids = listOf("b".repeat(40)),
            authoredAt = 1_700_000_000L,
            committedAt = 1_700_000_010L,
            message = "Update card Knife care",
        )
        val entry = HolderCardHistoryEntry(
            firstOid = "b".repeat(40),
            lastOid = "a".repeat(40),
            parentOids = listOf("b".repeat(40)),
            visibleParentOids = emptyList(),
            authorName = "Ezra",
            authorEmail = "ezra@example.com",
            startedAt = 1_700_000_000L,
            endedAt = 1_700_000_010L,
            kind = "updated",
            summary = "Edited card",
            commitCount = 2,
            isMerge = false,
            saves = listOf(save, save.copy(oid = "c".repeat(40))),
        )

        assertEquals(2, entry.commitCount)
        assertEquals(2, entry.saves.size)
        assertEquals(emptyList<String>(), entry.visibleParentOids)
        assertEquals(entry, entry.copy())
    }

    @Test
    fun cardHistoryPage_preservesNullableHeadAndCursorForPagination() {
        val page = HolderCardHistoryPage(
            headOid = null,
            entries = emptyList(),
            nextCursor = null,
            scanLimited = true,
        )

        assertNull(page.headOid)
        assertNull(page.nextCursor)
        assertTrue(page.scanLimited)
    }

    @Test
    fun cardHistoryVersion_reportsAbsentPreCreationStateAsNotExisting() {
        val version = HolderCardHistoryVersion(exists = false, oid = "", title = "", body = "")

        assertFalse(version.exists)
        assertEquals("", version.oid)
    }

    @Test
    fun cardHistoryDiffLine_preservesNullableLineNumbersForAddedAndRemovedLines() {
        val added = HolderCardHistoryDiffLine(origin = "+", text = "new text", oldLine = null, newLine = 3L)
        val removed = HolderCardHistoryDiffLine(origin = "-", text = "old text", oldLine = 3L, newLine = null)

        assertNull(added.oldLine)
        assertEquals(3L, added.newLine)
        assertEquals(3L, removed.oldLine)
        assertNull(removed.newLine)
    }

    @Test
    fun cardHistoryComparison_preservesTruncationAndSummary() {
        val comparison = HolderCardHistoryComparison(
            from = HolderCardHistoryVersion(exists = false, oid = "", title = "", body = ""),
            to = HolderCardHistoryVersion(exists = true, oid = "a".repeat(40), title = "Knife care", body = "..."),
            summary = "Card created",
            lines = emptyList(),
            truncated = true,
        )

        assertFalse(comparison.from.exists)
        assertTrue(comparison.to.exists)
        assertTrue(comparison.truncated)
    }

    @Test
    fun gitResults_preserveNullableErrorsAndCounters() {
        val push = GitPushResult(
            status = "ok",
            aheadCount = 2,
            behindCount = 0,
            errorMessage = null,
        )
        val pull = GitPullResult(
            status = "conflicts_resolved",
            errorMessage = "",
            conflictsResolved = 1,
        )

        assertNull(push.errorMessage)
        assertEquals(2, push.aheadCount)
        assertEquals(1, pull.conflictsResolved)
        assertEquals("", pull.errorMessage)
    }
}
