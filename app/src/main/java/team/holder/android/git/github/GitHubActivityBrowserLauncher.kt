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
 * own design relies on. [setOutstandingAttemptId] persists only a non-secret mirror onto the
 * Activity's SavedState (see `MainActivity.onSaveInstanceState`); the coordinator owns the
 * authoritative attempt marker and restores this mirror solely to discard stale OS results.
 */
internal class GitHubActivityBrowserLauncher(
    private val authTabLauncher: ActivityResultLauncher<Intent>,
    private val setOutstandingAttemptId: (UUID?) -> Unit,
) : GitHubConnectionCoordinator.GitHubBrowserLauncher {

    override fun resolveBrowser(
        context: Context,
        authorizationUrl: String,
    ): GitHubConnectionCoordinator.BrowserLaunch? {
        // Resolve a Custom Tabs provider FIRST, then check *that specific* provider's Auth Tab
        // support -- capability check and eventual launch must use the same resolved package.
        val customTabsPackage = CustomTabsClient.getPackageName(context, null, false)
        if (customTabsPackage != null) {
            val authTabSupported = CustomTabsClient.isAuthTabSupported(context, customTabsPackage)
            val launchKind = customTabsLaunchKind(authTabSupported)
            Log.d(
                "GitHubConnection",
                "resolveBrowser: provider=$customTabsPackage isAuthTabSupported=$authTabSupported " +
                    "requestedLaunch=$launchKind",
            )
            return GitHubConnectionCoordinator.BrowserLaunch(launchKind, customTabsPackage)
        }
        // Intent.resolveActivity() is the wrong check here: it returns null whenever there's
        // no single unambiguous default, even if one or more real handlers exist (confirmed
        // live -- this exact call returned null on a real emulator with Chrome genuinely
        // installed, simply because nothing had been chosen as the default browser yet).
        // Android's browser-role contract is an unconstrained browsable HTTP handler. Querying
        // a scheme-only URI means a host-specific App Link filter cannot qualify. The selected
        // package must separately resolve the exact authorization URI, and that same package is
        // retained in BrowserLaunch so the eventual OAuth intent can be pinned to it.
        return resolveExternalBrowser(authorizationUrl) { url -> handlerPackages(context, url) }
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
                        .addCategory(Intent.CATEGORY_BROWSABLE)
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
        /** Never launch a second Auth Tab through the same result registration. Its old result
         * could otherwise consume the newer attempt's marker. */
        fun customTabsLaunchKind(authTabSupported: Boolean): GitHubConnectionCoordinator.LaunchKind =
            if (authTabSupported) {
                GitHubConnectionCoordinator.LaunchKind.AuthTab
            } else {
                GitHubConnectionCoordinator.LaunchKind.CustomTab
            }

        /** A scheme-only URI exercises Android's generic browser intent semantics. It has no
         * arbitrary probe host for a link-handling app to claim. This works back to API 1. */
        internal const val GENERIC_BROWSER_URI = "http:"

        /** Deterministic seam around the two PackageManager queries. */
        fun resolveExternalBrowser(
            authorizationUrl: String,
            handlerPackages: (String) -> List<String>,
        ): GitHubConnectionCoordinator.BrowserLaunch? {
            val genericBrowserHandlers = handlerPackages(GENERIC_BROWSER_URI).toSet()
            if (genericBrowserHandlers.isEmpty()) return null
            val authorizationHandlers = handlerPackages(authorizationUrl)
            return selectExternalBrowserPackage(
                authorizationHandlers = authorizationHandlers,
                genericBrowserHandlers = genericBrowserHandlers,
            )?.let {
                GitHubConnectionCoordinator.BrowserLaunch(
                    GitHubConnectionCoordinator.LaunchKind.ExternalBrowser,
                    it,
                )
            }
        }

        /** Select only a generic browser that also resolves the exact authorization URI. */
        fun selectExternalBrowserPackage(
            authorizationHandlers: List<String>,
            genericBrowserHandlers: Set<String>,
        ): String? = authorizationHandlers.firstOrNull { it in genericBrowserHandlers }
    }
}
