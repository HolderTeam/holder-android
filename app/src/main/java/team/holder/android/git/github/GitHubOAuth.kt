package team.holder.android.git.github

import android.net.Uri
import android.util.Log
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()

/** The result of a successful exchange/refresh: an access token to use immediately (cached by
 * [GitHubConnectionCoordinator], never persisted -- see its `accessTokenCache`) and the rotated
 * refresh token/cap pair to store in place of whatever was stored before. */
data class GitHubTokens(
    val accessToken: String,
    val expiresInSeconds: Int,
    val refreshToken: String,
    val refreshCap: String,
    val refreshTokenExpiresInSeconds: Int,
)

/** holder-github-service's own closed error envelope -- see that repo's `src/core/errors.ts`.
 * Deliberately mirrored one-for-one rather than collapsed at this layer; [GitHubConnectionCoordinator]
 * is the one place that decides how each of these maps differently depending on whether it came
 * from exchange or refresh. */
sealed interface RelayError {
    data object InvalidRequest : RelayError
    data class AuthorizationRequired(val reason: AuthorizationReason?) : RelayError
    data class RateLimited(val retryAfterSeconds: Int?) : RelayError
    data object OutcomeUnknown : RelayError
    data class Unexpected(val httpStatus: Int?, val body: String) : RelayError

    /** Not part of the relay's own envelope -- this is Android's own transport-layer
     * classification (see "Transport-failure ambiguity" in the plan). A failure that occurred
     * *after* the request may have been transmitted, where whether the relay/GitHub actually
     * received and processed it is genuinely unknown. */
    data object AmbiguousTransportFailure : RelayError

    /** Genuinely never reached the relay at all -- safe to treat as an ordinary, retryable
     * transport failure, since nothing was ever sent anywhere. */
    data class NetworkError(val cause: Throwable) : RelayError
}

sealed interface RelayResult {
    data class Success(val tokens: GitHubTokens) : RelayResult
    data class Failure(val error: RelayError) : RelayResult
}

/**
 * Calls holder-github-service's relay -- never GitHub's token endpoint directly. See
 * GITHUB_SETUP_SERVICE_PLAN.md for why: a public client (this app) can't safely hold the
 * `client_secret` a code/refresh exchange requires, so the relay holds it instead. Internal
 * plumbing behind [GitHubConnectionCoordinator]'s protocol surface -- a future Swift/GTK/WinUI
 * implementation reproduces this wire exchange with its own HTTP stack, not this file.
 */
internal object GitHubOAuth {
    fun buildAuthorizationUrl(state: String, codeChallenge: String): String =
        Uri.parse("https://github.com/login/oauth/authorize")
            .buildUpon()
            .appendQueryParameter("client_id", GitHubEnvironment.CLIENT_ID)
            .appendQueryParameter("redirect_uri", GitHubEnvironment.OAUTH_CALLBACK_URL)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
            .toString()

    suspend fun exchangeCode(client: OkHttpClient, code: String, codeVerifier: String): RelayResult =
        postRelay(client, "${GitHubEnvironment.RELAY_BASE_URL}/v1/github/exchange") {
            put("code", code)
            put("code_verifier", codeVerifier)
        }

    suspend fun refresh(client: OkHttpClient, refreshToken: String, refreshCap: String): RelayResult =
        postRelay(client, "${GitHubEnvironment.RELAY_BASE_URL}/v1/github/refresh") {
            put("refresh_token", refreshToken)
            put("refresh_cap", refreshCap)
        }

    private suspend fun postRelay(client: OkHttpClient, url: String, body: JSONObject.() -> Unit): RelayResult =
        withContext(Dispatchers.IO) { postRelayBlocking(client, url, body) }

    /** Genuinely blocking (OkHttp's synchronous `execute()`) -- callers must already be on an
     * I/O dispatcher; see [postRelay]'s own `withContext` wrapper, which every caller in this
     * file goes through. */
    private fun postRelayBlocking(client: OkHttpClient, url: String, body: JSONObject.() -> Unit): RelayResult {
        val payload = JSONObject().apply(body).toString()
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: UnknownHostException) {
            Log.w("GitHubOAuth", "postRelay: $url never reached (UnknownHostException)", e)
            return RelayResult.Failure(RelayError.NetworkError(e))
        } catch (e: ConnectException) {
            Log.w("GitHubOAuth", "postRelay: $url never reached (ConnectException)", e)
            return RelayResult.Failure(RelayError.NetworkError(e))
        } catch (e: IOException) {
            // Anything past connection establishment (a timeout awaiting the response, a
            // connection reset mid-read, ...) -- the request may already have been received
            // and processed. See "Transport-failure ambiguity": never assume this is safely
            // retryable.
            Log.w("GitHubOAuth", "postRelay: $url ambiguous transport failure (${e::class.simpleName}: ${e.message})", e)
            return RelayResult.Failure(RelayError.AmbiguousTransportFailure)
        }

        response.use { resp ->
            val responseBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w("GitHubOAuth", "postRelay: $url returned HTTP ${resp.code}: $responseBody")
                return RelayResult.Failure(mapErrorBody(resp.code, responseBody))
            }
            val json = try {
                JSONObject(responseBody)
            } catch (e: org.json.JSONException) {
                Log.w("GitHubOAuth", "postRelay: $url returned unparseable JSON: $responseBody", e)
                return RelayResult.Failure(RelayError.Unexpected(resp.code, responseBody))
            }
            if (!json.has("access_token")) {
                Log.w("GitHubOAuth", "postRelay: $url response missing access_token: $responseBody")
                return RelayResult.Failure(RelayError.Unexpected(resp.code, responseBody))
            }
            // A JSONException from any of these (a field missing/wrong-typed) must never
            // propagate uncaught -- confirmed live: this exact gap crashed the whole app
            // instead of surfacing as an ordinary Failure, since access_token's own presence
            // was checked above but the other four fields' presence never was.
            return try {
                val tokens = GitHubTokens(
                    accessToken = json.getString("access_token"),
                    expiresInSeconds = json.getInt("expires_in"),
                    refreshToken = json.getString("refresh_token"),
                    refreshCap = json.getString("refresh_cap"),
                    refreshTokenExpiresInSeconds = json.getInt("refresh_token_expires_in"),
                )
                Log.d("GitHubOAuth", "postRelay: $url succeeded")
                RelayResult.Success(tokens)
            } catch (e: org.json.JSONException) {
                Log.w("GitHubOAuth", "postRelay: $url response missing/malformed an expected field: $responseBody", e)
                RelayResult.Failure(RelayError.Unexpected(resp.code, responseBody))
            }
        }
    }

    private fun mapErrorBody(httpStatus: Int, body: String): RelayError {
        val json = try {
            JSONObject(body)
        } catch (e: org.json.JSONException) {
            return RelayError.Unexpected(httpStatus, body)
        }
        return when (json.optString("error")) {
            "invalid_request" -> RelayError.InvalidRequest
            "authorization_required" -> RelayError.AuthorizationRequired(
                when (json.optString("reason")) {
                    "verify_email" -> AuthorizationReason.VerifyEmail
                    else -> null
                },
            )
            "rate_limited" -> RelayError.RateLimited(json.optInt("retry_after_seconds", -1).takeIf { it >= 0 })
            "outcome_unknown" -> RelayError.OutcomeUnknown
            else -> RelayError.Unexpected(httpStatus, body)
        }
    }
}
