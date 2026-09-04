package team.holder.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.holder.android.git.backup.SnapshotWriter
import team.holder.android.ui.openUrlExternally

/** The longer explanation of what Auto Backup is and isn't lives on the website now (see the
 * "Learn about Android backups" link below), not as in-app paragraphs -- this screen is just
 * the two actions (SnapshotWriter.regenerateAndRecordFreshness, and the entry point into
 * RestoreBackupScreen) with a line of copy each. */
private const val ANDROID_BACKUPS_HELP_URL = "https://www.holder.team/android/backups"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsScreen(onBack: () -> Unit, onRestoreBackupClick: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preparingBackup by remember { mutableStateOf(false) }
    var prepareBackupResult by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("Snapshot")
            Text("Normally kept up to date automatically.")
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Button(
                    enabled = !preparingBackup,
                    onClick = {
                        preparingBackup = true
                        prepareBackupResult = null
                        scope.launch {
                            prepareBackupResult = runCatching {
                                withContext(Dispatchers.IO) {
                                    SnapshotWriter.regenerateAndRecordFreshness(context)
                                }
                            }.fold(
                                onSuccess = { "Snapshot ready: ${it.cardCount} cards." },
                                onFailure = { "Couldn't prepare the snapshot -- ${it.message ?: it::class.java.simpleName}" },
                            )
                            preparingBackup = false
                        }
                    },
                ) { Text("Prepare") }
                if (preparingBackup) {
                    CircularProgressIndicator(modifier = Modifier.padding(start = 8.dp).size(20.dp))
                }
            }
            prepareBackupResult?.let { Text(it, modifier = Modifier.padding(top = 4.dp)) }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text("Restore")
            Text("Normally loaded automatically.")
            Button(onClick = onRestoreBackupClick, modifier = Modifier.padding(top = 8.dp)) {
                Text("Restore")
            }

            TextButton(
                onClick = { openUrlExternally(context, ANDROID_BACKUPS_HELP_URL) },
                modifier = Modifier.padding(top = 16.dp),
            ) { Text("Learn about Android backups") }
        }
    }
}
