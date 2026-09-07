package team.holder.android.git.github

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.browser.auth.AuthTabIntent
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import java.util.UUID
import team.holder.android.ui.openUrlExternally

/**
 * Implements [GitHubConnectionCoordinator.GitHubBrowserLauncher] for a real Activity --
 * everything Activity/AuthTabIntent-specific that the coordinator itself must stay agnostic
 * of. [authTabLauncher] must be registered unconditionally, as an Activity field initializer
 * (see `MainActivity`) -- never created conditionally inside a Composable, matching the "at
 * most one unresolved AuthTabIntent launch per registration, ever" policy the coordinator's
 * own design relies on. [setOutstandingAttemptId] persists onto the Activity's own SavedState
 * (see `MainActivity.onSaveInstanceState`) -- the same lifecycle boundary
 * `ActivityResultRegistry` itself uses to restore its own outstanding-launch bookkeeping.
 */
internal class GitHubActivityBrowserLauncher(
    private val authTabLauncher: ActivityResultLauncher<Intent>,
    private val setOutstandingAttemptId: (UUID?) -> Unit,
) : GitHubConnectionCoordinator.GitHubBrowserLauncher {

    override fun resolveLaunchKind(context: Context): GitHubConnectionCoordinator.LaunchKind? {
        // Resolve a Custom Tabs provider FIRST, then check *that specific* provider's Auth Tab
        // support -- capability check and eventual launch must use the same resolved package.
        val customTabsPackage = CustomTabsClient.getPackageName(context, null, false)
        if (customTabsPackage != null) {
            return if (CustomTabsClient.isAuthTabSupported(context, customTabsPackage)) {
                GitHubConnectionCoordinator.LaunchKind.AuthTab
            } else {
                GitHubConnectionCoordinator.LaunchKind.CustomTab
            }
        }
        val resolvesOrdinaryBrowser = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com"))
            .resolveActivity(context.packageManager) != null
        return if (resolvesOrdinaryBrowser) GitHubConnectionCoordinator.LaunchKind.ExternalBrowser else null
    }

    override fun launch(context: Context, kind: GitHubConnectionCoordinator.LaunchKind, uri: Uri, attemptId: UUID) {
        when (kind) {
            GitHubConnectionCoordinator.LaunchKind.AuthTab -> {
                // Persist BEFORE actually launching -- a result (even a fast one) must never
                // be able to arrive before this is recorded.
                setOutstandingAttemptId(attemptId)
                val redirect = Uri.parse(GitHubEnvironment.OAUTH_CALLBACK_URL)
                AuthTabIntent.Builder().build().launch(authTabLauncher, uri, redirect.host.orEmpty(), redirect.path.orEmpty())
            }
            GitHubConnectionCoordinator.LaunchKind.CustomTab -> CustomTabsIntent.Builder().build().launchUrl(context, uri)
            GitHubConnectionCoordinator.LaunchKind.ExternalBrowser -> openUrlExternally(context, uri.toString())
        }
    }
}
