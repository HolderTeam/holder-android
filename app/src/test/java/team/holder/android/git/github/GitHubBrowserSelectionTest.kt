package team.holder.android.git.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubBrowserSelectionTest {
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
