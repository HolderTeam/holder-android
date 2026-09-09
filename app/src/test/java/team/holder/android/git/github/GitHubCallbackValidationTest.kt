package team.holder.android.git.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubCallbackValidationTest {
    private val callbackEndpoint = "https://auth.holder.ws/android/oauth-callback"

    @Test
    fun endpointComparisonRequiresTheExactPendingSchemeHostPortAndPath() {
        assertTrue(
            GitHubConnectionCoordinator.matchesOAuthCallbackEndpointParts(
                scheme = "https",
                host = "auth.holder.ws",
                port = -1,
                path = "/android/oauth-callback",
                expectedRedirectUri = callbackEndpoint,
            ),
        )
        assertFalse(
            GitHubConnectionCoordinator.matchesOAuthCallbackEndpointParts(
                scheme = "https",
                host = "auth.holder.ws",
                port = 444,
                path = "/android/oauth-callback",
                expectedRedirectUri = callbackEndpoint,
            ),
        )
    }

    @Test
    fun parserRejectsDuplicateSecurityParametersBeforeAnyStateCanBeCompared() {
        assertNull(
            GitHubConnectionCoordinator.parseOAuthCallbackParameters(
                states = listOf("current", "attacker"),
                codes = listOf("one-time"),
                errors = emptyList(),
            ),
        )
        assertNull(
            GitHubConnectionCoordinator.parseOAuthCallbackParameters(
                states = listOf("current"),
                codes = listOf("one-time"),
                errors = listOf("access_denied"),
            ),
        )
        assertNull(
            GitHubConnectionCoordinator.parseOAuthCallbackParameters(
                states = listOf("current"),
                codes = listOf(""),
                errors = emptyList(),
            ),
        )
    }

    @Test
    fun parserAcceptsOneCodeOrErrorAndIgnoresOptionalDiagnostics() {
        assertEquals(
            GitHubConnectionCoordinator.ParsedOAuthCallback("current", null, "access_denied"),
            GitHubConnectionCoordinator.parseOAuthCallbackParameters(
                states = listOf("current"),
                codes = emptyList(),
                errors = listOf("access_denied"),
            ),
        )
    }
}
