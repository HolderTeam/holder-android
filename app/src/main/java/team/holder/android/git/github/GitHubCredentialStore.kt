package team.holder.android.git.github

import android.content.Context
import team.holder.android.keyring.AndroidKeyringStore

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
    fun getRefreshToken(context: Context): String?
    fun getRefreshCap(context: Context): String?
    fun store(context: Context, refreshToken: String, refreshCap: String)
    fun clear(context: Context)
}

internal object RealGitHubCredentialStore : GitHubCredentialStore {
    private const val REFRESH_TOKEN_SECRET_KEY = "github_refresh_token"
    private const val REFRESH_CAP_SECRET_KEY = "github_refresh_cap"

    override fun getRefreshToken(context: Context): String? = AndroidKeyringStore.getLocalSecret(context, REFRESH_TOKEN_SECRET_KEY)

    override fun getRefreshCap(context: Context): String? = AndroidKeyringStore.getLocalSecret(context, REFRESH_CAP_SECRET_KEY)

    override fun store(context: Context, refreshToken: String, refreshCap: String) {
        AndroidKeyringStore.storeLocalSecret(context, REFRESH_TOKEN_SECRET_KEY, refreshToken)
        AndroidKeyringStore.storeLocalSecret(context, REFRESH_CAP_SECRET_KEY, refreshCap)
    }

    override fun clear(context: Context) {
        AndroidKeyringStore.removeLocalSecret(context, REFRESH_TOKEN_SECRET_KEY)
        AndroidKeyringStore.removeLocalSecret(context, REFRESH_CAP_SECRET_KEY)
    }
}
