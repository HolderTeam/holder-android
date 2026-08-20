package team.holder.android

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "holder_settings")

/** Purely local UI preferences -- never touches libholder or card content. */
object HolderSettings {
    private val SEPARATE_TITLE_ENABLED = booleanPreferencesKey("separate_title_enabled")
    private val GIT_BACKGROUND_SYNC_ENABLED = booleanPreferencesKey("git_background_sync_enabled")
    private val GIT_BACKGROUND_SYNC_INTERVAL_MINUTES = intPreferencesKey("git_background_sync_interval_minutes")

    const val DEFAULT_BACKGROUND_SYNC_INTERVAL_MINUTES = 15

    /** When true, cards are edited with a distinct Title field (today's behavior). When
     * false, there's no Title field -- the first non-blank line of the body is the title. */
    fun separateTitleEnabled(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { it[SEPARATE_TITLE_ENABLED] ?: true }

    suspend fun setSeparateTitleEnabled(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { it[SEPARATE_TITLE_ENABLED] = enabled }
    }

    /** Off by default: background sync uses battery/data even when the app isn't open. */
    fun gitBackgroundSyncEnabled(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { it[GIT_BACKGROUND_SYNC_ENABLED] ?: false }

    suspend fun setGitBackgroundSyncEnabled(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { it[GIT_BACKGROUND_SYNC_ENABLED] = enabled }
    }

    /** WorkManager's periodic-work floor is 15 minutes; this is never allowed below that. */
    fun gitBackgroundSyncIntervalMinutes(context: Context): Flow<Int> =
        context.settingsDataStore.data.map {
            it[GIT_BACKGROUND_SYNC_INTERVAL_MINUTES] ?: DEFAULT_BACKGROUND_SYNC_INTERVAL_MINUTES
        }

    suspend fun setGitBackgroundSyncIntervalMinutes(context: Context, minutes: Int) {
        context.settingsDataStore.edit {
            it[GIT_BACKGROUND_SYNC_INTERVAL_MINUTES] = minutes.coerceAtLeast(DEFAULT_BACKGROUND_SYNC_INTERVAL_MINUTES)
        }
    }
}
