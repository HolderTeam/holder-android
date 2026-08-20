package team.holder.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.holder.android.GitSyncStatus
import team.holder.android.HolderNative
import team.holder.android.HolderProject
import team.holder.android.R
import team.holder.android.git.GitIdentity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault())

private fun formatEpochSeconds(epochSeconds: Long?): String =
    epochSeconds?.let { TIMESTAMP_FORMAT.format(Instant.ofEpochSecond(it)) } ?: "never"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitSyncScreen(project: HolderProject, onBack: () -> Unit) {
    var remoteUrlInput by remember(project.projectId) { mutableStateOf(project.gitRemoteUrl.orEmpty()) }
    var currentRemoteUrl by remember(project.projectId) { mutableStateOf(project.gitRemoteUrl) }
    var pubkeyLine by remember { mutableStateOf("") }
    var syncStatus by remember(project.projectId) { mutableStateOf<GitSyncStatus?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var pinInput by remember(project.projectId) { mutableStateOf("") }
    var exportedToken by remember(project.projectId) { mutableStateOf<String?>(null) }
    var exportError by remember(project.projectId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    suspend fun refreshSyncStatus() {
        runCatching { withContext(Dispatchers.IO) { HolderNative.gitSyncStatus(project.projectId) } }
            .onSuccess { syncStatus = it }
    }

    LaunchedEffect(project.projectId) {
        pubkeyLine = runCatching {
            withContext(Dispatchers.IO) { GitIdentity.sshPublicKeyLine() }
        }.getOrElse { "Unavailable: ${it.message ?: it::class.java.simpleName}" }
        refreshSyncStatus()
    }

    fun runAction(label: String, action: suspend () -> String) {
        if (isBusy) return
        isBusy = true
        statusMessage = null
        scope.launch {
            statusMessage = runCatching { withContext(Dispatchers.IO) { action() } }
                .getOrElse { "$label failed: ${it.message ?: it::class.java.simpleName}" }
            refreshSyncStatus()
            isBusy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Git Sync") },
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
            Text("Device SSH key", style = MaterialTheme.typography.titleMedium)
            Text(
                "Add this as a deploy key on your remote repository.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(
                    pubkeyLine,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { clipboard.setText(AnnotatedString(pubkeyLine)) }) {
                    Icon(painterResource(R.drawable.ic_copy), contentDescription = "Copy public key")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text("Remote repository", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = remoteUrlInput,
                onValueChange = { remoteUrlInput = it },
                placeholder = { Text("ssh://git@host/path/repo.git") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Row(modifier = Modifier.padding(top = 8.dp)) {
                Button(
                    enabled = !isBusy && remoteUrlInput.trim() != currentRemoteUrl.orEmpty(),
                    onClick = {
                        runAction("Save") {
                            val updated = HolderNative.updateProjectGitRemote(
                                project.projectId,
                                remoteUrlInput.trim().ifEmpty { null },
                            )
                            currentRemoteUrl = updated.gitRemoteUrl
                            "Remote saved"
                        }
                    },
                ) { Text("Save") }
                Button(
                    enabled = !isBusy,
                    onClick = {
                        runAction("Test") {
                            val result = HolderNative.testGitRemote(project.projectId)
                            "Test: ${result.status}" + (result.errorMessage?.let { " -- $it" } ?: "")
                        }
                    },
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("Test connection") }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Text("Sync", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (isBusy) CircularProgressIndicator(modifier = Modifier.padding(4.dp))
            }
            syncStatus?.let { status ->
                Text("Last push: ${formatEpochSeconds(status.lastPushAt)} (${status.lastPushStatus ?: "none"})")
                Text("Last pull: ${formatEpochSeconds(status.lastPullAt)} (${status.lastPullStatus ?: "none"})")
                Text("Uncommitted changes: ${status.uncommittedChangesCount}, unpushed commits: ${status.unpushedCommitsCount}")
                status.lastSyncError?.let { Text("Last error: $it", color = MaterialTheme.colorScheme.error) }
            }
            statusMessage?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }

            Row(modifier = Modifier.padding(top = 8.dp)) {
                Button(
                    enabled = !isBusy,
                    onClick = {
                        runAction("Push") {
                            val result = HolderNative.pushGit(project.projectId)
                            "Push: ${result.status}" + (result.errorMessage?.let { " -- $it" } ?: "")
                        }
                    },
                ) { Text("Push") }
                Button(
                    enabled = !isBusy,
                    onClick = {
                        runAction("Pull") {
                            val result = HolderNative.pullGit(project.projectId)
                            "Pull: ${result.status}" + (result.errorMessage?.let { " -- $it" } ?: "")
                        }
                    },
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("Pull") }
                Button(
                    enabled = !isBusy,
                    onClick = {
                        runAction("Sync") {
                            val pull = HolderNative.pullGit(project.projectId)
                            val push = HolderNative.pushGit(project.projectId)
                            "Sync -- pull: ${pull.status}, push: ${push.status}"
                        }
                    },
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("Sync now") }
            }

            if (project.privacyMode == "encrypted_git") {
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                Text("Encryption recovery", style = MaterialTheme.typography.titleMedium)
                Text(
                    "This project's cards are encrypted. Export a PIN-protected recovery token " +
                        "and keep it somewhere safe -- it's the only way to read this project's " +
                        "cards on another device.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { pinInput = it },
                    label = { Text("PIN") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Button(
                    enabled = !isBusy && pinInput.isNotBlank(),
                    modifier = Modifier.padding(top = 8.dp),
                    onClick = {
                        if (!isBusy) {
                            isBusy = true
                            exportError = null
                            exportedToken = null
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        HolderNative.exportRecoveryToken(project.projectId, pinInput)
                                    }
                                }.fold(
                                    onSuccess = { exportedToken = it.recoveryToken },
                                    onFailure = {
                                        exportError = it.message ?: it::class.java.simpleName
                                    },
                                )
                                isBusy = false
                            }
                        }
                    },
                ) { Text("Export recovery token") }

                exportError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                }
                exportedToken?.let { token ->
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text(token, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { clipboard.setText(AnnotatedString(token)) }) {
                            Icon(painterResource(R.drawable.ic_copy), contentDescription = "Copy recovery token")
                        }
                    }
                }
            }
        }
    }
}
