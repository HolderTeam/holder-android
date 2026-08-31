package team.holder.android

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
    private val DRIVE_CONNECTED_ACCOUNT_EMAIL = stringPreferencesKey("drive_connected_account_email")
    private val DRIVE_FOLDER_ID = stringPreferencesKey("drive_folder_id")

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

    /** Null when Drive isn't connected. Not a secret -- just which account to show in
     * Settings and to pass to GoogleDriveAuth.authorize so it can skip the account picker.
     * See GoogleDriveAuth's doc comment for why no token is stored anywhere at all. */
    fun driveConnectedAccountEmail(context: Context): Flow<String?> =
        context.settingsDataStore.data.map { it[DRIVE_CONNECTED_ACCOUNT_EMAIL] }

    suspend fun setDriveConnectedAccountEmail(context: Context, email: String?) {
        context.settingsDataStore.edit {
            if (email == null) it.remove(DRIVE_CONNECTED_ACCOUNT_EMAIL) else it[DRIVE_CONNECTED_ACCOUNT_EMAIL] = email
        }
    }

    /** The id of the single well-known "Holder/Resources" Drive folder every project's
     * google-drive Location shares -- see GoogleDriveStorageProvider's doc comment for why
     * one global folder, not one per project, is today's deliberate simplification. Null
     * until Drive has been connected once. */
    fun driveFolderId(context: Context): Flow<String?> =
        context.settingsDataStore.data.map { it[DRIVE_FOLDER_ID] }

    suspend fun setDriveFolderId(context: Context, folderId: String?) {
        context.settingsDataStore.edit {
            if (folderId == null) it.remove(DRIVE_FOLDER_ID) else it[DRIVE_FOLDER_ID] = folderId
        }
    }
}
