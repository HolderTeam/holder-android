package team.holder.android.git.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val BASE64_URL_CHARS = Regex("^[A-Za-z0-9_-]+$")

class GitHubPkceTest {
    @Test
    fun generateCodeVerifier_isWithinRfc7636LengthBounds() {
        val verifier = GitHubPkce.generateCodeVerifier()
        assertTrue("code_verifier must be 43-128 chars, was ${verifier.length}", verifier.length in 43..128)
        assertTrue(BASE64_URL_CHARS.matches(verifier))
    }

    @Test
    fun generateCodeVerifier_isDifferentEveryCall() {
        assertNotEquals(GitHubPkce.generateCodeVerifier(), GitHubPkce.generateCodeVerifier())
    }

    @Test
    fun codeChallengeFor_isDeterministicForTheSameVerifier() {
        val verifier = GitHubPkce.generateCodeVerifier()
        assertEquals(GitHubPkce.codeChallengeFor(verifier), GitHubPkce.codeChallengeFor(verifier))
    }

    @Test
    fun codeChallengeFor_differsForDifferentVerifiers() {
        assertNotEquals(
            GitHubPkce.codeChallengeFor(GitHubPkce.generateCodeVerifier()),
            GitHubPkce.codeChallengeFor(GitHubPkce.generateCodeVerifier()),
        )
    }

    @Test
    fun codeChallengeFor_isA43CharacterUnpaddedBase64UrlSha256Digest() {
        val challenge = GitHubPkce.codeChallengeFor(GitHubPkce.generateCodeVerifier())
        assertEquals(43, challenge.length) // 32 raw bytes, base64url, no padding
        assertTrue(BASE64_URL_CHARS.matches(challenge))
    }

    @Test
    fun codeChallengeFor_matchesAKnownRfc7636TestVector() {
        // From RFC 7636 Appendix B.
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", GitHubPkce.codeChallengeFor(verifier))
    }

    @Test
    fun generateState_isDifferentEveryCall() {
        assertNotEquals(GitHubPkce.generateState(), GitHubPkce.generateState())
    }

    @Test
    fun generateInstallState_isDifferentEveryCall() {
        assertNotEquals(GitHubPkce.generateInstallState(), GitHubPkce.generateInstallState())
    }

    @Test
    fun generateAttemptId_isDifferentEveryCall_neverACounter() {
        // A counter would repeat across process lifetimes -- see GitHubConnectionCoordinator's
        // doc comment for why that's actively wrong here, not just a style preference.
        val ids = List(50) { GitHubPkce.generateAttemptId() }
        assertEquals(ids.size, ids.toSet().size)
    }
}
