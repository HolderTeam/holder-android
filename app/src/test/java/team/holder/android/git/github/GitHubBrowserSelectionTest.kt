package team.holder.android.git.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
