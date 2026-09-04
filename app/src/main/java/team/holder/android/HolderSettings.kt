package team.holder.android

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import team.holder.android.ui.theme.HolderFontFamilyOption
import team.holder.android.ui.theme.HolderFontSizeOption
import team.holder.android.ui.theme.HolderThemeOption

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "holder_settings")

/** Purely local UI preferences -- never touches libholder or card content. */
object HolderSettings {
    private val SEPARATE_TITLE_ENABLED = booleanPreferencesKey("separate_title_enabled")
    private val GIT_BACKGROUND_SYNC_ENABLED = booleanPreferencesKey("git_background_sync_enabled")
    private val GIT_BACKGROUND_SYNC_INTERVAL_MINUTES = intPreferencesKey("git_background_sync_interval_minutes")
    private val THEME_OPTION = stringPreferencesKey("theme_option")
    private val FONT_SIZE_OPTION = stringPreferencesKey("font_size_option")
    private val FONT_FAMILY_OPTION = stringPreferencesKey("font_family_option")
    private val PRESERVE_TRAILING_WHITESPACE = booleanPreferencesKey("preserve_trailing_whitespace")
    private val TRIM_TWO_SPACE_LINE_ENDINGS = booleanPreferencesKey("trim_two_space_line_endings")
    private val TRIM_WHITESPACE_IN_CODE_BLOCKS = booleanPreferencesKey("trim_whitespace_in_code_blocks")

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

    fun themeOption(context: Context): Flow<HolderThemeOption> =
        context.settingsDataStore.data.map { prefs ->
            prefs[THEME_OPTION]?.let { stored ->
                runCatching { HolderThemeOption.valueOf(stored) }.getOrNull()
            } ?: HolderThemeOption.SYSTEM
        }

    suspend fun setThemeOption(context: Context, option: HolderThemeOption) {
        context.settingsDataStore.edit { it[THEME_OPTION] = option.name }
    }

    fun fontSizeOption(context: Context): Flow<HolderFontSizeOption> =
        context.settingsDataStore.data.map { prefs ->
            prefs[FONT_SIZE_OPTION]?.let { stored ->
                runCatching { HolderFontSizeOption.valueOf(stored) }.getOrNull()
            } ?: HolderFontSizeOption.SYSTEM
        }

    suspend fun setFontSizeOption(context: Context, option: HolderFontSizeOption) {
        context.settingsDataStore.edit { it[FONT_SIZE_OPTION] = option.name }
    }

    fun fontFamilyOption(context: Context): Flow<HolderFontFamilyOption> =
        context.settingsDataStore.data.map { prefs ->
            prefs[FONT_FAMILY_OPTION]?.let { stored ->
                runCatching { HolderFontFamilyOption.valueOf(stored) }.getOrNull()
            } ?: HolderFontFamilyOption.DEFAULT
        }

    suspend fun setFontFamilyOption(context: Context, option: HolderFontFamilyOption) {
        context.settingsDataStore.edit { it[FONT_FAMILY_OPTION] = option.name }
    }

