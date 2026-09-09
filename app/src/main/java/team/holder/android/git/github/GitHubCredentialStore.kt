package team.holder.android.git.github

import android.content.Context
import org.json.JSONObject
import team.holder.android.keyring.AndroidKeyringStore

/** The one durable representation of a GitHub refresh credential.  The access token remains
 * process-only; the expiry is wall-clock advisory data that survives restart. */
internal data class StoredGitHubCredential(
    val refreshToken: String,
    val refreshCap: String,
    val refreshTokenExpiresAtMillis: Long,
)

/** Keeps the secure-store payload versioned so a future format change has one explicit
 * migration boundary rather than several independently-versioned secret keys. */
internal object GitHubCredentialRecord {
    private const val VERSION = 1
    private const val VERSION_KEY = "version"
    private const val REFRESH_TOKEN_KEY = "refresh_token"
    private const val REFRESH_CAP_KEY = "refresh_cap"
    private const val REFRESH_TOKEN_EXPIRES_AT_KEY = "refresh_token_expires_at"

    fun encode(credential: StoredGitHubCredential): String = JSONObject()
        .put(VERSION_KEY, VERSION)
        .put(REFRESH_TOKEN_KEY, credential.refreshToken)
        .put(REFRESH_CAP_KEY, credential.refreshCap)
        .put(REFRESH_TOKEN_EXPIRES_AT_KEY, credential.refreshTokenExpiresAtMillis)
        .toString()

    /** A corrupt or old-format record is not a credential.  The coordinator consequently fails
     * closed into the normal reconnect path rather than assembling a partial representation. */
    fun decode(record: String): StoredGitHubCredential? {
        return try {
            val json = JSONObject(record)
            if (json.getInt(VERSION_KEY) != VERSION) return null
            val refreshToken = json.getString(REFRESH_TOKEN_KEY)
            val refreshCap = json.getString(REFRESH_CAP_KEY)
            val expiresAt = json.getLong(REFRESH_TOKEN_EXPIRES_AT_KEY)
            if (refreshToken.isEmpty() || refreshCap.isEmpty() || expiresAt <= 0L) return null
            StoredGitHubCredential(refreshToken, refreshCap, expiresAt)
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * The one seam in [GitHubConnectionCoordinator] between its concurrency logic and actual
 * Android storage -- exists so the coordinator's real guarantees (join-or-create, single-shot
 * callback consumption, credential epoch, single-flight refresh) can be exercised by a plain
 * JVM unit test with an in-memory fake, matching this codebase's existing testing convention
 * (no Robolectric, no mocking framework anywhere in `src/test`) rather than requiring a real
 * Android `Context`/Keystore for every test. [GitHubConnectionCoordinator.credentialStore] is
 * swapped to a fake only by tests; production always uses [RealGitHubCredentialStore].
 */
internal interface GitHubCredentialStore {
    fun get(context: Context): StoredGitHubCredential?
    /** Returns only after the whole versioned credential record is durable. */
    fun store(context: Context, credential: StoredGitHubCredential)
    fun clear(context: Context)
}

internal object RealGitHubCredentialStore : GitHubCredentialStore {
    private const val CREDENTIAL_SECRET_KEY = "github_refresh_credential_v1"
    // Clean up the pre-record representation in the same atomic preference transaction.  It
    // was never a valid complete credential by itself and must not linger after replacement.
    private val LEGACY_SECRET_KEYS = setOf("github_refresh_token", "github_refresh_cap")

    override fun get(context: Context): StoredGitHubCredential? =
        AndroidKeyringStore.getLocalSecret(context, CREDENTIAL_SECRET_KEY)?.let(GitHubCredentialRecord::decode)

    override fun store(context: Context, credential: StoredGitHubCredential) {
        check(
            AndroidKeyringStore.replaceLocalSecrets(
                context,
                replacements = mapOf(CREDENTIAL_SECRET_KEY to GitHubCredentialRecord.encode(credential)),
                removedKeys = LEGACY_SECRET_KEYS,
            ),
        ) { "Could not durably store the GitHub credential" }
    }

    override fun clear(context: Context) {
        check(
            AndroidKeyringStore.replaceLocalSecrets(
                context,
                replacements = emptyMap(),
                removedKeys = LEGACY_SECRET_KEYS + CREDENTIAL_SECRET_KEY,
            ),
        ) { "Could not durably clear the GitHub credential" }
    }
}
