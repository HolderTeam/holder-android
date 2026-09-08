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
                response.isSuccessful -> {
                    Log.d("GitHubApi", "createRepository: POST /user/repos succeeded for $name")
                    GitHubResult.Success(JSONObject(body).toGitHubRepo())
                }
                // GitHub's actual message for this case (worth reconfirming against a live
                // response before relying on it further): a top-level "message" of
                // "Repository creation failed." with an errors[].message of
                // "name already exists on this account".
                response.code == 422 && body.contains("name already exists") -> {
                    Log.d("GitHubApi", "createRepository: $name already exists, fetching it instead")
                    getRepository(client, accessToken, login, name)
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
     * [verifyKeyAlreadyOnThisRepo] actually checks which one it is via `GET .../keys` rather
     * than assuming the friendlier one. See [GitHubConnection.registerDeployKey]'s doc comment:
     * this is the other half of its idempotency guarantee, now verified instead of assumed. */
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
                response.isSuccessful -> {
                    Log.d("GitHubApi", "addDeployKey: POST /repos/$owner/$repo/keys succeeded")
                    GitHubResult.Success(Unit)
                }
                // GitHub's actual message for this case (same reconfirm-against-a-live-
                // response caveat as createRepository's collision check above): an
                // errors[].message of "key is already in use".
                response.code == 422 && body.contains("key is already in use") -> {
                    if (verifyKeyAlreadyOnThisRepo(client, accessToken, owner, repo, publicKeyLine)) {
                        Log.d("GitHubApi", "addDeployKey: verified key is already registered for $owner/$repo, treating as success")
                        GitHubResult.Success(Unit)
                    } else {
                        // This key is a deploy key on a DIFFERENT repository -- GitHub will
                        // never let it become a deploy key here too, no retry fixes this. The
                        // caller needs a fresh, actually-unused key (see GitIdentity
                        // .aliasForProject -- each project's own alias exists specifically so
                        // this case shouldn't occur in the first place; hitting it for real
                        // means something upstream reused an alias that already has a repo).
                        Log.w(
                            "GitHubApi",
                            "addDeployKey: key already in use, but NOT on $owner/$repo -- it's a deploy key " +
                                "on a different repository and can never be added here",
                        )
                        GitHubResult.Failure(GitHubError.Unexpected(422, body))
                    }
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
    private fun verifyKeyAlreadyOnThisRepo(
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
