package team.holder.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubCallbackActivityTest {
    @Test
    fun routesOnlyTheTwoExactCallbackPaths() {
        assertEquals(
            GitHubCallbackActivity.CallbackKind.OAuth,
            GitHubCallbackActivity.callbackKind("/android/oauth-callback"),
        )
        assertEquals(
            GitHubCallbackActivity.CallbackKind.Installation,
            GitHubCallbackActivity.callbackKind("/github/install-complete"),
        )
        assertNull(GitHubCallbackActivity.callbackKind("/unrelated"))
    }
}
