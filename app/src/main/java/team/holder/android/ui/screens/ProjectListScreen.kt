package team.holder.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.holder.android.HolderNative
import team.holder.android.HolderProject
import team.holder.android.R
import team.holder.android.git.github.GitHubConnection
import team.holder.android.git.github.GitHubResult
import team.holder.android.git.github.GitHubStatus
import team.holder.android.ui.CenteredMessage
import team.holder.android.ui.LoadState
import team.holder.android.ui.TextInputDialog
import team.holder.android.ui.githubErrorMessage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreen(
    onProjectClick: (HolderProject) -> Unit,
    onGitSyncClick: (HolderProject) -> Unit,
    onRecoverProjectClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<LoadState<List<HolderProject>>>(LoadState.Loading) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var projectPendingRename by remember { mutableStateOf<HolderProject?>(null) }
    var projectPendingDelete by remember { mutableStateOf<HolderProject?>(null) }
    var menuOpenFor by remember { mutableStateOf<String?>(null) }
    // Guards create/rename/delete against double-tap: a second tap can reach the
    // same dialog button before recomposition dismisses it, re-running onConfirm.
    var isSubmitting by remember { mutableStateOf(false) }
    // Whether the New Project dialog should even offer the "keep this on device only"
    // choice -- see GITHUB_INTEGRATION_ANDROID_PLAN.md's wiring point 2: nothing to opt out
    // of if GitHub was never connected in the first place. A stale value between GitHub
    // connect/disconnect and this screen's next load is a minor UX gap, not a correctness
    // one -- ensureProjectRepo below still fails cleanly (AuthorizationRequired) if it does.
    var githubConnected by remember { mutableStateOf(false) }
    // A dismissible summary of the one thing that can go wrong silently otherwise: local
    // project creation always succeeds even if the GitHub half doesn't, and a user who
    // never notices that isn't actually getting the backup they think they are.
    var githubSyncNotice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        state = runCatching {
            withContext(Dispatchers.IO) { HolderNative.listProjects() }
        }.fold(
            onSuccess = { LoadState.Success(it) },
            onFailure = { LoadState.Error(it.message ?: it::class.java.simpleName) },
        )
    }

    LaunchedEffect(Unit) {
        refresh()
        githubConnected = runCatching { GitHubConnection.status(context) }.getOrNull() is GitHubStatus.Connected
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Holder") },
                actions = {
                    IconButton(onClick = onRecoverProjectClick) {
                        Icon(painterResource(R.drawable.ic_restore), contentDescription = "Recover project")
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New project")
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            githubSyncNotice?.let { notice ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(notice, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { githubSyncNotice = null }) {
                        Icon(Icons.Filled.Close, contentDescription = "Dismiss")
                    }
                }
            }

            // weight(1f), not just a bare Box, so CenteredMessage's own fillMaxSize() and the
            // LazyColumn's scrolling both get the actual remaining bounded height to work
            // with -- the notice banner above (when present) is the only sibling competing
            // for space in this Column.
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (val current = state) {
                    is LoadState.Loading -> CenteredMessage {
                        CircularProgressIndicator()
                    }
                    is LoadState.Error -> CenteredMessage {
                        Text("Failed to load projects: ${current.message}")
                    }
                    is LoadState.Success -> {
                        if (current.value.isEmpty()) {
                            CenteredMessage {
                                Text("No projects yet")
                            }
                        } else {
                            LazyColumn {
                                items(current.value, key = { it.projectId }) { project ->
                                    ListItem(
                                        headlineContent = { Text(project.name) },
                                        modifier = Modifier.clickable { onProjectClick(project) },
                                        trailingContent = {
                                            // The Box, not just the IconButton, is what DropdownMenu
                                            // anchors to -- keeping it scoped to just the button
                                            // (rather than wrapping the whole row, which anchored
                                            // the menu to the row's own top-start instead of where
                                            // the button actually is) is what makes the menu open
                                            // under the button itself.
                                            Box {
                                                IconButton(onClick = { menuOpenFor = project.projectId }) {
                                                    Icon(
                                                        Icons.Filled.MoreVert,
                                                        contentDescription = "More options for ${project.name}",
                                                    )
                                                }
                                                DropdownMenu(
                                                    expanded = menuOpenFor == project.projectId,
                                                    onDismissRequest = { menuOpenFor = null },
                                                ) {
                                                    DropdownMenuItem(
                                                        text = { Text("Rename") },
                                                        onClick = {
                                                            menuOpenFor = null
                                                            projectPendingRename = project
                                                        },
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text("Git Sync") },
                                                        onClick = {
                                                            menuOpenFor = null
                                                            onGitSyncClick(project)
                                                        },
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text("Delete") },
                                                        onClick = {
                                                            menuOpenFor = null
                                                            projectPendingDelete = project
                                                        },
                                                    )
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        // Scoped to this block, not hoisted with the rest of this screen's state -- resets
        // to unticked every time the dialog is freshly opened, which is what we want (no
        // reason a previous "keep local" choice should carry over to the next project).
        var keepLocalOnly by remember { mutableStateOf(false) }
        TextInputDialog(
            title = "New project",
            label = "Name",
            confirmLabel = "Create",
            onConfirm = { name ->
                if (!isSubmitting) {
                    isSubmitting = true
                    showCreateDialog = false
                    scope.launch {
                        val created = runCatching {
                            withContext(Dispatchers.IO) { HolderNative.createProject(name) }
                        }.getOrNull()
                        // Remote-backed by default once GitHub is connected -- see the plan's
                        // wiring point 2 for why this decision happens before any GitHub call
                        // rather than create-then-offer-to-undo.
                        if (created != null && githubConnected && !keepLocalOnly) {
                            githubSyncNotice = null
                            when (val repoResult = GitHubConnection.ensureProjectRepo(context, created)) {
                                is GitHubResult.Success -> {
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            HolderNative.updateProjectGitRemote(created.projectId, repoResult.value)
                                        }
                                    }.onFailure { failure ->
                                        githubSyncNotice = "Created a GitHub repository for \"${created.name}\", " +
                                            "but couldn't link it: ${failure.message}"
                                    }
                                }
                                is GitHubResult.Failure -> {
                                    githubSyncNotice = "\"${created.name}\" was created, but GitHub backup " +
                                        "didn't finish: ${githubErrorMessage(repoResult.error)}"
                                }
                            }
                        }
                        isSubmitting = false
                        refresh()
                    }
                }
            },
            onDismiss = { showCreateDialog = false },
            extraContent = if (githubConnected) {
                {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Checkbox(checked = keepLocalOnly, onCheckedChange = { keepLocalOnly = it })
                        Text(
                            "Keep this project on this device only",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            } else {
                null
            },
        )
    }

    projectPendingRename?.let { project ->
        TextInputDialog(
            title = "Rename project",
            label = "Name",
            initialValue = project.name,
            confirmLabel = "Rename",
            onConfirm = { name ->
                if (!isSubmitting) {
                    isSubmitting = true
                    projectPendingRename = null
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { HolderNative.renameProject(project.projectId, name) }
                        }
                        isSubmitting = false
                        refresh()
                    }
                }
            },
            onDismiss = { projectPendingRename = null },
        )
    }

    projectPendingDelete?.let { project ->
        AlertDialog(
            onDismissRequest = { projectPendingDelete = null },
            title = { Text("Delete \"${project.name}\"?") },
            text = { Text("This deletes the project and its cards.") },
            confirmButton = {
                TextButton(onClick = {
                    if (!isSubmitting) {
                        isSubmitting = true
                        projectPendingDelete = null
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { HolderNative.deleteProject(project.projectId) } }
                            isSubmitting = false
                            refresh()
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { projectPendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}
