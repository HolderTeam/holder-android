package team.holder.android.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.holder.android.HolderNative
import team.holder.android.HolderProject
import team.holder.android.ui.CenteredMessage
import team.holder.android.ui.LoadState
import team.holder.android.ui.TextInputDialog

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProjectListScreen(onProjectClick: (HolderProject) -> Unit, onSettingsClick: () -> Unit) {
    var state by remember { mutableStateOf<LoadState<List<HolderProject>>>(LoadState.Loading) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var projectPendingRename by remember { mutableStateOf<HolderProject?>(null) }
    var projectPendingDelete by remember { mutableStateOf<HolderProject?>(null) }
    var menuOpenFor by remember { mutableStateOf<String?>(null) }
    // Guards create/rename/delete against double-tap: a second tap can reach the
    // same dialog button before recomposition dismisses it, re-running onConfirm.
    var isSubmitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        state = runCatching {
            withContext(Dispatchers.IO) { HolderNative.listProjects() }
        }.fold(
            onSuccess = { LoadState.Success(it) },
            onFailure = { LoadState.Error(it.message ?: it::class.java.simpleName) },
        )
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Holder") },
                actions = {
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
        when (val current = state) {
            is LoadState.Loading -> CenteredMessage(Modifier.padding(innerPadding)) {
                CircularProgressIndicator()
            }
            is LoadState.Error -> CenteredMessage(Modifier.padding(innerPadding)) {
                Text("Failed to load projects: ${current.message}")
            }
            is LoadState.Success -> {
                if (current.value.isEmpty()) {
                    CenteredMessage(Modifier.padding(innerPadding)) {
                        Text("No projects yet")
                    }
                } else {
                    LazyColumn(modifier = Modifier.padding(innerPadding)) {
                        items(current.value, key = { it.projectId }) { project ->
                            Box {
                                ListItem(
                                    headlineContent = { Text(project.name) },
                                    modifier = Modifier.combinedClickable(
                                        onClick = { onProjectClick(project) },
                                        onLongClick = { menuOpenFor = project.projectId },
                                    ),
                                )
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
                                        text = { Text("Delete") },
                                        onClick = {
                                            menuOpenFor = null
                                            projectPendingDelete = project
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
        TextInputDialog(
            title = "New project",
            label = "Name",
            confirmLabel = "Create",
            onConfirm = { name ->
                if (!isSubmitting) {
                    isSubmitting = true
                    showCreateDialog = false
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { HolderNative.createProject(name) } }
                        isSubmitting = false
                        refresh()
                    }
                }
            },
            onDismiss = { showCreateDialog = false },
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
