package team.holder.android.git.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class GitHubBrowserSelectionTest {
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
    fun hostSpecificGitHubHandlerIsNotAcceptedAsAnExternalBrowser() {
        assertNull(
            GitHubActivityBrowserLauncher.selectExternalBrowserPackage(
                githubHandlers = listOf("example.github-link-handler"),
                genericWebHandlers = emptySet(),
            ),
        )
    }

    @Test
    fun selectsOnlyAPackageThatHandlesBothGitHubAndGenericHttps() {
        assertEquals(
            "org.example.browser",
            GitHubActivityBrowserLauncher.selectExternalBrowserPackage(
                githubHandlers = listOf("example.github-link-handler", "org.example.browser"),
                genericWebHandlers = setOf("org.example.browser"),
            ),
        )
    }
}
