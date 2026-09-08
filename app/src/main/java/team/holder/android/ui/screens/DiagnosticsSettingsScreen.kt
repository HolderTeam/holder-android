package team.holder.android.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import team.holder.android.diagnostics.DiagnosticsEntry
import team.holder.android.diagnostics.DiagnosticsLog
import team.holder.android.diagnostics.diagnosticsLogFile
import team.holder.android.ui.CenteredMessage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DIAGNOSTICS_TIME_FORMAT =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault())

/**
 * An activity log for this device -- sync attempts today, saves/restores/failures later -- not
 * a programmer's console; see [team.holder.android.sync.GitSyncWorker] for the one source that
 * currently records into it. Read-only: there's no in-app action that clears or edits entries.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<DiagnosticsEntry>?>(null) }

    LaunchedEffect(Unit) {
        entries = withContext(Dispatchers.IO) {
            DiagnosticsLog.read(diagnosticsLogFile(context))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        val loaded = entries
        when {
            loaded == null -> CenteredMessage(Modifier.padding(innerPadding)) { CircularProgressIndicator() }
            loaded.isEmpty() -> CenteredMessage(Modifier.padding(innerPadding)) {
                Text(
                    "No activity recorded yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(modifier = Modifier.padding(innerPadding)) {
                // Newest first, matching every other activity/history list in the app.
                items(loaded.asReversed()) { entry ->
                    DiagnosticsEntryRow(entry)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsEntryRow(entry: DiagnosticsEntry) {
    ListItem(
        headlineContent = { Text(entry.message) },
        supportingContent = {
            Text(DIAGNOSTICS_TIME_FORMAT.format(Instant.ofEpochSecond(entry.timestampSeconds)))
        },
    )
}
