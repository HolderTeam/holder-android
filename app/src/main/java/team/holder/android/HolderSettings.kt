package team.holder.android

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "holder_settings")

/** Purely local UI preferences -- never touches libholder or card content. */
object HolderSettings {
    private val SEPARATE_TITLE_ENABLED = booleanPreferencesKey("separate_title_enabled")

    /** When true, cards are edited with a distinct Title field (today's behavior). When
     * false, there's no Title field -- the first non-blank line of the body is the title. */
    fun separateTitleEnabled(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { it[SEPARATE_TITLE_ENABLED] ?: true }

    suspend fun setSeparateTitleEnabled(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { it[SEPARATE_TITLE_ENABLED] = enabled }
    }
}
