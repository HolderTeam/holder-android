package team.holder.android.git.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubCredentialRecordTest {
    @Test
    fun roundTripsTheWholeVersionedCredentialAsOneRecord() {
        val credential = StoredGitHubCredential("ghr_current", "cap_current", 1_800_000_000_000L)

        assertEquals(credential, GitHubCredentialRecord.decode(GitHubCredentialRecord.encode(credential)))
    }

    @Test
    fun rejectsAnIncompleteOrUnknownVersionCredentialRecord() {
        assertNull(GitHubCredentialRecord.decode("{\"version\":1,\"refresh_token\":\"ghr\"}"))
        assertNull(
            GitHubCredentialRecord.decode(
                "{\"version\":2,\"refresh_token\":\"ghr\",\"refresh_cap\":\"cap\",\"refresh_token_expires_at\":1}",
            ),
        )
    }

    @Test
    fun derivesThePersistedRefreshExpiryFromTheRelayLifetimeAndWallClock() {
        val tokens = GitHubTokens(
            accessToken = "gho_ephemeral",
            expiresInSeconds = 28_800,
            refreshToken = "ghr_current",
            refreshCap = "cap_current",
            refreshTokenExpiresInSeconds = 60,
        )

        val credential = GitHubConnectionCoordinator.durableCredential(tokens, wallClockMillis = 1_000L)

        assertEquals(61_000L, credential.refreshTokenExpiresAtMillis)
    }
}
