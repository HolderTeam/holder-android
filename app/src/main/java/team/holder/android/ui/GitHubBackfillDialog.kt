package team.holder.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlinx.coroutines.launch
import team.holder.android.HolderProject
import team.holder.android.git.github.BackfillOutcome
import team.holder.android.git.github.BackfillResult
import team.holder.android.git.github.GitHubBackfill

/**
 * The one-time offer itself -- see GITHUB_INTEGRATION_ANDROID_PLAN.md's "Future work:
 * back-filling pre-existing local-only projects" for the full design, including why the
 * copy says "safe and synced," never "backup." [projects] is always non-empty (the caller
 * only shows this dialog when [GitHubBackfill.checkAndMarkOfferedOnce] returned something).
 * [onFinished] is called once, whether she declined outright or the sync run completed
 * (successfully or partially) -- the caller's cue to dismiss and, if it cares, refresh its
 * own project list.
 */
@Composable
fun GitHubBackfillDialog(projects: List<HolderProject>, onFinished: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(projects.map { it.projectId }.toSet()) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<Int, HolderProject>?>(null) }
    var results by remember { mutableStateOf<List<BackfillResult>?>(null) }

    AlertDialog(
        onDismissRequest = { if (!running) onFinished() },
        title = { Text("Keep your existing projects safe and synced with GitHub?") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                val settledResults = results
                when {
                    settledResults != null -> {
                        val succeeded = settledResults.count { it.outcome is BackfillOutcome.Success }
                        Text("${succeeded} of ${settledResults.size} synced.")
                        settledResults.filter { it.outcome !is BackfillOutcome.Success }.forEach { result ->
                            val message = when (val outcome = result.outcome) {
                                is BackfillOutcome.GitHubFailure -> githubErrorMessage(outcome.error)
                                is BackfillOutcome.LocalFailure -> outcome.message
                                BackfillOutcome.Success -> "" // unreachable, filtered above
                            }
                            Text(
                                "${result.project.name}: couldn't sync -- $message",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    running -> {
                        val (index, project) = progress ?: (0 to null)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Text(
                                "Syncing ${index + 1} of ${projects.size}" +
                                    (project?.let { ": ${it.name}" } ?: ""),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    else -> {
                        Text(
                            "This only asks once -- leaving a project unticked keeps it on this " +
                                "device only, for good.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        projects.forEach { project ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            ) {
                                Checkbox(
                                    checked = project.projectId in selected,
                                    onCheckedChange = { checked ->
                                        selected = if (checked) selected + project.projectId else selected - project.projectId
                                    },
                                )
                                Text(project.name)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (results != null) {
                TextButton(onClick = onFinished) { Text("Done") }
            } else {
                TextButton(
                    enabled = !running && selected.isNotEmpty(),
                    onClick = {
                        running = true
                        scope.launch {
                            val toSync = projects.filter { it.projectId in selected }
                            results = GitHubBackfill.backfill(context, toSync) { index, _, project ->
                                progress = index to project
                            }
                            running = false
                        }
                    },
                ) { Text("Sync") }
            }
        },
        dismissButton = {
            if (results == null) {
                TextButton(enabled = !running, onClick = onFinished) { Text("Not now") }
            }
        },
    )
}
