package team.holder.android.git.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Covers parseGitHubOwnerRepo in isolation -- both remote URL shapes it needs to recognize
 * (see its own doc comment for why there are two: GitHub's own `ssh_url` convention vs.
 * GitSyncScreen's hand-typed `ssh://` placeholder shape), and that everything else stays a
 * clean "not GitHub, use the manual path" null rather than a false match or a crash. */
class GitHubRemoteUrlTest {
    @Test
    fun parseGitHubOwnerRepo_recognizesScpStyleWithDotGit() {
        assertEquals("zeth" to "holder-abc123", parseGitHubOwnerRepo("git@github.com:zeth/holder-abc123.git"))
    }

    @Test
    fun parseGitHubOwnerRepo_recognizesScpStyleWithoutDotGit() {
        assertEquals("zeth" to "holder-abc123", parseGitHubOwnerRepo("git@github.com:zeth/holder-abc123"))
    }

    @Test
    fun parseGitHubOwnerRepo_recognizesFullSshUrlStyle() {
        assertEquals(
            "zeth" to "holder-abc123",
            parseGitHubOwnerRepo("ssh://git@github.com/zeth/holder-abc123.git"),
        )
    }

    @Test
    fun parseGitHubOwnerRepo_recognizesFullSshUrlStyleWithoutDotGit() {
        assertEquals(
            "zeth" to "holder-abc123",
            parseGitHubOwnerRepo("ssh://git@github.com/zeth/holder-abc123"),
        )
    }

    @Test
    fun parseGitHubOwnerRepo_toleratesATrailingSlash() {
        assertEquals("zeth" to "holder-abc123", parseGitHubOwnerRepo("git@github.com:zeth/holder-abc123.git/"))
    }

    @Test
    fun parseGitHubOwnerRepo_returnsNullForANonGitHubHost() {
        assertNull(parseGitHubOwnerRepo("git@gitlab.com:zeth/holder-abc123.git"))
        assertNull(parseGitHubOwnerRepo("ssh://git@example.com/zeth/holder-abc123.git"))
    }

    @Test
    fun parseGitHubOwnerRepo_returnsNullForAnHttpsRemote() {
        assertNull(parseGitHubOwnerRepo("https://github.com/zeth/holder-abc123.git"))
    }

    @Test
    fun parseGitHubOwnerRepo_returnsNullForGarbage() {
        assertNull(parseGitHubOwnerRepo(""))
        assertNull(parseGitHubOwnerRepo("not a url at all"))
        assertNull(parseGitHubOwnerRepo("git@github.com"))
    }
}
