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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.holder.android.GitSyncStatus
import team.holder.android.HolderNative
import team.holder.android.HolderProject
import team.holder.android.R
import team.holder.android.git.GitIdentity
import team.holder.android.git.github.GitHubConnection
import team.holder.android.git.github.GitHubResult
import team.holder.android.git.github.parseGitHubOwnerRepo
import team.holder.android.ui.githubErrorMessage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault())

private fun formatEpochSeconds(epochSeconds: Long?): String =
    epochSeconds?.let { TIMESTAMP_FORMAT.format(Instant.ofEpochSecond(it)) } ?: "never"

// A pull that hit a genuine conflict (the same card changed on both this device and the
// remote) still reports "succeeded" -- it was resolved, not failed -- so this is the only way
// the user finds out a "(conflicted copy)" card now exists and is worth a look.
private fun conflictsSuffix(conflictsResolved: Int): String =
    if (conflictsResolved > 0) {
        " ($conflictsResolved conflict${if (conflictsResolved == 1) "" else "s"} resolved)"
    } else {
        ""
    }

/** The device key section's own default visibility -- shown unprompted only when it looks
 * like the user might actually need to go paste it somewhere themselves: no remote configured
 * yet, or a non-GitHub remote that's never had a confirmed successful push/pull. A GitHub
 * remote never needs this (Holder registers that key automatically, see
 * GitHubConnection.ensureProjectRepo/registerDeployKey), and a non-GitHub remote that's
 * already synced successfully already has whatever key it needed in place. Neither case
 * should surface a regular, non-technical user's raw SSH key unprompted -- they can still
 * reveal it manually via the toggle either way. */
private fun defaultShowDeviceKey(remoteUrl: String?, syncStatus: GitSyncStatus?): Boolean {
    if (remoteUrl.isNullOrBlank()) return true
    if (parseGitHubOwnerRepo(remoteUrl) != null) return false
    val everSyncedOk = syncStatus?.let { it.lastSyncError == null && (it.lastPushAt != null || it.lastPullAt != null) } ?: false
    return !everSyncedOk
}

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
    var showDeviceKey by remember(project.projectId) { mutableStateOf(false) }
    // Once the user has explicitly toggled it themselves this screen visit, their choice wins
    // -- refreshSyncStatus() re-running after every Push/Pull/Sync action must never silently
    // flip it back closed on them mid-session just because a push finally succeeded.
    var deviceKeyManuallyToggled by remember(project.projectId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val githubOwnerRepo = currentRemoteUrl?.let(::parseGitHubOwnerRepo)

    suspend fun refreshSyncStatus() {
        runCatching { withContext(Dispatchers.IO) { HolderNative.gitSyncStatus(project.projectId) } }
            .onSuccess {
                syncStatus = it
                if (!deviceKeyManuallyToggled) showDeviceKey = defaultShowDeviceKey(currentRemoteUrl, it)
            }
    }

    LaunchedEffect(project.projectId) {
        pubkeyLine = runCatching {
            // This project's own key, not a device-wide one -- most git hosts (GitHub
            // included) reject the same public key being registered as a deploy key on more
            // than one repository, so a shared key would only ever actually work for
            // whichever project registered it first.
            withContext(Dispatchers.IO) { GitIdentity.sshPublicKeyLine(alias = GitIdentity.aliasForProject(project.projectId)) }
        }.getOrElse { "Unavailable: ${it.message ?: it::class.java.simpleName}" }
        if (!deviceKeyManuallyToggled) showDeviceKey = defaultShowDeviceKey(currentRemoteUrl, syncStatus)
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
                title = { Text("Project Sync") },
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
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("Sync", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (isBusy) CircularProgressIndicator(modifier = Modifier.padding(4.dp))
            }
            syncStatus?.let { status ->
                Text("Last push: ${formatEpochSeconds(status.lastPushAt)} (${status.lastPushStatus ?: "none"})")
                Text("Last pull: ${formatEpochSeconds(status.lastPullAt)} (${status.lastPullStatus ?: "none"})")
                Text("Uncommitted changes: ${status.uncommittedChangesCount}, unpushed commits: ${status.unpushedCommitsCount}")
                status.lastSyncError?.let { Text("Last error: $it", color = MaterialTheme.colorScheme.error) }

                // A GitHub remote's deploy key is something Holder registers automatically
                // (ensureProjectRepo/RecoverProjectScreen), never something a regular user is
                // expected to paste by hand -- so when it's this specific remote failing, the
                // real fix is re-running that same registration, not surfacing the raw device
                // key section above (which stays collapsed for a GitHub remote regardless, see
                // defaultShowDeviceKey). Covers exactly the case a pre-per-project-key-fix
                // project actually hits: a genuinely new key that was simply never registered.
                if (githubOwnerRepo != null && status.lastSyncError != null) {
                    val (owner, repo) = githubOwnerRepo
                    Button(
                        enabled = !isBusy,
                        modifier = Modifier.padding(top = 8.dp),
                        onClick = {
                            runAction("Register device") {
                                when (val result = GitHubConnection.registerDeployKey(context, project.projectId, owner, repo)) {
                                    is GitHubResult.Success -> {
                                        val retry = HolderNative.pullGit(project.projectId)
                                        "Registered this device with GitHub -- retry pull: ${retry.status}" +
                                            (retry.errorMessage?.let { " -- $it" } ?: "")
                                    }
                                    is GitHubResult.Failure -> "Couldn't register device: ${githubErrorMessage(result.error)}"
                                }
                            }
                        },
                    ) { Text("Register device with GitHub") }
                }
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
                            "Pull: ${result.status}" + conflictsSuffix(result.conflictsResolved) +
                                (result.errorMessage?.let { " -- $it" } ?: "")
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
                            "Sync -- pull: ${pull.status}" + conflictsSuffix(pull.conflictsResolved) +
                                ", push: ${push.status}"
                        }
                    },
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("Sync now") }
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
                            // A changed remote is a fresh context -- re-derive the default
                            // rather than honoring a toggle choice made about the old one.
                            deviceKeyManuallyToggled = false
                            showDeviceKey = defaultShowDeviceKey(currentRemoteUrl, syncStatus)
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

            // Hidden by default once a GitHub remote or a working non-GitHub remote makes it
            // irrelevant (see defaultShowDeviceKey) -- most Holder users never set this up by
            // hand and shouldn't be shown a raw SSH key unprompted. Someone who does need it
            // (a from-scratch manual/non-GitHub remote) gets it shown automatically instead of
            // having to find this toggle at all; everyone else can still reveal it here.
            TextButton(onClick = {
                deviceKeyManuallyToggled = true
                showDeviceKey = !showDeviceKey
            }) { Text(if (showDeviceKey) "Hide device key" else "See device key") }
            if (showDeviceKey) {
                Text(
                    "If you used Holder's automatic setup for Project Sync, you can safely ignore the " +
                        "advanced technical details below.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "The following text is this device's SSH key for this project. It is used as a " +
                        "deploy key for the project's remote repository. You only need it if you're " +
                        "configuring Git yourself.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
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
                    visualTransformation = PasswordVisualTransformation(),
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
