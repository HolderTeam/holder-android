package team.holder.android.git.github

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
     * GitHub response at all (a malformed local request, for instance). Also where the
     * relay's own `outcome_unknown` and an ambiguous transport failure during *refresh*
     * land -- see GitHubConnectionCoordinator's refresh gate for why those must not
     * force-clear the stored credential the way a definite [AuthorizationRequired] does. */
    data class Unexpected(val httpStatus: Int?, val body: String) : GitHubError

    /** `RESULT_VERIFICATION_FAILED`/`RESULT_VERIFICATION_TIMED_OUT`/`RESULT_UNKNOWN_CODE`
     * from `AuthTabIntent` -- an OS/browser-level failure of the Auth Tab verification
     * guarantee itself, not a GitHub authorization outcome. Deliberately never folded into
     * [GitHubStatus.AuthorizationRequired]'s reasons -- see GitHubConnectionCoordinator's
     * `connect()` sequence, path (b). */
    data object AuthorizationVerificationFailed : GitHubError

    /** No Custom Tabs provider and no ordinary browser resolved at all -- `connect()` never
     * even reaches a `pendingOAuth` in this case. */
    data object BrowserUnavailable : GitHubError
}

/** Why [GitHubStatus.AuthorizationRequired] resolved that way -- absent (`null`) for most
 * causes (a definite rejection, an ambiguous/ `outcome_unknown` exchange outcome, a local
 * timeout); present only when the relay itself distinguishes a more specific reason. */
enum class AuthorizationReason { VerifyEmail }

/** [GitHubConnection]'s connection-state snapshot -- see the plan's "Connection state"
 * section. Observable as a [kotlinx.coroutines.flow.Flow] via
 * [GitHubConnection.statusFlow] for Settings to render, and returned by
 * [GitHubConnection.status] and [GitHubConnection.connect] for a one-off check. */
sealed interface GitHubStatus {
    /** No stored refresh token; [GitHubConnection.connect] has never succeeded, or
     * [GitHubConnection.disconnect] was called. */
    data object NotConnected : GitHubStatus

    /** A refresh token is stored but no longer works (expired past its rotation window, or
     * revoked by the user from GitHub's own "Authorized GitHub Apps" settings), OR an
     * authorization attempt was made but needs a fresh ceremony (a definite rejection, an
     * ambiguous/`outcome_unknown` exchange outcome, or a local timeout) -- distinct from
     * [NotConnected]'s "chose not to," this means "tried, needs to try again." Recovery
     * either way is re-running [GitHubConnection.connect]. [reason] is null for all of the
     * above; present only when the relay distinguishes a more specific cause (currently just
     * an unverified email). Note: [GitHubError.AuthorizationRequired] is a *different* case
     * on a *different* type -- it stays the bare case it already is; the two are never the
     * same thing and must not be conflated. */
    data class AuthorizationRequired(val reason: AuthorizationReason? = null) : GitHubStatus

    /** The token is valid, but no installation on the user's personal account is reachable
     * (see "Personal accounts only" -- an org-only installation doesn't count). [installUrl]
     * is always [GitHubEnvironment.APP_URL]. */
    data class InstallationRequired(val installUrl: String) : GitHubStatus

    /** Token valid and a personal-account installation exists. The only state
     * [GitHubConnection.createRepository]/[GitHubConnection.registerDeployKey]/
     * [GitHubConnection.ensureProjectRepo] are expected to succeed from.
     * [installationSettingsUrl] is this installation's own settings page (e.g.
     * `https://github.com/settings/installations/{id}`) -- usable to drive a "Manage GitHub
     * repository access" link in Settings even outside any error path. */
    data class Connected(val login: String, val installationSettingsUrl: String) : GitHubStatus
}
