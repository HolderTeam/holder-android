package team.holder.android.git.github

import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private const val API_BASE = "https://api.github.com"
private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()

internal data class GitHubInstallation(
    val id: Long,
    val accountLogin: String,
    val accountType: String,
) {
    /** Hand-constructed rather than read from a `html_url` field on the installation
     * object -- unverified whether that field is even present on `GET /user/installations`'
     * response shape, whereas this exact URL shape (`https://github.com/settings/
     * installations/{id}`) is confirmed directly from this design phase's own screenshots.
     * Only ever used for a "User"-type installation (personal-account-only, see the plan's
     * "Personal accounts only" section) -- an org installation's settings URL takes a
     * different shape this app never needs, by design. */
    val settingsUrl: String get() = "https://github.com/settings/installations/$id"
}

/** Part of the public protocol surface (returned from [GitHubConnection.createRepository]),
 * unlike [GitHubInstallation] which stays purely internal. */
data class GitHubRepo(val ownerLogin: String, val name: String, val sshUrl: String)

/**
 * Raw GitHub REST calls plus the mapping from GitHub's actual wire-level responses to
 * [GitHubError] -- internal plumbing behind [GitHubConnection]'s protocol surface, not part
 * of the protocol itself (see the plan's "Holder GitHub protocol" section: a future Swift/
 * GTK/WinUI implementation reproduces the *behavior* this encodes, not this file). Every
 * function here takes an already-minted access token; none of them refresh one themselves
 * -- see the plan's Storage section for why that's [GitHubConnection]'s job, once per
 * public operation, not this file's.
 */
internal object GitHubApi {
    /** `GET /user/installations` -- every installation this token can see, personal or
     * organizational. [GitHubConnection] is the one that filters for a "User"-type entry
     * (personal-account-only, per the plan); this function returns the raw list. */
    fun listInstallations(client: OkHttpClient, accessToken: String): GitHubResult<List<GitHubInstallation>> =
        runCatching {
            val request = authedRequest(accessToken, "$API_BASE/user/installations").build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    mapGenericError<List<GitHubInstallation>>(response.code, body)
                } else {
                    val installations = JSONObject(body).getJSONArray("installations")
                    GitHubResult.Success(
                        List(installations.length()) { index ->
                            val installation = installations.getJSONObject(index)
                            val account = installation.getJSONObject("account")
                            GitHubInstallation(
                                id = installation.getLong("id"),
                                accountLogin = account.getString("login"),
                                accountType = account.getString("type"),
                            )
                        },
                    )
                }
            }
        }.getOrElse { networkFailure(it) }

    /** `POST /user/repos`, private, with [description] set to the project's own
     * human-readable name (the repo's own `name` is a `holder-<slug>-<project id>` name instead --
     * see [GitHubConnection.ensureProjectRepo]'s doc comment for why a Holder project's
     * freeform display name, e.g. containing spaces or apostrophes, is never used directly
     * as a GitHub repo name). On a `422` name collision, follows up with `GET
     * /repos/{login}/{name}` (using [login], already known from [listInstallations]'
     * personal-account entry) and returns *that* repo instead of failing -- the idempotency
     * guarantee [GitHubConnection.createRepository] promises. */
    fun createRepository(
        client: OkHttpClient,
        accessToken: String,
        login: String,
        name: String,
        description: String,
    ): GitHubResult<GitHubRepo> = runCatching {
        val payload = JSONObject().put("name", name).put("private", true).put("description", description)
        val request = authedRequest(accessToken, "$API_BASE/user/repos")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            when {
                response.isSuccessful -> GitHubResult.Success(JSONObject(body).toGitHubRepo())
                // GitHub's actual message for this case (worth reconfirming against a live
                // response before relying on it further): a top-level "message" of
                // "Repository creation failed." with an errors[].message of
                // "name already exists on this account".
                response.code == 422 && body.contains("name already exists") -> getRepository(client, accessToken, login, name)
                else -> mapGenericError(response.code, body)
            }
        }
    }.getOrElse { networkFailure(it) }

    /** `POST /repos/{owner}/{repo}/keys`. On a `403`/`404` -- the installation doesn't cover
     * this specific repo, see the plan's "Selective-repository installations" section --
     * maps to [GitHubError.RepositoryNotAccessible] rather than a bare failure. On a `422`
     * "key already in use" (this exact device already registered, e.g. a retried call),
     * treats it as success rather than an error -- the other half of
     * [GitHubConnection.registerDeployKey]'s idempotency guarantee. */
    fun addDeployKey(
        client: OkHttpClient,
        accessToken: String,
        owner: String,
        repo: String,
        title: String,
        publicKeyLine: String,
        installationSettingsUrl: String,
    ): GitHubResult<Unit> = runCatching {
        val payload = JSONObject().put("title", title).put("key", publicKeyLine).put("read_only", false)
        val request = authedRequest(accessToken, "$API_BASE/repos/$owner/$repo/keys")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            when {
                response.isSuccessful -> GitHubResult.Success(Unit)
                // GitHub's actual message for this case (same reconfirm-against-a-live-
                // response caveat as createRepository's collision check above): an
                // errors[].message of "key is already in use".
                response.code == 422 && body.contains("key is already in use") -> GitHubResult.Success(Unit)
                response.code == 403 || response.code == 404 ->
                    GitHubResult.Failure(GitHubError.RepositoryNotAccessible("$owner/$repo", installationSettingsUrl))
                else -> mapGenericError(response.code, body)
            }
        }
    }.getOrElse { networkFailure(it) }

    private fun getRepository(client: OkHttpClient, accessToken: String, owner: String, repo: String): GitHubResult<GitHubRepo> {
        val request = authedRequest(accessToken, "$API_BASE/repos/$owner/$repo").build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            return if (!response.isSuccessful) mapGenericError(response.code, body) else GitHubResult.Success(JSONObject(body).toGitHubRepo())
        }
    }

    private fun JSONObject.toGitHubRepo() = GitHubRepo(
        ownerLogin = getJSONObject("owner").getString("login"),
        name = getString("name"),
        sshUrl = getString("ssh_url"),
    )

    private fun authedRequest(accessToken: String, url: String) = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $accessToken")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")

    private fun <T> mapGenericError(httpStatus: Int, body: String): GitHubResult<T> = GitHubResult.Failure(
        when (httpStatus) {
            429 -> GitHubError.RateLimited(retryAfterSecondsFrom(body))
            else -> GitHubError.Unexpected(httpStatus, body)
        },
    )

    private fun retryAfterSecondsFrom(body: String): Int? =
        runCatching { JSONObject(body).optInt("retry_after", -1).takeIf { it >= 0 } }.getOrNull()

    private fun <T> networkFailure(cause: Throwable): GitHubResult<T> =
        if (cause is IOException) GitHubResult.Failure(GitHubError.NetworkError(cause)) else throw cause
}
