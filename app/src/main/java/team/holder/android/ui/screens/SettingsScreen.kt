package team.holder.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The Settings index -- one row per subpage, each its own screen/back-stack entry
 * (AppearanceSettingsScreen, EditorSettingsScreen, BackupSettingsScreen, SyncSettingsScreen,
 * StorageSettingsScreen, AboutSettingsScreen). Split out of what used to be one long scrolling
 * screen once it grew too long to navigate comfortably -- Theme, Font, Editor prefs, Backup,
 * GitHub, Google Drive and S3 all stacked in a single Column. Each subpage now owns its own
 * scroll position and remembered dialog/menu state instead of all of it living together
 * whether the relevant section is in view or not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onAppearanceClick: () -> Unit,
    onEditorClick: () -> Unit,
    onBackupClick: () -> Unit,
    onSyncClick: () -> Unit,
    onStorageClick: () -> Unit,
    onAboutClick: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxWidth().padding(innerPadding)) {
            SettingsIndexRow("Appearance", "Theme, font size, font", onAppearanceClick)
            SettingsIndexRow("Editor", "Title field, trailing whitespace handling", onEditorClick)
            SettingsIndexRow("Backup", "Auto Backup snapshot, restore from backup", onBackupClick)
            SettingsIndexRow("Sync", "Background git sync, GitHub", onSyncClick)
            SettingsIndexRow("Storage", "Google Drive, S3-compatible storage", onStorageClick)
            SettingsIndexRow("About", "Version", onAboutClick)
        }
    }
}

@Composable
private fun SettingsIndexRow(title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}
