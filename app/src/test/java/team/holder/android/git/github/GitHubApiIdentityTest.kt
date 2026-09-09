package team.holder.android.git.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubApiIdentityTest {
    @Test
    fun personalInstallationRequiresTheAuthenticatedUsersImmutableNumericId() {
        val user = GitHubAuthenticatedUser(id = 42L, login = "renamed-alice")
        val sameLoginWrongId = GitHubInstallation(
            id = 1L,
            accountId = 7L,
            accountLogin = "renamed-alice",
            accountType = "User",
        )
        val matchingIdDifferentLogin = GitHubInstallation(
            id = 2L,
            accountId = 42L,
            accountLogin = "old-alice-handle",
            accountType = "User",
        )

        assertEquals(
            matchingIdDifferentLogin,
            GitHubApi.personalInstallationFor(user, listOf(sameLoginWrongId, matchingIdDifferentLogin)),
        )
        assertNull(GitHubApi.personalInstallationFor(user, listOf(sameLoginWrongId)))
    }
}
