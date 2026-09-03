package team.holder.android.git.github

import android.content.Context
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import team.holder.android.HolderProject
import team.holder.android.HolderSettings
import team.holder.android.git.GitIdentity
import team.holder.android.keyring.AndroidKeyringStore

private const val PROVIDER_ID = "github"
private const val REFRESH_TOKEN_SECRET_KEY = "github_refresh_token"

/**
 * The Android reference implementation of the Holder GitHub protocol -- see
 * GITHUB_INTEGRATION_ANDROID_PLAN.md's "Holder GitHub protocol" section for the full
 * behavioral contract (states, errors, idempotency guarantees) this implements. UI-agnostic,
 * same as [team.holder.android.resource.drive.GoogleDriveConnection]/
 * [team.holder.android.resource.s3.S3Connection] -- Compose only observes/drives this,
 * never talks to [GitHubOAuth]/[GitHubApi] directly.
 */
object GitHubConnection {
    /** [status] as a [Flow] for Settings/Compose to observe, re-checked whenever the
     * connected-login config changes (i.e. after [connect]/[disconnect]). Each emission
     * does a fresh network check (refresh + installations), same as [status] itself --
     * this doesn't try to cache connection state across time on its own, since GitHub-side
     * state (revoked access, changed installation scope) can change independently of
     * anything Holder does. */
    fun statusFlow(context: Context): Flow<GitHubStatus> {
        val appContext = context.applicationContext
        return HolderSettings.connectedProviderConfig(appContext, PROVIDER_ID).map { statusBlocking(appContext) }
    }

