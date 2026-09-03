package team.holder.android.ui

import team.holder.android.git.github.GitHubError

/** A short, human-readable summary of [error] -- shared by every screen that surfaces a
 * `GitHubResult.Failure` directly (New Project, `RecoverProjectScreen`) rather than each
 * writing its own copy for the same handful of cases. Deliberately generic; a caller that
 * wants a specific call-to-action (e.g. "tap to grant access", using
 * [team.holder.android.git.github.GitHubError.RepositoryNotAccessible]'s own
 * `installationSettingsUrl`) still branches on [error]'s subtype itself for that -- this is
 * just the fallback description. */
fun githubErrorMessage(error: GitHubError): String = when (error) {
    GitHubError.AuthorizationRequired -> "GitHub needs to be reconnected"
    is GitHubError.InstallationRequired -> "Holder Sync isn't installed on your GitHub account yet"
    is GitHubError.RepositoryNotAccessible -> "GitHub needs one more permission for this repository"
    is GitHubError.RateLimited -> "GitHub asked us to slow down -- try again shortly"
    is GitHubError.NetworkError -> error.cause.message ?: "Network error talking to GitHub"
    is GitHubError.Unexpected -> "Unexpected response from GitHub (HTTP ${error.httpStatus ?: "?"})"
}
