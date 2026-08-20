package team.holder.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
