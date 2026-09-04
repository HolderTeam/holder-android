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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import team.holder.android.HolderProject
import team.holder.android.git.backup.BackupRestore
import team.holder.android.git.backup.RestoreOutcome
import team.holder.android.git.backup.RestoreResult
import team.holder.android.git.backup.SnapshotGroup
import team.holder.android.git.backup.SnapshotReader
import team.holder.android.git.backup.snapshotFile
import team.holder.android.git.github.GitHubBackfill
import team.holder.android.ui.GitHubBackfillDialog
import team.holder.android.ui.LoadState

/**
 * The screen behind both of BACKUP_RESTORE_IMPLEMENTATION_PLAN.md step 9's entry points --
 * the automatic post-reinstall offer (see [team.holder.android.git.backup.RestoreOffer]) and
 * the manual "Restore from backup" button in Settings -- reached identically either way, with
 * no argument distinguishing them: this screen always just reads whatever snapshot file is
 * actually on disk right now (see [team.holder.android.git.backup.snapshotFile]).
 *
 * Each [SnapshotGroup] the snapshot contains becomes its own brand-new project (fresh id,
 * fresh git repo, no history -- see BACKUP_RESTORE_DESIGN.md), never merged into an existing
 * one. Restoring the same snapshot twice creates duplicates, not an update -- there's no
 * matching/dedup logic, deliberately (this is a recovery aid for "no project at all right
 * now," not an ongoing sync mechanism), so the warning below is real, not boilerplate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreBackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var groupsState by remember { mutableStateOf<LoadState<List<SnapshotGroup>>>(LoadState.Loading) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<Int, SnapshotGroup>?>(null) }
    var results by remember { mutableStateOf<List<RestoreResult>?>(null) }
    // Non-null only once restore has finished and at least one newly-created project has no
    // remote yet -- always true right after creation, so in practice this is "non-null iff at
    // least one group actually succeeded". Reuses GitHubBackfillDialog exactly as
    // BACKUP_RESTORE_DESIGN.md describes: "the natural restore UX... is just GitHubBackfill
    // pointed at the one just-restored project" -- not gated by
    // GitHubBackfill.checkAndMarkOfferedOnce's own one-time flag, since that flag may already
    // be spent from a previous, unrelated GitHub connection and this is a distinct, always-
    // relevant moment to ask.
    var backfillCandidates by remember { mutableStateOf<List<HolderProject>?>(null) }

    LaunchedEffect(Unit) {
        groupsState = runCatching {
            withContext(Dispatchers.IO) { SnapshotReader.readGroups(snapshotFile(context)) }
        }.fold(
            onSuccess = { LoadState.Success(it) },
            onFailure = { LoadState.Error(it.message ?: it::class.java.simpleName) },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Restore from backup") },
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
            when (val state = groupsState) {
                is LoadState.Loading -> CircularProgressIndicator()
                is LoadState.Error -> Text(
                    "Couldn't read the backup snapshot: ${state.message}",
                    color = MaterialTheme.colorScheme.error,
                )
                is LoadState.Success -> RestoreContent(
                    groups = state.value,
                    running = running,
                    progress = progress,
                    results = results,
                    onRestore = {
                        running = true
                        scope.launch {
                            val settled = BackupRestore.restoreAll(state.value) { index, _, group ->
                                progress = index to group
                            }
                            results = settled
                            running = false
                            val restoredProjects = settled.mapNotNull { (it.outcome as? RestoreOutcome.Success)?.project }
                            GitHubBackfill.eligibleProjects(restoredProjects).let { eligible ->
                                if (eligible.isNotEmpty()) backfillCandidates = eligible
                            }
                        }
                    },
                    onDone = onBack,
                )
            }
        }
    }

    backfillCandidates?.let { candidates ->
        GitHubBackfillDialog(projects = candidates, onFinished = { backfillCandidates = null })
    }
}

@Composable
private fun RestoreContent(
    groups: List<SnapshotGroup>,
    running: Boolean,
    progress: Pair<Int, SnapshotGroup>?,
    results: List<RestoreResult>?,
    onRestore: () -> Unit,
    onDone: () -> Unit,
) {
    if (groups.isEmpty()) {
        Text("No backup snapshot found on this device.")
        return
    }

    val settledResults = results
    when {
        settledResults != null -> {
            settledResults.forEach { result ->
                when (val outcome = result.outcome) {
                    is RestoreOutcome.Success ->
                        Text("${outcome.project.name}: restored ${result.group.cardCount} cards.")
                    is RestoreOutcome.Failure ->
                        Text(
                            "${result.group.projectName}: couldn't restore -- ${outcome.message}",
                            color = MaterialTheme.colorScheme.error,
                        )
                }
            }
            Button(onClick = onDone, modifier = Modifier.padding(top = 16.dp)) { Text("Done") }
        }
        running -> {
            val (index, group) = progress ?: (0 to null)
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Text(
                    "Restoring ${index + 1} of ${groups.size}" + (group?.let { ": ${it.projectName}" } ?: ""),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        else -> {
            Text("Found a backup on this device with ${groups.size} " + (if (groups.size == 1) "project" else "projects") + ":")
            groups.forEach { group ->
                Text(
                    "• ${group.projectName}: ${group.cardCount} " + (if (group.cardCount == 1) "card" else "cards"),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(
                "Restoring creates brand-new project(s) from this backup -- it never merges into " +
                    "an existing project, and there's no history, just the cards as they were when " +
                    "the backup was made. Restoring the same backup twice creates duplicates, so " +
                    "only do this once per backup.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp),
            )
            Button(onClick = onRestore, modifier = Modifier.padding(top = 16.dp)) { Text("Restore") }
        }
    }
}
