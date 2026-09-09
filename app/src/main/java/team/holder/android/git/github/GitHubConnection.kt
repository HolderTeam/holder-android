package team.holder.android.git.github

import android.content.Context
import android.net.Uri
import androidx.browser.auth.AuthTabIntent
import java.util.UUID
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import team.holder.android.HolderProject
import team.holder.android.git.GitIdentity

/** A single shared client for direct calls to api.github.com -- separate from the
 * relay-calling client in [GitHubOAuth], a different concern hitting a different host. */
private val githubApiHttpClient = OkHttpClient()

/**
 * The Android reference implementation of the Holder GitHub protocol -- see
 * GITHUB_SETUP_SERVICE_PLAN.md's "Holder GitHub protocol" section for the full behavioral
 * contract (states, errors, idempotency guarantees) this implements. UI-agnostic, same as
 * [team.holder.android.resource.drive.GoogleDriveConnection]/
 * [team.holder.android.resource.s3.S3Connection] -- Compose only observes/drives this, never
 * talks to [GitHubOAuth]/[GitHubApi]/[GitHubConnectionCoordinator] directly.
 *
 * A thin facade only -- every piece of actual concurrency/state-machine logic (single-flight
 * connect, credential epoch, refresh gating, callback delivery) lives in
 * [GitHubConnectionCoordinator]; see that file for the real behavior this delegates to.
 */
object GitHubConnection {
    /** Observable connection state -- see [GitHubConnectionCoordinator.statusFlow]'s own doc
     * comment for why this is the coordinator's own published state, not a fresh network check
     * per collection. */
    val statusFlow: StateFlow<GitHubStatus> get() = GitHubConnectionCoordinator.statusFlow

    /** Runs the OAuth ceremony end-to-end via [browserLauncher] (Auth Tab, falling back to
     * Custom Tabs/an ordinary browser) and, on success, checks installation reachability --
     * see [GitHubConnectionCoordinator.connect] for the full state machine this drives. A
     * second concurrent call while one is already in flight joins it rather than starting an
     * independent ceremony. */
    suspend fun connect(context: Context, browserLauncher: GitHubConnectionCoordinator.GitHubBrowserLauncher): GitHubResult<GitHubStatus> =
        GitHubConnectionCoordinator.connect(context, browserLauncher)

    /** Abandons the currently pending Custom Tab/external-browser sign-in, if any. The
     * browser cannot be closed by Holder, but its later App Link callback is rejected because
     * this clears the exact pending transaction before returning. */
    suspend fun cancelPendingBrowserAuthorization(): Boolean =
        GitHubConnectionCoordinator.cancelPendingBrowserAuthorization()

    /** Clears the stored credential and connected-login display state. Always succeeds
     * locally; does not attempt to revoke anything on GitHub's side -- revocation is the
     * user's own action from GitHub's "Authorized GitHub Apps" settings, the same boundary
     * [team.holder.android.resource.drive.GoogleDriveAuth] already draws around Google's side
     * of Drive access. Waits for any in-flight direct GitHub REST call to finish first (see
     * [GitHubConnectionCoordinator]'s control-plane barrier). */
    suspend fun disconnect(context: Context) = GitHubConnectionCoordinator.disconnect(context)

    /** A cheap-ish probe for whenever a screen needs to know "is GitHub actually usable right
     * now" without performing a repo/key operation. */
    suspend fun status(context: Context): GitHubStatus = GitHubConnectionCoordinator.status(context)

    /** Forward a matching App Link `VIEW` intent here -- see `MainActivity.onNewIntent`. Safe
     * to call for any URI; silently ignored if it isn't this app's OAuth callback endpoint or
     * doesn't match a currently-pending attempt. */
    suspend fun handleOAuthCallbackUri(uri: Uri) = GitHubConnectionCoordinator.handleOAuthCallbackUri(uri)

    /** Forward an Auth Tab result here from the `ActivityResultLauncher`'s own callback.
     * [consumeOutstandingAttemptId] must read-and-clear the launching Activity's own
     * SavedState-persisted attempt id -- see [GitHubConnectionCoordinator.GitHubBrowserLauncher]'s
     * doc comment for why this specific mechanism, not DataStore. */
    fun handleAuthTabResult(result: AuthTabIntent.AuthResult, consumeOutstandingAttemptId: () -> UUID?) =
        GitHubConnectionCoordinator.handleAuthTabResult(result, consumeOutstandingAttemptId)

    /** Generates a fresh `install_state` and sends the user to the installation flow -- call
     * before opening `.../installations/new?state=<returned value>`. */
    suspend fun beginInstallationReturn(): String = GitHubConnectionCoordinator.beginInstallationReturn()

    /** Forward a Setup URL return (`/github/install-complete`) here from `MainActivity
     * .onNewIntent` -- pass the `state` query parameter, exactly as received (never
     * `installation_id`, which GitHub's own docs call spoofable). Returns null if the state is
     * missing, doesn't match, or has expired -- no automatic API call is made in that case. */
    suspend fun handleInstallationReturn(context: Context, returnedState: String?): GitHubStatus? =
        GitHubConnectionCoordinator.handleInstallationReturn(context, returnedState)

