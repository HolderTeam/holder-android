package team.holder.android.ui.screens

import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import team.holder.android.HolderProject
import team.holder.android.HolderSettings
import team.holder.android.git.github.DeviceAuthorization
import team.holder.android.git.github.GitHubBackfill
import team.holder.android.git.github.GitHubConnection
import team.holder.android.git.github.GitHubStatus
import team.holder.android.sync.GitSyncScheduler
import team.holder.android.ui.GitHubBackfillDialog
import team.holder.android.ui.GitHubDeviceFlowDialog
import team.holder.android.ui.openUrlExternally

private val BACKGROUND_SYNC_INTERVAL_OPTIONS_MINUTES = listOf(15, 30, 60, 120)

/** Keeping projects in sync with somewhere else: background git sync's own schedule, and the
 * GitHub paved-road connection. Google Drive/S3 (attachment storage, not project sync) live in
 * StorageSettingsScreen instead. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backgroundSyncEnabled by HolderSettings.gitBackgroundSyncEnabled(context).collectAsState(initial = false)
    val backgroundSyncIntervalMinutes by HolderSettings.gitBackgroundSyncIntervalMinutes(context)
        .collectAsState(initial = HolderSettings.DEFAULT_BACKGROUND_SYNC_INTERVAL_MINUTES)
    var intervalMenuExpanded by remember { mutableStateOf(false) }
    // null while the initial check is in flight -- see GitHubConnectionSection for how that
    // (as opposed to a genuine NotConnected) is rendered.
    var githubStatus by remember { mutableStateOf<GitHubStatus?>(null) }
    var githubBusy by remember { mutableStateOf(false) }
    var githubError by remember { mutableStateOf<String?>(null) }
    var pendingGithubAuth by remember { mutableStateOf<DeviceAuthorization?>(null) }
    // Cancelling the dialog needs to cancel the actual in-flight Device Flow poll too, not
    // just hide the dialog -- see GitHubDeviceFlowDialog's doc comment.
    var githubConnectJob by remember { mutableStateOf<Job?>(null) }
    // Non-null only while the one-time "sync your existing projects?" offer is showing --
    // see GitHubBackfill.checkAndMarkOfferedOnce, called below every time this screen learns
    // status is Connected. Idempotent (no-ops after the first real time), so it's safe to
    // call from every one of those places rather than needing one single canonical trigger.
    var backfillCandidates by remember { mutableStateOf<List<HolderProject>?>(null) }

    suspend fun maybeOfferBackfill(status: GitHubStatus) {
        if (status is GitHubStatus.Connected) {
            GitHubBackfill.checkAndMarkOfferedOnce(context).let { eligible ->
                if (eligible.isNotEmpty()) backfillCandidates = eligible
            }
        }
    }

    fun recheckGithubStatus() {
        githubError = null
        githubBusy = true
        scope.launch {
            runCatching { GitHubConnection.status(context) }
                .onSuccess {
                    githubStatus = it
                    maybeOfferBackfill(it)
                }
                .onFailure { githubError = it.message ?: "Could not check GitHub status" }
            githubBusy = false
        }
    }

    LaunchedEffect(Unit) { recheckGithubStatus() }

    // Keeps WorkManager's schedule in sync whenever either setting changes here, in addition
    // to the reconcile MainActivity does once at process start.
    LaunchedEffect(backgroundSyncEnabled, backgroundSyncIntervalMinutes) {
        GitSyncScheduler.reconcile(context, backgroundSyncEnabled, backgroundSyncIntervalMinutes)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync") },
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
                Column(modifier = Modifier.weight(1f)) {
                    Text("Background git sync")
                    Text("Periodically pull and push projects with a remote configured, even when Holder isn't open. Uses battery and data.")
                }
                Switch(
                    checked = backgroundSyncEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch { HolderSettings.setGitBackgroundSyncEnabled(context, enabled) }
                    },
                )
            }

            if (backgroundSyncEnabled) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("Sync every", modifier = Modifier.weight(1f).padding(top = 12.dp))
                    Box {
                        Button(onClick = { intervalMenuExpanded = true }) {
                            Text("$backgroundSyncIntervalMinutes min")
                        }
                        DropdownMenu(
                            expanded = intervalMenuExpanded,
                            onDismissRequest = { intervalMenuExpanded = false },
                        ) {
                            BACKGROUND_SYNC_INTERVAL_OPTIONS_MINUTES.forEach { minutes ->
                                DropdownMenuItem(
                                    text = { Text("$minutes min") },
                                    onClick = {
                                        intervalMenuExpanded = false
                                        scope.launch {
                                            HolderSettings.setGitBackgroundSyncIntervalMinutes(context, minutes)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            GitHubConnectionSection(
                status = githubStatus,
                busy = githubBusy,
                error = githubError,
                onConnect = {
                    githubError = null
                    githubBusy = true
                    githubConnectJob = scope.launch {
                        runCatching {
                            GitHubConnection.connect(context) { authorization -> pendingGithubAuth = authorization }
                        }.onSuccess { newStatus ->
                            pendingGithubAuth = null
                            githubStatus = newStatus
                            maybeOfferBackfill(newStatus)
                        }.onFailure { failure ->
                            pendingGithubAuth = null
                            githubError = failure.message ?: "Could not connect to GitHub"
                        }
                        githubBusy = false
                    }
                },
                onDisconnect = {
                    scope.launch {
                        GitHubConnection.disconnect(context)
                        githubStatus = GitHubStatus.NotConnected
                    }
                },
                onOpenUrl = { url -> openUrlExternally(context, url) },
                onRetryInstallCheck = { recheckGithubStatus() },
            )

            backfillCandidates?.let { candidates ->
                GitHubBackfillDialog(
                    projects = candidates,
                    onFinished = { backfillCandidates = null },
                )
            }

            pendingGithubAuth?.let { authorization ->
                GitHubDeviceFlowDialog(
                    authorization = authorization,
                    onCancel = {
                        githubConnectJob?.cancel()
                        pendingGithubAuth = null
                        githubBusy = false
                    },
                )
            }
        }
    }
}

/**
 * GitHub's Settings row -- deliberately not [StorageProviderConnectionRow], since GitHub
 * isn't a storage provider and has more states than connected/disconnected (see
 * GITHUB_INTEGRATION_ANDROID_PLAN.md's "Connection state" section: [GitHubStatus] has four
 * states, not two, and [GitHubStatus.InstallationRequired]/[GitHubStatus.Connected] each
 * need their own secondary action beyond Connect/Disconnect). [status] is null only while
 * the very first check is in flight -- not a fifth state, just "don't know yet."
 */