    /** Runs Device Flow end-to-end: requests a code, hands it to [onCodeReady] (opening a
     * Custom Tab etc. is the caller's job -- kept out of this UI-agnostic layer), polls
     * until approved, stores the resulting refresh token, then immediately checks
     * installation reachability and returns the resulting status. A caller never needs a
     * separate "did it work" step after this returns. */
    suspend fun connect(context: Context, onCodeReady: suspend (DeviceAuthorization) -> Unit): GitHubStatus =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val client = OkHttpClient()
            val authorization = GitHubOAuth.requestDeviceCode(client)
            onCodeReady(authorization)
            val tokens = GitHubOAuth.pollForToken(client, authorization)
            storeRefreshToken(appContext, tokens.refreshToken)
            resolveStatus(client, tokens.accessToken)
        }

    /** Clears the stored refresh token and connected-login display state. Always succeeds
     * locally; does not attempt to revoke anything on GitHub's side -- revocation is the
     * user's own action from GitHub's "Authorized GitHub Apps" settings, the same boundary
     * [team.holder.android.resource.drive.GoogleDriveAuth] already draws around Google's
     * side of Drive access. */
    suspend fun disconnect(context: Context) {
        val appContext = context.applicationContext
        AndroidKeyringStore.removeLocalSecret(appContext, REFRESH_TOKEN_SECRET_KEY)
        HolderSettings.setConnectedProviderConfig(appContext, PROVIDER_ID, null)
    }

    /** A cheap-ish probe (refresh, then installations) for whenever a screen needs to know
     * "is GitHub actually usable right now" without performing a repo/key operation --
     * Settings' row, and the New Project / recovery-import entry points deciding whether to
     * even attempt the paved-road path before showing it as an option. Throws on a genuine
     * transport failure (no internet, GitHub unreachable) rather than folding that into
     * [GitHubStatus] -- callers already have an existing runCatching/error-message pattern
     * for that (see [team.holder.android.ui.screens.GitSyncScreen]'s `runAction`), and a
     * transient network blip isn't the same thing as "you need to reconnect." */
    suspend fun status(context: Context): GitHubStatus = withContext(Dispatchers.IO) { statusBlocking(context.applicationContext) }

    /** `POST /user/repos`, private, named from [project]. Idempotent in the sense that
     * matters here: a name collision against a repo Holder itself already created is
     * treated as success (returns the existing repo) rather than an error -- safe to retry
     * after a partial failure without producing a duplicate. See
     * [ensureProjectRepo]'s doc comment for the naming scheme. */
    suspend fun createRepository(context: Context, project: HolderProject): GitHubResult<GitHubRepo> =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val client = OkHttpClient()
            withPersonalInstallation(appContext, client).flatMap { (accessToken, installation) ->
                GitHubApi.createRepository(client, accessToken, installation.accountLogin, repoNameFor(project), project.name)
            }
        }

    /** `POST /repos/{owner}/{repo}/keys` with this device's [GitIdentity] public key.
     * Idempotent: GitHub's "key already in use" response (this exact device having already
     * been registered -- a real case, e.g. retrying after `pushGit` failed for an unrelated
     * reason) is treated as success, not an error. */
    suspend fun registerDeployKey(context: Context, owner: String, repo: String): GitHubResult<Unit> =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val client = OkHttpClient()
            withPersonalInstallation(appContext, client).flatMap { (accessToken, installation) ->
                addDeployKey(client, accessToken, installation, owner, repo)
            }
        }

    /** The actual paved-road compound operation both New Project wiring and the recovery
     * "register this device" entry point call: [createRepository] then
     * [registerDeployKey], returning the resulting `ssh_url` for `HolderNative
     * .updateProjectGitRemote`. Safe to call repeatedly for the same project -- every step
     * underneath is idempotent (see both operations' doc comments), so a retried call after
     * a transient [GitHubError.NetworkError] or [GitHubError.RateLimited] never produces a
     * duplicate repo or deploy key; there's no separate "already has a repo" check needed
     * here beyond that, since [createRepository]'s own name-collision handling already
     * finds and reuses the same repo a previous attempt created.
     *
     * The repo's GitHub `name` is `holder-<slug>-<project id>` ([repoNameFor]) -- Holder
     * project names are freeform (spaces, apostrophes, emoji) and GitHub repo names are
     * not, so the slug is a best-effort, lossy readability aid only; uniqueness always
     * comes from the trailing `project.projectId` (already a unique UUID, see
     * `holder-core`'s `uuid_v4()`), never the slug, so an empty or heavily-mangled slug
     * (an all-emoji project name, say) is harmless -- it just falls back to no slug at all.
     * The full, unmangled project name still ends up on GitHub either way, as the repo's
     * `description`.
     *
     * Refreshes once for the whole compound call, not once per REST call underneath -- see
     * the plan's Storage section for why that granularity matters (it's the difference
     * between rotating the stored refresh token once vs. twice for one thing the user
     * perceives as a single action). */
    suspend fun ensureProjectRepo(context: Context, project: HolderProject): GitHubResult<String> =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val client = OkHttpClient()
            withPersonalInstallation(appContext, client).flatMap { (accessToken, installation) ->
                GitHubApi.createRepository(client, accessToken, installation.accountLogin, repoNameFor(project), project.name)
                    .flatMap { repo -> addDeployKey(client, accessToken, installation, repo.ownerLogin, repo.name).map { repo.sshUrl } }
            }
        }

    /** `holder-<slug>-<project id>`, e.g. "holder-taylor-swifts-hair-3fa8...". GitHub allows
     * repo names up to 100 characters, so a 40-character slug cap plus the 36-character
     * UUID leaves comfortable room either way. See [ensureProjectRepo]'s doc comment for why
     * the slug itself carries no correctness weight -- [slugify] only needs to be
     * best-effort, not exact or reversible. Internal rather than private so
     * GitHubConnectionTest can exercise it directly, same as GitIdentity's
     * ensureKnownHosts. */
    internal fun repoNameFor(project: HolderProject): String {
        val slug = slugify(project.name)
        return if (slug.isEmpty()) "holder-${project.projectId}" else "holder-$slug-${project.projectId}"
    }

    /** Lossy on purpose: GitHub repo names allow only `[A-Za-z0-9_.-]`, so unicode, emoji,
     * and most punctuation are simply dropped rather than transliterated -- there's no
     * requirement this be reversible or even meaningfully similar to [name], just readable
     * enough to be a nicer sight than a bare UUID on GitHub's own repo list. */
    internal fun slugify(name: String): String =
        name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(40)
            .trim('-')

    private fun addDeployKey(
        client: OkHttpClient,
        accessToken: String,
        installation: GitHubInstallation,
        owner: String,
        repo: String,
    ): GitHubResult<Unit> = GitHubApi.addDeployKey(
        client, accessToken, owner, repo,
        title = "Holder — $repo",
        publicKeyLine = GitIdentity.sshPublicKeyLine(),
        installationSettingsUrl = installation.settingsUrl,
    )

    private fun statusBlocking(appContext: Context): GitHubStatus {
        if (AndroidKeyringStore.getLocalSecret(appContext, REFRESH_TOKEN_SECRET_KEY) == null) return GitHubStatus.NotConnected
        val client = OkHttpClient()
        return when (val result = withPersonalInstallation(appContext, client)) {
            is GitHubResult.Success -> {
                val (_, installation) = result.value
                GitHubStatus.Connected(installation.accountLogin, installation.settingsUrl)
            }
            is GitHubResult.Failure -> when (val error = result.error) {
                GitHubError.AuthorizationRequired -> GitHubStatus.AuthorizationRequired
                is GitHubError.InstallationRequired -> GitHubStatus.InstallationRequired(error.installUrl)
                is GitHubError.NetworkError -> throw error.cause
                else -> throw GitHubAuthException("Unexpected GitHub response while checking status: $error")
            }
        }
    }

    /** Shared by [connect] (which already has a freshly minted access token from the
     * device-flow grant itself, so refreshing again immediately would rotate the stored
     * refresh token a second time for no reason) and [statusBlocking] (via
     * [withPersonalInstallation]'s own call to this). */
    private fun resolveStatus(client: OkHttpClient, accessToken: String): GitHubStatus =
        when (val result = GitHubApi.listInstallations(client, accessToken)) {
            is GitHubResult.Success -> {
                val personal = result.value.firstOrNull { it.accountType == "User" }
                if (personal != null) {
                    GitHubStatus.Connected(personal.accountLogin, personal.settingsUrl)
                } else {
                    GitHubStatus.InstallationRequired(HOLDER_SYNC_APP_URL)
                }
            }
            is GitHubResult.Failure -> when (val error = result.error) {
                is GitHubError.NetworkError -> throw error.cause
                // An installations lookup failing right after a successful authorization is
                // unexpected, but the safest fallback is still "finish installing" rather
                // than silently reporting Connected.
                else -> GitHubStatus.InstallationRequired(HOLDER_SYNC_APP_URL)
            }
        }

    /** Refreshes once, fetches installations once, and finds the personal ("User"-type)
     * one -- the "one refresh + one installations check per public operation" granularity
     * the plan's Storage section specifies. Every public operation above calls this exactly
     * once at its start. Overwrites the stored refresh token with GitHub's rotated one
     * immediately on a successful refresh, before doing anything else with it -- see the
     * plan's "Refresh token rotation" note for why the ordering matters. */
    private fun withPersonalInstallation(appContext: Context, client: OkHttpClient): GitHubResult<Pair<String, GitHubInstallation>> {
        val refreshToken = AndroidKeyringStore.getLocalSecret(appContext, REFRESH_TOKEN_SECRET_KEY)
            ?: return GitHubResult.Failure(GitHubError.AuthorizationRequired)
        val tokens = try {
            GitHubOAuth.refreshAccessToken(client, refreshToken)
        } catch (e: GitHubAuthException) {
            return GitHubResult.Failure(GitHubError.AuthorizationRequired)
        } catch (e: IOException) {
            return GitHubResult.Failure(GitHubError.NetworkError(e))
        }
        storeRefreshToken(appContext, tokens.refreshToken)
        val personal = when (val installationsResult = GitHubApi.listInstallations(client, tokens.accessToken)) {
            is GitHubResult.Failure -> return GitHubResult.Failure(installationsResult.error)
            is GitHubResult.Success -> installationsResult.value.firstOrNull { it.accountType == "User" }
                ?: return GitHubResult.Failure(GitHubError.InstallationRequired(HOLDER_SYNC_APP_URL))
        }
        return GitHubResult.Success(tokens.accessToken to personal)
    }

    private fun storeRefreshToken(appContext: Context, refreshToken: String) {
        AndroidKeyringStore.storeLocalSecret(appContext, REFRESH_TOKEN_SECRET_KEY, refreshToken)
    }

    private inline fun <T, R> GitHubResult<T>.flatMap(transform: (T) -> GitHubResult<R>): GitHubResult<R> = when (this) {
        is GitHubResult.Success -> transform(value)
        is GitHubResult.Failure -> GitHubResult.Failure(error)
    }

    private inline fun <T, R> GitHubResult<T>.map(transform: (T) -> R): GitHubResult<R> = flatMap { GitHubResult.Success(transform(it)) }
}