    /** Off by default: a card's raw Markdown gets its trailing whitespace cleaned up on save
     * (see [team.holder.android.ui.markdown.trimTrailingWhitespaceForSave]). On disables that
     * entirely -- whatever was typed or pasted is saved byte-for-byte, and
     * [trimTwoSpaceLineEndings]/[trimWhitespaceInCodeBlocks] are moot. */
    fun preserveTrailingWhitespace(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { it[PRESERVE_TRAILING_WHITESPACE] ?: false }

    suspend fun setPreserveTrailingWhitespace(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { it[PRESERVE_TRAILING_WHITESPACE] = enabled }
    }

    /** Off by default: a genuine hard-break run (2+ literal trailing spaces) is preserved,
     * canonicalized to exactly 2. On strips it to 0 like any other trailing whitespace, so the
     * two-space hard-break convention can never survive a save. No effect when
     * [preserveTrailingWhitespace] is on. */
    fun trimTwoSpaceLineEndings(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { it[TRIM_TWO_SPACE_LINE_ENDINGS] ?: false }

    suspend fun setTrimTwoSpaceLineEndings(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { it[TRIM_TWO_SPACE_LINE_ENDINGS] = enabled }
    }

    /** Off by default: lines inside a fenced code block are exempt from all trailing-whitespace
     * cleanup, since that whitespace may be literal pasted content. On strips it there too. No
     * effect when [preserveTrailingWhitespace] is on. */
    fun trimWhitespaceInCodeBlocks(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { it[TRIM_WHITESPACE_IN_CODE_BLOCKS] ?: false }

    suspend fun setTrimWhitespaceInCodeBlocks(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { it[TRIM_WHITESPACE_IN_CODE_BLOCKS] = enabled }
    }

    /** Non-secret local config for a connected storage provider (e.g. Drive's connected
     * account email and shared folder id) -- null when [providerId] isn't connected. One
     * generic slot per provider id rather than a dedicated field per provider, so a second
     * storage backend (S3, WebDAV, ...) doesn't need its own copy-pasted pair of settings
     * keys; see RESOURCE_STORAGE_ROADMAP.md's step 1. Deliberately never used for secrets --
     * an S3 secret key belongs in the Android keyring (see AndroidKeyringStore), same as
     * GoogleDriveAuth never stores an OAuth token here either. */
    fun connectedProviderConfig(context: Context, providerId: String): Flow<Map<String, String>?> =
        context.settingsDataStore.data.map { prefs ->
            prefs[connectedProviderConfigKey(providerId)]?.let(::decodeProviderConfig)
        }

    suspend fun setConnectedProviderConfig(context: Context, providerId: String, config: Map<String, String>?) {
        context.settingsDataStore.edit { prefs ->
            val key = connectedProviderConfigKey(providerId)
            if (config == null) prefs.remove(key) else prefs[key] = encodeProviderConfig(config)
        }
    }

    private fun connectedProviderConfigKey(providerId: String) =
        stringPreferencesKey("connected_provider_config:$providerId")

    private fun encodeProviderConfig(config: Map<String, String>): String = JSONObject(config).toString()

    private fun decodeProviderConfig(json: String): Map<String, String> {
        val obj = JSONObject(json)
        return obj.keys().asSequence().associateWith { obj.getString(it) }
    }

    /** Whether the one-time "keep your existing projects safe and synced with GitHub?"
     * offer (see GITHUB_INTEGRATION_ANDROID_PLAN.md's "Future work: back-filling
     * pre-existing local-only projects") has already been shown -- true the moment it's
     * checked, whether or not anything was eligible or she accepted anything. Never reset,
     * including across disconnect/reconnect -- a genuine one-time-ever flag, not
     * connection-state-derived, so it can't re-fire on every reconnect. */
    fun githubBackfillOfferShown(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { it[GITHUB_BACKFILL_OFFER_SHOWN] ?: false }

    suspend fun setGithubBackfillOfferShown(context: Context, shown: Boolean) {
        context.settingsDataStore.edit { it[GITHUB_BACKFILL_OFFER_SHOWN] = shown }
    }

    /** Whether the one-time "we found a backup snapshot, restore it?" offer (see
     * [team.holder.android.git.backup.RestoreOffer]) has already been shown -- true the
     * moment it's checked, whether or not a snapshot file actually existed. Never reset, same
     * shape as [githubBackfillOfferShown]: a genuine one-time-ever flag, not
     * snapshot-file-presence-derived, so deleting and re-creating a snapshot later can't
     * re-fire it. The always-available manual "Restore from backup" entry point in Settings
     * doesn't check this at all -- this flag only gates the automatic first-launch offer. */
    fun restoreOfferShown(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { it[RESTORE_OFFER_SHOWN] ?: false }

    suspend fun setRestoreOfferShown(context: Context, shown: Boolean) {
        context.settingsDataStore.edit { it[RESTORE_OFFER_SHOWN] = shown }
    }

    /** The largest card `updated_at` that was reflected in the most recently *successfully*
     * regenerated backup snapshot -- compared against the device's current max (see
     * `SnapshotWriter.deviceMaxUpdatedAt`) to decide whether a scheduled regeneration
     * (`SnapshotWorker`) has anything new to write. 0, the default, means "no snapshot has
     * ever been written," which compares as dirty against any real card. */
    fun lastSnapshotMaxUpdatedAt(context: Context): Flow<Long> =
        context.settingsDataStore.data.map { it[LAST_SNAPSHOT_MAX_UPDATED_AT] ?: 0L }

    suspend fun setLastSnapshotMaxUpdatedAt(context: Context, updatedAt: Long) {
        context.settingsDataStore.edit { it[LAST_SNAPSHOT_MAX_UPDATED_AT] = updatedAt }
    }

    private val GITHUB_BACKFILL_OFFER_SHOWN = booleanPreferencesKey("github_backfill_offer_shown")
    private val RESTORE_OFFER_SHOWN = booleanPreferencesKey("restore_offer_shown")
    private val LAST_SNAPSHOT_MAX_UPDATED_AT = longPreferencesKey("last_snapshot_max_updated_at")
}
