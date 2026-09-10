package team.holder.android.git.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class GitHubBrowserSelectionTest {
    private val authorizationUrl =
        "https://github.com/login/oauth/authorize?client_id=holder&state=fresh"

    @Test
    fun onlyNonResultBrowserLaunchesAreCancellable() {
        assertEquals(false, GitHubConnectionCoordinator.isCancellableBrowserLaunch(GitHubConnectionCoordinator.LaunchKind.AuthTab))
        assertEquals(true, GitHubConnectionCoordinator.isCancellableBrowserLaunch(GitHubConnectionCoordinator.LaunchKind.CustomTab))
        assertEquals(true, GitHubConnectionCoordinator.isCancellableBrowserLaunch(GitHubConnectionCoordinator.LaunchKind.ExternalBrowser))
    }

    @Test
    fun pendingAuthTabForcesTheNextAttemptToUseCustomTab() {
        val requested = GitHubConnectionCoordinator.BrowserLaunch(
            GitHubConnectionCoordinator.LaunchKind.AuthTab,
            "org.example.browser",
        )
        assertEquals(
            GitHubConnectionCoordinator.BrowserLaunch(
                GitHubConnectionCoordinator.LaunchKind.CustomTab,
                "org.example.browser",
            ),
            GitHubConnectionCoordinator.browserLaunchWithOutstandingAuthTab(
                requested = requested,
                hasOutstandingAuthTab = true,
            ),
        )
        assertEquals(
            requested,
            GitHubConnectionCoordinator.browserLaunchWithOutstandingAuthTab(
                requested = requested,
                hasOutstandingAuthTab = false,
            ),
        )
    }

    @Test
    fun processRecreationRestoreIsAuthoritativeBeforeAnImmediateNewAttempt() {
        // Simulate a fresh process. Attempt A's non-secret marker is restored in onCreate,
        // then B immediately tries to register -- with no dispatcher drain or timing delay.
        GitHubConnectionCoordinator.consumeAuthTabOutstandingAttemptId()
        val attemptA = UUID.randomUUID()
        val attemptB = UUID.randomUUID()
        val requested = GitHubConnectionCoordinator.BrowserLaunch(
            GitHubConnectionCoordinator.LaunchKind.AuthTab,
            "org.example.browser",
        )

        GitHubConnectionCoordinator.restoreAuthTabOutstandingAttemptId(attemptA)
        val attemptBLaunch = GitHubConnectionCoordinator.claimAuthTabOrFallback(requested, attemptB)

        assertEquals(GitHubConnectionCoordinator.LaunchKind.CustomTab, attemptBLaunch.kind)
        assertEquals(attemptA, GitHubConnectionCoordinator.consumeAuthTabOutstandingAttemptId())
    }

    @Test
    fun lateNonOkResultFromRestoredAttemptCannotMatchTheNewerAttempt() {
        GitHubConnectionCoordinator.consumeAuthTabOutstandingAttemptId()
        val attemptA = UUID.randomUUID()
        val attemptB = UUID.randomUUID()
        val requested = GitHubConnectionCoordinator.BrowserLaunch(
            GitHubConnectionCoordinator.LaunchKind.AuthTab,
            "org.example.browser",
        )

        GitHubConnectionCoordinator.restoreAuthTabOutstandingAttemptId(attemptA)
        assertEquals(
            GitHubConnectionCoordinator.LaunchKind.CustomTab,
            GitHubConnectionCoordinator.claimAuthTabOrFallback(requested, attemptB).kind,
        )

        // A non-OK result carries no OAuth state; its consumed outstanding ID is its only
        // authority. It must still resolve to A and therefore fail the handler's B match.
        val lateAttemptAResult = GitHubConnectionCoordinator.consumeAuthTabOutstandingAttemptId()
        assertEquals(attemptA, lateAttemptAResult)
        assertFalse(
            "A's late non-OK result must not match or terminate B",
            GitHubConnectionCoordinator.authTabResultMayFinishAttempt(lateAttemptAResult!!, attemptB),
        )
    }

    @Test
    fun authTabTakesPrecedenceWhenSupported() {
        assertEquals(
            GitHubConnectionCoordinator.LaunchKind.AuthTab,
            GitHubActivityBrowserLauncher.customTabsLaunchKind(authTabSupported = true),
        )
    }

    @Test
    fun customTabRemainsTheFallbackForAProviderWithoutAuthTabSupport() {
        assertEquals(
            GitHubConnectionCoordinator.LaunchKind.CustomTab,
            GitHubActivityBrowserLauncher.customTabsLaunchKind(authTabSupported = false),
        )
    }

    @Test
    fun genericBrowserQueryIsSchemeOnlyAndContainsNoProbeHost() {
        assertEquals("http:", GitHubActivityBrowserLauncher.GENERIC_BROWSER_URI)
        assertFalse(GitHubActivityBrowserLauncher.GENERIC_BROWSER_URI.contains("//"))
    }

    @Test
    fun packageClaimingOnlyGitHubIsRejected() {
        assertNull(
            resolveExternalBrowser(
                genericHandlers = emptyList(),
                authorizationHandlers = listOf("example.github-link-handler"),
            ).first,
        )
    }

    @Test
    fun packageClaimingTheOldGitHubAndExampleHostPairButNotGenericBrowsingIsRejected() {
        val maliciousPackage = "example.two-host-link-handler"
        val (selection, queriedUrls) = resolveExternalBrowser(
            genericHandlers = listOf("org.example.unrelated-browser"),
            authorizationHandlers = listOf(maliciousPackage),
            unrelatedExampleHandlers = listOf(maliciousPackage),
        )

        assertNull(selection)
        assertEquals(
            listOf(GitHubActivityBrowserLauncher.GENERIC_BROWSER_URI, authorizationUrl),
            queriedUrls,
        )
    }

    @Test
    fun genuineGenericBrowserThatHandlesTheActualAuthorizationUriIsAccepted() {
        val browserPackage = "org.example.browser"
        val (selection, queriedUrls) = resolveExternalBrowser(
            genericHandlers = listOf("org.example.generic-only", browserPackage),
            authorizationHandlers = listOf("example.github-link-handler", browserPackage),
        )

        assertEquals(
            GitHubConnectionCoordinator.BrowserLaunch(
                GitHubConnectionCoordinator.LaunchKind.ExternalBrowser,
                browserPackage,
            ),
            selection,
        )
        assertEquals(
            listOf(GitHubActivityBrowserLauncher.GENERIC_BROWSER_URI, authorizationUrl),
            queriedUrls,
        )
    }

    @Test
    fun genericBrowserThatCannotHandleTheActualAuthorizationUriIsRejected() {
        assertNull(
            resolveExternalBrowser(
                genericHandlers = listOf("org.example.generic-browser"),
                authorizationHandlers = listOf("example.github-link-handler"),
            ).first,
        )
    }

    @Test
    fun selectedExternalBrowserPackageIsRetainedForThePinnedLaunch() {
        val browserPackage = "org.example.browser"
        val selection = resolveExternalBrowser(
            genericHandlers = listOf(browserPackage),
            authorizationHandlers = listOf(browserPackage),
        ).first

        assertEquals(GitHubConnectionCoordinator.LaunchKind.ExternalBrowser, selection?.kind)
        assertEquals(browserPackage, selection?.packageName)
    }

    private fun resolveExternalBrowser(
        genericHandlers: List<String>,
        authorizationHandlers: List<String>,
        unrelatedExampleHandlers: List<String> = emptyList(),
    ): Pair<GitHubConnectionCoordinator.BrowserLaunch?, List<String>> {
        val queriedUrls = mutableListOf<String>()
        val selection = GitHubActivityBrowserLauncher.resolveExternalBrowser(authorizationUrl) { url ->
            queriedUrls += url
            when (url) {
                GitHubActivityBrowserLauncher.GENERIC_BROWSER_URI -> genericHandlers
                authorizationUrl -> authorizationHandlers
                "https://www.example.com/" -> unrelatedExampleHandlers
                else -> emptyList()
            }
        }
        return selection to queriedUrls
    }
}
