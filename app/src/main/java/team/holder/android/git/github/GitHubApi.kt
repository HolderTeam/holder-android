package team.holder.android.git.github

import android.util.Log
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private const val API_BASE = "https://api.github.com"
private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()

internal data class GitHubInstallation(
    val id: Long,
    val accountId: Long,
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

/** The authenticated account's numeric ID is the stable binding. Its login is deliberately
 * retained only for display and GitHub URL construction after that binding has succeeded. */
internal data class GitHubAuthenticatedUser(val id: Long, val login: String)

/** Part of the public protocol surface (returned from [GitHubConnection.createRepository]),
 * unlike [GitHubInstallation] which stays purely internal. */
data class GitHubRepo(val ownerLogin: String, val name: String, val sshUrl: String)

internal sealed interface CreateRepositoryResponse {
    data class Created(val repository: GitHubRepo) : CreateRepositoryResponse
    data class NameAlreadyExists(val responseBody: String) : CreateRepositoryResponse
}

internal sealed interface AddDeployKeyResponse {
    data object Added : AddDeployKeyResponse
    data class KeyAlreadyInUse(val responseBody: String) : AddDeployKeyResponse
}

/**
 * Raw GitHub REST calls plus the mapping from GitHub's actual wire-level responses to
 * [GitHubError] -- internal plumbing behind [GitHubConnection]'s protocol surface, not part
 * of the protocol itself (see the plan's "Holder GitHub protocol" section: a future Swift/
 * GTK/WinUI implementation reproduces the *behavior* this encodes, not this file). Every
 * function here takes an already-minted access token; none of them refresh one themselves.
 * [GitHubConnection] obtains authority and a token separately for every HTTP request.
 */
internal object GitHubApi {
    /** `GET /user` -- authenticates the bearer to a stable numeric account identity before
     * any installation-derived personal-account decision is made. */
    fun authenticatedUser(client: OkHttpClient, accessToken: String): GitHubResult<GitHubAuthenticatedUser> =
        runCatching {
            val request = authedRequest(accessToken, "$API_BASE/user").build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    mapGenericError<GitHubAuthenticatedUser>(response.code, body)
                } else {
                    val user = JSONObject(body)
                    GitHubResult.Success(GitHubAuthenticatedUser(user.getLong("id"), user.getString("login")))
                }
            }
        }.getOrElse { networkFailure(it) }

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
                                accountId = account.getLong("id"),
                                accountLogin = account.getString("login"),
                                accountType = account.getString("type"),
                            )
                        },
                    )
                }
            }
        }.getOrElse { networkFailure(it) }

    /** Installation handles are mutable, so the sole personal-installation selection rule
     * compares the installation's account ID with `GET /user`'s authenticated numeric ID. */
    internal fun personalInstallationFor(
        user: GitHubAuthenticatedUser,
        installations: List<GitHubInstallation>,
    ): GitHubInstallation? = installations.firstOrNull {
        it.accountType == "User" && it.accountId == user.id
    }

    /** `POST /user/repos`, with [description] set to the project's own human-readable name
     * (the repo's own `name` is a `holder-<slug>-<project id>` name instead -- see
     * [GitHubConnection.ensureProjectRepo]'s doc comment for why a Holder project's freeform
     * display name, e.g. containing spaces or apostrophes, is never used directly as a GitHub
     * repo name). Note [description] -- and the repo name's own slug -- are visible on GitHub
     * regardless of [private]: an encrypted project's card *contents* stay protected, but its
     * name does not, which is exactly what the New Project dialog's Encrypted+Public warning
     * calls out. A `422` name collision is returned as [CreateRepositoryResponse.NameAlreadyExists]
     * so [GitHubConnection] can release the control-plane gate before independently gating the
     * verification `GET /repos/{login}/{name}`. */
    fun createRepository(
        client: OkHttpClient,
        accessToken: String,
        name: String,
        description: String,
        private: Boolean = true,
    ): GitHubResult<CreateRepositoryResponse> = runCatching {
        val payload = JSONObject().put("name", name).put("private", private).put("description", description)
        val request = authedRequest(accessToken, "$API_BASE/user/repos")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            when {
                response.isSuccessful -> {
                    Log.d("GitHubApi", "createRepository: POST /user/repos succeeded for $name")
                    GitHubResult.Success(CreateRepositoryResponse.Created(JSONObject(body).toGitHubRepo()))
                }
                // GitHub's actual message for this case (worth reconfirming against a live
                // response before relying on it further): a top-level "message" of
                // "Repository creation failed." with an errors[].message of
                // "name already exists on this account".
                response.code == 422 && body.contains("name already exists") -> {
                    Log.d("GitHubApi", "createRepository: $name already exists; caller must verify it")
                    GitHubResult.Success(CreateRepositoryResponse.NameAlreadyExists(body))
                }
                else -> {
                    Log.w("GitHubApi", "createRepository: POST /user/repos returned HTTP ${response.code}: $body")
                    mapGenericError(response.code, body)
                }
            }
        }
    }.getOrElse { networkFailure(it) }

    /** `POST /repos/{owner}/{repo}/keys`. On a `403`/`404` -- the installation doesn't cover
     * this specific repo, see the plan's "Selective-repository installations" section --
     * maps to [GitHubError.RepositoryNotAccessible] rather than a bare failure. On a `422`
     * "key already in use", GitHub's message doesn't distinguish "already a deploy key on
     * *this* repo" (a genuine retried call -- success) from "already a deploy key on some
     * *other* repo" (this key can never work here at all -- a real failure, not an idempotency
     * case) -- these are two very different outcomes with the identical response shape, so
     * [AddDeployKeyResponse.KeyAlreadyInUse] returns that conditional outcome to
     * [GitHubConnection], which releases the POST's gate before independently gating the
     * verification `GET .../keys`. */
    fun addDeployKey(
        client: OkHttpClient,
        accessToken: String,
        owner: String,
        repo: String,
        title: String,
        publicKeyLine: String,
        installationSettingsUrl: String,
    ): GitHubResult<AddDeployKeyResponse> = runCatching {
        val payload = JSONObject().put("title", title).put("key", publicKeyLine).put("read_only", false)
        val request = authedRequest(accessToken, "$API_BASE/repos/$owner/$repo/keys")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            when {
                response.isSuccessful -> {
                    Log.d("GitHubApi", "addDeployKey: POST /repos/$owner/$repo/keys succeeded")
                    GitHubResult.Success(AddDeployKeyResponse.Added)
                }
                // GitHub's actual message for this case (same reconfirm-against-a-live-
                // response caveat as createRepository's collision check above): an
                // errors[].message of "key is already in use".
                response.code == 422 && body.contains("key is already in use") -> {
                    GitHubResult.Success(AddDeployKeyResponse.KeyAlreadyInUse(body))
                }
                response.code == 403 || response.code == 404 -> {
                    Log.w("GitHubApi", "addDeployKey: $owner/$repo not accessible (HTTP ${response.code})")
                    GitHubResult.Failure(GitHubError.RepositoryNotAccessible("$owner/$repo", installationSettingsUrl))
                }
                else -> {
                    Log.w("GitHubApi", "addDeployKey: POST /repos/$owner/$repo/keys returned HTTP ${response.code}: $body")
                    mapGenericError(response.code, body)
                }
            }
        }
    }.getOrElse { networkFailure(it) }

    /** `GET /repos/{owner}/{repo}/keys` -- the actual check behind addDeployKey's `422`
     * verification. GitHub's list response gives each key's `key` field as just
     * `"<algorithm> <base64>"`, no comment, so compare against [publicKeyLine]'s own first two
     * space-separated fields, never the raw strings (our own line always carries a trailing
     * comment the list response never does). Fails closed: any error listing the repo's keys
     * (network, auth, whatever) means "not verified," never "assume it's fine." */
    fun verifyKeyAlreadyOnThisRepo(
        client: OkHttpClient,
        accessToken: String,
        owner: String,
        repo: String,
        publicKeyLine: String,
    ): Boolean {
        val ourKeyValue = publicKeyLine.trim().split(Regex("\\s+")).take(2).joinToString(" ")
        val request = authedRequest(accessToken, "$API_BASE/repos/$owner/$repo/keys").build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching false
                val keys = JSONArray(response.body?.string().orEmpty())
                (0 until keys.length()).any { index ->
                    val theirKeyValue = keys.getJSONObject(index).getString("key").trim().split(Regex("\\s+")).take(2).joinToString(" ")
                    theirKeyValue == ourKeyValue
                }
            }
        }.getOrElse {
            Log.w("GitHubApi", "verifyKeyAlreadyOnThisRepo: couldn't list $owner/$repo's keys to verify", it)
            false
        }
    }

    fun getRepository(client: OkHttpClient, accessToken: String, owner: String, repo: String): GitHubResult<GitHubRepo> =
        runCatching {
            val request = authedRequest(accessToken, "$API_BASE/repos/$owner/$repo").build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    mapGenericError(response.code, body)
                } else {
                    GitHubResult.Success(JSONObject(body).toGitHubRepo())
                }
            }
        }.getOrElse { networkFailure(it) }

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