@Composable
private fun GitHubConnectionSection(
    status: GitHubStatus?,
    busy: Boolean,
    error: String?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onRetryInstallCheck: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text("GitHub")
            Text(
                when (status) {
                    null -> "Checking..."
                    GitHubStatus.NotConnected ->
                        "Let Holder create and manage GitHub repositories for your projects."
                    GitHubStatus.AuthorizationRequired -> "Your GitHub sign-in needs to be renewed."
                    is GitHubStatus.InstallationRequired -> "Signed in -- one more step is needed on GitHub."
                    is GitHubStatus.Connected -> "Connected as @${status.login}"
                },
            )
            error?.let { message -> Text(message, color = MaterialTheme.colorScheme.error) }
        }
        when {
            busy -> CircularProgressIndicator(modifier = Modifier.padding(12.dp))
            status == null -> {}
            status is GitHubStatus.Connected -> TextButton(onClick = onDisconnect) { Text("Disconnect") }
            status is GitHubStatus.InstallationRequired ->
                Button(onClick = { onOpenUrl(status.installUrl) }) { Text("Finish setup") }
            status is GitHubStatus.AuthorizationRequired -> Button(onClick = onConnect) { Text("Reconnect") }
            else -> Button(onClick = onConnect) { Text("Connect") }
        }
    }
    if (status is GitHubStatus.InstallationRequired && !busy) {
        TextButton(onClick = onRetryInstallCheck, modifier = Modifier.padding(top = 4.dp)) {
            Text("I've installed it -- check again")
        }
    }
    if (status is GitHubStatus.Connected) {
        TextButton(
            onClick = { onOpenUrl(status.installationSettingsUrl) },
            modifier = Modifier.padding(top = 4.dp),
        ) { Text("Manage repository access") }
    }
}
