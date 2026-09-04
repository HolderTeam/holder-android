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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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

/** The Auto Backup snapshot: "Prepare backup now" (unconditional regeneration -- see
 * SnapshotWriter's doc comment for why this can't also trigger an actual Android backup) and
 * the entry point into RestoreBackupScreen. */
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
            Text(
                "Holder keeps a small backup snapshot ready for Android's Auto Backup to pick " +
                    "up on its own schedule -- there's no way for Holder to trigger an actual " +
                    "backup itself, only your phone's Backup settings (usually under System) " +
                    "can force one. This button just makes sure the snapshot itself is fresh " +
                    "right now, in case Auto Backup happens to run soon.",
            )
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
                ) { Text("Prepare backup now") }
                if (preparingBackup) {
                    CircularProgressIndicator(modifier = Modifier.padding(start = 8.dp).size(20.dp))
                }
            }
            prepareBackupResult?.let { Text(it, modifier = Modifier.padding(top = 4.dp)) }

            Text(
                "If Android's Auto Backup already restored a snapshot of your cards onto a " +
                    "new or reinstalled phone, restore it here.",
                modifier = Modifier.padding(top = 16.dp),
            )
            Button(onClick = onRestoreBackupClick, modifier = Modifier.padding(top = 8.dp)) {
                Text("Restore from backup")
            }
        }
    }
}
