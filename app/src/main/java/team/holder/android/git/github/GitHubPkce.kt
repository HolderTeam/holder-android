package team.holder.android.git.github

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * Pure PKCE/state generation -- no persistence, no coordinator concerns. See
 * GitHubConnectionCoordinator for where these are actually used and how their
 * lifetimes are managed (process-memory only, never persisted).
 */
internal object GitHubPkce {
    private val secureRandom = SecureRandom()
    private val urlEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    /** RFC 7636 code_verifier: 32 random bytes, unpadded base64url (43 chars) -- within the
     * 43-128 character range the RFC requires. */
    fun generateCodeVerifier(): String = randomBase64Url(32)

    /** RFC 7636 S256 code_challenge: base64url(SHA-256(code_verifier)), unpadded. */
    fun codeChallengeFor(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        return urlEncoder.encodeToString(digest)
    }

    /** The OAuth `state` parameter -- correlates a callback to this device's own pending
     * attempt. No security property depends on the encoding, only on it being unguessable and
     * compared exactly. */
    fun generateState(): String = randomBase64Url(32)

    /** Disambiguates connect() operations/launches across process lifetimes -- always a random
     * UUID, never a counter (see GitHubConnectionCoordinator's doc comment for why a counter is
     * actively wrong here: it can repeat across two different process lifetimes). Never leaves
     * the device; no security property depends on its value, only on comparing it exactly. */
    fun generateAttemptId(): UUID = UUID.randomUUID()

    /** Same recipe as `generateState()` -- used for the Setup URL's `install_state` too (see
     * "install_state / pendingInstallationReturn" in the plan). Named separately for call-site
     * clarity even though the implementation is identical. */
    fun generateInstallState(): String = randomBase64Url(32)

    private fun randomBase64Url(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return urlEncoder.encodeToString(bytes)
    }
}
