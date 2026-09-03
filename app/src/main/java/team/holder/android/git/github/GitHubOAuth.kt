package team.holder.android.git.github

import kotlinx.coroutines.delay
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Holder Sync's GitHub App Client ID -- non-secret, the equivalent of any OAuth app's
 * public client identifier. See GITHUB_INTEGRATION_ANDROID_PLAN.md's "What's already
 * verified against the real production app" for the App ID/permissions this belongs to. */
private const val CLIENT_ID = "Iv23lih033MzJBJQKU6n"
private const val DEVICE_CODE_URL = "https://github.com/login/device/code"
private const val ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token"

/** One in-flight Device Flow authorization: the code to show the user, the URL to open, and
 * everything [GitHubOAuth.pollForToken] needs to keep asking GitHub whether it's been
 * approved yet. */
data class DeviceAuthorization(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int,
)

/** The result of a successful Device Flow authorization or refresh: an access token to use
 * immediately (never persisted -- see the plan's Storage section) and the refresh token to
 * store in its place. GitHub rotates the refresh token on every use, so [refreshToken] here
 * is always a *new* value the caller must overwrite whatever was stored before with. */
data class GitHubTokens(val accessToken: String, val refreshToken: String, val refreshTokenExpiresInSeconds: Int)

/** [errorCode] is GitHub's own machine-readable `error` field (`authorization_pending`,
 * `expired_token`, `access_denied`, `bad_refresh_token`, ...) when the failure came from a
 * GitHub response, so callers -- see [GitHubConnection] -- can branch on it (e.g.
 * `bad_refresh_token` maps to [GitHubStatus.AuthorizationRequired]) without parsing
 * [message] themselves. Null for failures that never reached a GitHub response at all. */
class GitHubAuthException(message: String, val errorCode: String? = null) : Exception(message)

/**
 * GitHub App Device Flow: no client secret, no redirect URI, no backend -- see
 * GITHUB_INTEGRATION_ANDROID_PLAN.md's "What's already verified" section, confirmed with
 * live `curl` testing before any of this was written. Internal plumbing behind
 * [GitHubConnection]'s protocol surface, not part of the protocol itself -- a future Swift/
 * GTK/WinUI implementation reproduces this wire exchange with its own HTTP stack, not this
 * file (see the plan's "Holder GitHub protocol" section).
 */
internal object GitHubOAuth {
    /** `POST /login/device/code` -- the first step, giving the user a code to enter at
     * [DeviceAuthorization.verificationUri]. */
    fun requestDeviceCode(client: OkHttpClient): DeviceAuthorization {
        val body = FormBody.Builder().add("client_id", CLIENT_ID).build()
        val request = Request.Builder()
            .url(DEVICE_CODE_URL)
            .header("Accept", "application/json")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw GitHubAuthException("Failed to start GitHub sign-in: HTTP ${response.code}")
            }
            val json = JSONObject(responseBody)
            return DeviceAuthorization(
                deviceCode = json.getString("device_code"),
                userCode = json.getString("user_code"),
                verificationUri = json.getString("verification_uri"),
                expiresInSeconds = json.optInt("expires_in", 900),
                intervalSeconds = json.optInt("interval", 5),
            )
        }
    }

    /** Polls `POST /login/oauth/access_token` until the user approves [authorization] in
     * their browser, honoring `authorization_pending`/`slow_down` the same way this
     * feature's own live-testing scripts did, or throws [GitHubAuthException] on
     * `access_denied`/`expired_token`/anything else GitHub reports. Suspends between
     * attempts rather than blocking a thread, so this is safe to call directly from a
     * coroutine driving UI state. */
    suspend fun pollForToken(client: OkHttpClient, authorization: DeviceAuthorization): GitHubTokens {
        var intervalSeconds = authorization.intervalSeconds
        val deadline = System.currentTimeMillis() + authorization.expiresInSeconds * 1000L
        while (true) {
            if (System.currentTimeMillis() > deadline) {
                throw GitHubAuthException("The GitHub sign-in code expired -- try again", "expired_token")
            }
            delay(intervalSeconds * 1000L)
            val json = postToken(
                client,
                FormBody.Builder()
                    .add("client_id", CLIENT_ID)
                    .add("device_code", authorization.deviceCode)
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                    .build(),
            )
            val error = json.optString("error", "")
            when {
                json.has("access_token") -> return json.toTokens()
                error == "authorization_pending" -> continue
                error == "slow_down" -> {
                    intervalSeconds = json.optInt("interval", intervalSeconds + 5)
                    continue
                }
                error.isNotEmpty() -> throw GitHubAuthException(json.optString("error_description", error), error)
                else -> throw GitHubAuthException("Unexpected response from GitHub during sign-in")
            }
        }
    }

    /** `grant_type=refresh_token` -- mints a fresh access token from a stored refresh token.
     * GitHub always returns a *new* refresh token too (rotation); the caller must overwrite
     * whatever was stored before immediately, see the plan's "Refresh token rotation" note.
     * Called once per [GitHubConnection] public operation invocation, never once per raw
     * HTTP request underneath it -- see the plan's Storage section for why. A
     * `bad_refresh_token`/`expired` error here is the caller's signal to report
     * [GitHubStatus.AuthorizationRequired], not a generic failure. */
    fun refreshAccessToken(client: OkHttpClient, refreshToken: String): GitHubTokens {
        val json = postToken(
            client,
            FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .build(),
        )
        val error = json.optString("error", "")
        if (error.isNotEmpty()) throw GitHubAuthException(json.optString("error_description", error), error)
        return json.toTokens()
    }

    private fun postToken(client: OkHttpClient, body: FormBody): JSONObject {
        val request = Request.Builder()
            .url(ACCESS_TOKEN_URL)
            .header("Accept", "application/json")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            return JSONObject(response.body?.string().orEmpty())
        }
    }

    /** Requires `refresh_token`/`refresh_token_expires_in` to be present, i.e. assumes
     * Holder Sync's user-token-expiration setting is enabled (see the plan's "Token
     * expiration" open item) -- fails loudly with a clear diagnostic rather than silently
     * degrading to treating a non-expiring access token as its own refresh token, a mode
     * the plan explicitly left undesigned (Security notes) pending that setting being
     * confirmed. If this throws in practice, the fix is the App setting, not this code. */
    private fun JSONObject.toTokens(): GitHubTokens {
        if (!has("refresh_token")) {
            throw GitHubAuthException(
                "GitHub did not return a refresh token -- Holder Sync's user token expiration " +
                    "setting may not be enabled (App settings -> Optional features)",
            )
        }
        return GitHubTokens(
            accessToken = getString("access_token"),
            refreshToken = getString("refresh_token"),
            refreshTokenExpiresInSeconds = getInt("refresh_token_expires_in"),
        )
    }
}
