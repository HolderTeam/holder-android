package team.holder.android.git.github

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import team.holder.android.keyring.AndroidKeyringStore

/** The complete refresh authority. Access tokens remain process-only. */
internal data class StoredGitHubCredential(
    val refreshToken: String,
    val refreshCap: String,
    val refreshTokenExpiresAtMillis: Long,
)

/**
 * The only states in the durable GitHub credential slot. [Refreshing] is deliberately durable:
 * once a one-shot refresh credential has been admitted to the network it must stay quarantined
 * across process death unless that exact attempt durably rotates, restores, or clears it.
 */
internal sealed interface DurableGitHubCredentialState {
    data object Absent : DurableGitHubCredentialState
    data class Ready(val credential: StoredGitHubCredential) : DurableGitHubCredentialState
    data class Refreshing(val credential: StoredGitHubCredential) : DurableGitHubCredentialState
}

/** One versioned encrypted payload containing both lifecycle state and complete authority. */
internal object GitHubCredentialStateRecord {
    private const val VERSION = 1
    private const val VERSION_KEY = "version"
    private const val STATE_KEY = "state"
    private const val READY = "ready"
    private const val REFRESHING = "refreshing"
    private const val REFRESH_TOKEN_KEY = "refresh_token"
    private const val REFRESH_CAP_KEY = "refresh_cap"
    private const val REFRESH_TOKEN_EXPIRES_AT_KEY = "refresh_token_expires_at"

    fun encode(state: DurableGitHubCredentialState): String {
        require(state !is DurableGitHubCredentialState.Absent) { "Absent is represented by an empty DataStore slot" }
        val credential = when (state) {
            is DurableGitHubCredentialState.Ready -> state.credential
            is DurableGitHubCredentialState.Refreshing -> state.credential
            DurableGitHubCredentialState.Absent -> error("checked above")
        }
        val stateName = when (state) {
            is DurableGitHubCredentialState.Ready -> READY
            is DurableGitHubCredentialState.Refreshing -> REFRESHING
            DurableGitHubCredentialState.Absent -> error("checked above")
        }
        return JSONObject()
            .put(VERSION_KEY, VERSION)
            .put(STATE_KEY, stateName)
            .put(REFRESH_TOKEN_KEY, credential.refreshToken)
            .put(REFRESH_CAP_KEY, credential.refreshCap)
            .put(REFRESH_TOKEN_EXPIRES_AT_KEY, credential.refreshTokenExpiresAtMillis)
            .toString()
    }

    /** Corrupt, unknown, or incomplete plaintext grants no credential authority. */
    fun decode(record: String): DurableGitHubCredentialState {
        return try {
            val json = JSONObject(record)
            if (json.getInt(VERSION_KEY) != VERSION) return DurableGitHubCredentialState.Absent
            val credential = StoredGitHubCredential(
                refreshToken = json.getString(REFRESH_TOKEN_KEY),
                refreshCap = json.getString(REFRESH_CAP_KEY),
                refreshTokenExpiresAtMillis = json.getLong(REFRESH_TOKEN_EXPIRES_AT_KEY),
            )
            if (
                credential.refreshToken.isEmpty() ||
                credential.refreshCap.isEmpty() ||
                credential.refreshTokenExpiresAtMillis <= 0L
            ) {
                return DurableGitHubCredentialState.Absent
            }
            when (json.getString(STATE_KEY)) {
                READY -> DurableGitHubCredentialState.Ready(credential)
                REFRESHING -> DurableGitHubCredentialState.Refreshing(credential)
                else -> DurableGitHubCredentialState.Absent
            }
        } catch (_: Exception) {
            DurableGitHubCredentialState.Absent
        }
    }
}

/**
 * Persistence protocol seam. [compareAndSet] is one durable transaction: success changes the
 * old state to the new state, a false result changes nothing because the expected authority no
 * longer owns the slot, and an exception leaves the old DataStore state authoritative.
 */
internal interface GitHubCredentialStore {
    suspend fun read(context: Context): DurableGitHubCredentialState
    suspend fun compareAndSet(
        context: Context,
        expected: DurableGitHubCredentialState,
        replacement: DurableGitHubCredentialState,
    ): Boolean
}

private const val GITHUB_CREDENTIAL_DATASTORE_NAME = "github_credential"
private val CREDENTIAL_STATE_KEY = stringPreferencesKey("encrypted_credential_state")
private val Context.githubCredentialDataStore: DataStore<Preferences> by preferencesDataStore(
    name = GITHUB_CREDENTIAL_DATASTORE_NAME,
)

internal object RealGitHubCredentialStore : GitHubCredentialStore {
    override suspend fun read(context: Context): DurableGitHubCredentialState =
        decode(context.applicationContext.githubCredentialDataStore.data.first()[CREDENTIAL_STATE_KEY])

    override suspend fun compareAndSet(
        context: Context,
        expected: DurableGitHubCredentialState,
        replacement: DurableGitHubCredentialState,
    ): Boolean {
        // Keystore work happens before DataStore opens its update transaction. If encryption
        // fails, the durable old value is untouched.
        val encryptedReplacement = when (replacement) {
            DurableGitHubCredentialState.Absent -> null
            else -> AndroidKeyringStore.encryptLocalPayload(GitHubCredentialStateRecord.encode(replacement))
        }
        var replaced = false
        context.applicationContext.githubCredentialDataStore.edit { preferences ->
            if (decode(preferences[CREDENTIAL_STATE_KEY]) == expected) {
                if (encryptedReplacement == null) {
                    preferences.remove(CREDENTIAL_STATE_KEY)
                } else {
                    preferences[CREDENTIAL_STATE_KEY] = encryptedReplacement
                }
                replaced = true
            }
        }
        return replaced
    }

    private fun decode(encrypted: String?): DurableGitHubCredentialState {
        if (encrypted == null) return DurableGitHubCredentialState.Absent
        return try {
            GitHubCredentialStateRecord.decode(AndroidKeyringStore.decryptLocalPayload(encrypted))
        } catch (_: Exception) {
            DurableGitHubCredentialState.Absent
        }
    }
}