    /** `POST /user/repos`, private, named from [project]. Idempotent in the sense that
     * matters here: a name collision against a repo Holder itself already created is treated
     * as success (returns the existing repo) rather than an error -- safe to retry after a
     * partial failure without producing a duplicate. See [ensureProjectRepo]'s doc comment for
     * the naming scheme. */
    suspend fun createRepository(context: Context, project: HolderProject, private: Boolean = true): GitHubResult<GitHubRepo> =
        withPersonalInstallation(context) { installation ->
            GitHubConnectionCoordinator.withAccessToken(context) { accessToken ->
                GitHubApi.createRepository(githubApiHttpClient, accessToken, installation.accountLogin, repoNameFor(project), project.name, private)
            }
        }

    /** `POST /repos/{owner}/{repo}/keys` with [projectId]'s own [GitIdentity] public key --
     * each project gets its own device keypair (see [GitIdentity.aliasForProject]), since
     * GitHub rejects the same public key being a deploy key on more than one repository.
     * Idempotent: GitHub's "key already in use" response is only ever treated as success once
     * actually verified as *this* key already being on *this* repo (see [GitHubApi.addDeployKey]) --
     * never assumed just because the response shape matches. */
    suspend fun registerDeployKey(context: Context, projectId: String, owner: String, repo: String): GitHubResult<Unit> =
        withPersonalInstallation(context) { installation ->
            GitHubConnectionCoordinator.withAccessToken(context) { accessToken -> addDeployKey(accessToken, installation, projectId, owner, repo) }
        }

    /** The actual paved-road compound operation: [createRepository] then [registerDeployKey],
     * returning the resulting `ssh_url` for `HolderNative.updateProjectGitRemote`. Safe to
     * call repeatedly for the same project -- every step underneath is idempotent, so a
     * retried call after a transient failure never produces a duplicate repo or deploy key.
     *
     * The repo's GitHub `name` is `holder-<slug>-<project id>` ([repoNameFor]) -- Holder
     * project names are freeform and GitHub repo names are not, so the slug is a best-effort,
     * lossy readability aid only; uniqueness always comes from the trailing `project.projectId`. */
    suspend fun ensureProjectRepo(context: Context, project: HolderProject, private: Boolean = true): GitHubResult<String> =
        withPersonalInstallation(context) { installation ->
            when (
                val createResult = GitHubConnectionCoordinator.withAccessToken(context) { accessToken ->
                    GitHubApi.createRepository(
                        githubApiHttpClient,
                        accessToken,
                        installation.accountLogin,
                        repoNameFor(project),
                        project.name,
                        private,
                    )
                }
            ) {
                is GitHubResult.Failure -> createResult
                is GitHubResult.Success -> GitHubConnectionCoordinator.withAccessToken(context) { accessToken ->
                    addDeployKey(accessToken, installation, project.projectId, createResult.value.ownerLogin, createResult.value.name)
                        .map { createResult.value.sshUrl }
                }
            }
        }

    internal fun repoNameFor(project: HolderProject): String {
        val slug = slugify(project.name)
        return if (slug.isEmpty()) "holder-${project.projectId}" else "holder-$slug-${project.projectId}"
    }

    internal fun slugify(name: String): String =
        name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(40)
            .trim('-')

    private fun addDeployKey(
        accessToken: String,
        installation: GitHubInstallation,
        projectId: String,
        owner: String,
        repo: String,
    ): GitHubResult<Unit> = GitHubApi.addDeployKey(
        githubApiHttpClient, accessToken, owner, repo,
        title = "Holder — $repo",
        publicKeyLine = GitIdentity.sshPublicKeyLine(alias = GitIdentity.aliasForProject(projectId)),
        installationSettingsUrl = installation.settingsUrl,
    )

    /** The installation lookup is one independently-gated direct GitHub request. [block] then
     * gates every follow-up request separately, so a compound setup operation never holds the
     * control-plane mutex across multiple REST calls. */
    private suspend fun <T> withPersonalInstallation(
        context: Context,
        block: suspend (installation: GitHubInstallation) -> GitHubResult<T>,
    ): GitHubResult<T> {
        val userResult = GitHubConnectionCoordinator.withAccessToken(context) { accessToken ->
            GitHubApi.authenticatedUser(githubApiHttpClient, accessToken)
        }
        val user = when (userResult) {
            is GitHubResult.Failure -> return GitHubResult.Failure(userResult.error)
            is GitHubResult.Success -> userResult.value
        }
        val lookupResult = GitHubConnectionCoordinator.withAccessToken(context) { accessToken ->
            GitHubApi.listInstallations(githubApiHttpClient, accessToken)
        }
        val installations = when (lookupResult) {
            is GitHubResult.Failure -> return GitHubResult.Failure(lookupResult.error)
            is GitHubResult.Success -> lookupResult.value
        }
        val installation = GitHubApi.personalInstallationFor(user, installations)
            ?: return GitHubResult.Failure(GitHubError.InstallationRequired(GitHubEnvironment.APP_URL))
        // The mutable installation login is never an identity binding. Use the authenticated
        // `/user` login only after `personalInstallationFor` matched numeric account IDs.
        return block(installation.copy(accountLogin = user.login))
    }

    private inline fun <T, R> GitHubResult<T>.flatMap(transform: (T) -> GitHubResult<R>): GitHubResult<R> = when (this) {
        is GitHubResult.Success -> transform(value)
        is GitHubResult.Failure -> GitHubResult.Failure(error)
    }

    private inline fun <T, R> GitHubResult<T>.map(transform: (T) -> R): GitHubResult<R> = flatMap { GitHubResult.Success(transform(it)) }
}
