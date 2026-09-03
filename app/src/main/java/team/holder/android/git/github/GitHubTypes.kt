package team.holder.android.git.github

/** The bare, un-scoped public listing page -- never an `/installations/new` deep link with
 * a baked-in `target_id`. See GITHUB_INTEGRATION_ANDROID_PLAN.md's "What's already
 * verified" section for why a scoped deep link can permanently misroute with no account
 * picker shown at all. */
const val HOLDER_SYNC_APP_URL = "https://github.com/apps/holder-sync"

/** Returned by every [GitHubConnection] operation instead of thrown, so a caller -- a
 * Compose screen -- can branch on the failure directly rather than just log it. See the
 * plan's "Holder GitHub protocol" section for the full contract this is part of. */
sealed interface GitHubResult<out T> {
    data class Success<T>(val value: T) : GitHubResult<T>
    data class Failure(val error: GitHubError) : GitHubResult<Nothing>
}

/** Per-call error taxonomy -- see the plan's "Per-call errors" section for what each one
 * means and where it comes from. [AuthorizationRequired] and [InstallationRequired] mean
 * the same thing as [GitHubStatus]'s identically-named states: the token or installation
 * can go stale *between* a [GitHubConnection.status] check and the next call (e.g. the user
 * revokes access from github.com mid-session), so every operation surfaces these too, not
 * just an initial probe. */
sealed interface GitHubError {
    data object AuthorizationRequired : GitHubError
    data class InstallationRequired(val installUrl: String) : GitHubError

    /** The installation exists and is reachable, but doesn't cover this *particular*
     * repo -- e.g. the user chose "Only select repositories" and this repo isn't in the
     * list. See the plan's "Selective-repository installations" section for detection and
     * the friendly recovery route [installationSettingsUrl] drives. */
    data class RepositoryNotAccessible(val ownerSlashRepo: String, val installationSettingsUrl: String) : GitHubError
    data class RateLimited(val retryAfterSeconds: Int?) : GitHubError
    data class NetworkError(val cause: Throwable) : GitHubError

    /** The honest fallback rather than forcing every unanticipated GitHub response into one
     * of the named cases above. [httpStatus] is null when the failure never reached a
     * GitHub response at all (a malformed local request, for instance). */
    data class Unexpected(val httpStatus: Int?, val body: String) : GitHubError
}

/** [GitHubConnection]'s connection-state snapshot -- see the plan's "Connection state"
 * section. Observable as a [kotlinx.coroutines.flow.Flow] via
 * [GitHubConnection.statusFlow] for Settings to render, and returned by
 * [GitHubConnection.status] and [GitHubConnection.connect] for a one-off check. */
sealed interface GitHubStatus {
    /** No stored refresh token; [GitHubConnection.connect] has never succeeded, or
     * [GitHubConnection.disconnect] was called. */
    data object NotConnected : GitHubStatus

    /** A refresh token is stored but no longer works (expired past its rotation window, or
     * revoked by the user from GitHub's own "Authorized GitHub Apps" settings). Recovery is
     * re-running [GitHubConnection.connect]. */
    data object AuthorizationRequired : GitHubStatus

    /** The token is valid, but no installation on the user's personal account is reachable
     * (see "Personal accounts only" -- an org-only installation doesn't count). [installUrl]
     * is always [HOLDER_SYNC_APP_URL]. */
    data class InstallationRequired(val installUrl: String) : GitHubStatus

    /** Token valid and a personal-account installation exists. The only state
     * [GitHubConnection.createRepository]/[GitHubConnection.registerDeployKey]/
     * [GitHubConnection.ensureProjectRepo] are expected to succeed from.
     * [installationSettingsUrl] is this installation's own settings page (e.g.
     * `https://github.com/settings/installations/{id}`) -- usable to drive a "Manage GitHub
     * repository access" link in Settings even outside any error path. */
    data class Connected(val login: String, val installationSettingsUrl: String) : GitHubStatus
}
