package team.holder.android.git.github

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.browser.auth.AuthTabIntent
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import java.util.UUID

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

    override fun resolveBrowser(context: Context): GitHubConnectionCoordinator.BrowserLaunch? {
        // Resolve a Custom Tabs provider FIRST, then check *that specific* provider's Auth Tab
        // support -- capability check and eventual launch must use the same resolved package.
        val customTabsPackage = CustomTabsClient.getPackageName(context, null, false)
        if (customTabsPackage != null) {
            val authTabSupported = CustomTabsClient.isAuthTabSupported(context, customTabsPackage)
            Log.d(
                "GitHubConnection",
                "resolveBrowser: provider=$customTabsPackage isAuthTabSupported=$authTabSupported -> " +
                    if (authTabSupported) "AuthTab" else "CustomTab",
            )
            return if (authTabSupported) {
                GitHubConnectionCoordinator.BrowserLaunch(
                    GitHubConnectionCoordinator.LaunchKind.AuthTab,
                    customTabsPackage,
                )
            } else {
                GitHubConnectionCoordinator.BrowserLaunch(
                    GitHubConnectionCoordinator.LaunchKind.CustomTab,
                    customTabsPackage,
                )
            }
        }
        // Intent.resolveActivity() is the wrong check here: it returns null whenever there's
        // no single unambiguous default, even if one or more real handlers exist (confirmed
        // live -- this exact call returned null on a real emulator with Chrome genuinely
        // installed, simply because nothing had been chosen as the default browser yet).
        // A handler for GitHub alone is not an ordinary browser: a host-specific app can claim
        // that link and receive the authorization request. Require the same package to handle
        // an unrelated, generic browsable HTTPS URL too, then pin the actual launch to it.
        val githubHandlers = handlerPackages(context, "https://github.com")
        val genericWebHandlers = handlerPackages(context, "https://www.example.com/").toSet()
        val externalBrowserPackage = selectExternalBrowserPackage(githubHandlers, genericWebHandlers)
        return externalBrowserPackage?.let {
            GitHubConnectionCoordinator.BrowserLaunch(
                GitHubConnectionCoordinator.LaunchKind.ExternalBrowser,
                it,
            )
        }
    }

    override fun launch(
        context: Context,
        browser: GitHubConnectionCoordinator.BrowserLaunch,
        uri: Uri,
        attemptId: UUID,
    ) {
        when (browser.kind) {
            GitHubConnectionCoordinator.LaunchKind.AuthTab -> {
                // Persist BEFORE actually launching -- a result (even a fast one) must never
                // be able to arrive before this is recorded.
                setOutstandingAttemptId(attemptId)
                val redirect = Uri.parse(GitHubEnvironment.OAUTH_CALLBACK_URL)
                AuthTabIntent.Builder().build().also { authTabIntent ->
                    authTabIntent.intent.setPackage(browser.packageName)
                    authTabIntent.launch(authTabLauncher, uri, redirect.host.orEmpty(), redirect.path.orEmpty())
                }
            }
            GitHubConnectionCoordinator.LaunchKind.CustomTab -> {
                // GitHubConnectionCoordinator deliberately passes its own applicationContext
                // here (its operations can outlive any single screen), but starting an
                // Activity from a non-Activity Context requires this flag or it throws --
                // confirmed live: this exact call crashed on a real device without it.
                val customTabsIntent = CustomTabsIntent.Builder().build()
                customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                customTabsIntent.intent.setPackage(browser.packageName)
                customTabsIntent.launchUrl(context, uri)
            }
            GitHubConnectionCoordinator.LaunchKind.ExternalBrowser ->
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, uri)
                        .setPackage(browser.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
        }
    }

    private fun handlerPackages(context: Context, url: String): List<String> =
        context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE),
            PackageManager.MATCH_DEFAULT_ONLY,
        ).mapNotNull { it.activityInfo?.packageName }

    internal companion object {
        /** Pure seam for testing the fallback against a host-specific URL-handler. */
        fun selectExternalBrowserPackage(
            githubHandlers: List<String>,
            genericWebHandlers: Set<String>,
        ): String? = githubHandlers.firstOrNull { it in genericWebHandlers }
    }
}
