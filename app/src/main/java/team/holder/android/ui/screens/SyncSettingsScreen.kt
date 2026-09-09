package team.holder.android.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import kotlinx.coroutines.launch
import team.holder.android.HolderProject
import team.holder.android.HolderSettings
import team.holder.android.git.github.GitHubBackfill
import team.holder.android.git.github.GitHubConnection
import team.holder.android.git.github.GitHubConnectionCoordinator
import team.holder.android.git.github.GitHubResult
import team.holder.android.git.github.GitHubStatus
import team.holder.android.sync.GitSyncScheduler
import team.holder.android.ui.GitHubBackfillDialog
import team.holder.android.ui.githubErrorMessage
import team.holder.android.ui.openUrlExternally

private val BACKGROUND_SYNC_INTERVAL_OPTIONS_MINUTES = listOf(15, 30, 60, 120)

// Mirrors BackupSettingsScreen's ANDROID_BACKUPS_HELP_URL -- same website, same
// /android/<topic> shape.
private const val SYNC_HELP_URL = "https://www.holder.team/android/sync"

/** Keeping projects in sync with somewhere else: background git sync's own schedule, and the
 * GitHub paved-road connection. Google Drive/S3 (attachment storage, not project sync) live in
 * StorageSettingsScreen instead. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettingsScreen(onBack: () -> Unit, browserLauncher: GitHubConnectionCoordinator.GitHubBrowserLauncher) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backgroundSyncEnabled by HolderSettings.gitBackgroundSyncEnabled(context).collectAsState(initial = false)
    val backgroundSyncIntervalMinutes by HolderSettings.gitBackgroundSyncIntervalMinutes(context)
        .collectAsState(initial = HolderSettings.DEFAULT_BACKGROUND_SYNC_INTERVAL_MINUTES)
    var intervalMenuExpanded by remember { mutableStateOf(false) }
    // The coordinator's own published state, updated automatically by any completed connect()/
    // disconnect()/status() call from anywhere -- including an OAuth callback or Setup URL
    // return handled in MainActivity, not just this screen's own recheckGithubStatus() below.
    // Starts as NotConnected until the initial recheck (LaunchedEffect below) resolves.
    val githubStatus by GitHubConnection.statusFlow.collectAsState()
    var githubBusy by remember { mutableStateOf(false) }
    var githubError by remember { mutableStateOf<String?>(null) }
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
                .onSuccess { maybeOfferBackfill(it) }
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
                    Text("Automatic background sync")
                    Text("Uses battery and Wi-Fi/Data.")
                }
                Spacer(modifier = Modifier.width(16.dp))
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
                    scope.launch {
                        runCatching { GitHubConnection.connect(context, browserLauncher) }
                            .onSuccess { result ->
                                when (result) {
                                    is GitHubResult.Success -> maybeOfferBackfill(result.value)
                                    is GitHubResult.Failure -> githubError = githubErrorMessage(result.error)
                                }
                            }
                            .onFailure { failure -> githubError = failure.message ?: "Could not connect to GitHub" }
                        githubBusy = false
                    }
                },
                onCancel = {
                    scope.launch { GitHubConnection.cancelPendingBrowserAuthorization() }
                },
                onDisconnect = { scope.launch { GitHubConnection.disconnect(context) } },
                onOpenUrl = { url -> openUrlExternally(context, url) },
                onFinishSetup = { installUrl ->
                    scope.launch {
                        // install_state correlates the Setup URL return -- once it arrives,
                        // MainActivity forwards it to GitHubConnection.handleInstallationReturn,
                        // which re-checks status() and updates statusFlow on its own. This
                        // screen is already collecting that Flow, so it picks up Connected
                        // automatically with no second button to press.
                        val state = GitHubConnection.beginInstallationReturn()
                        openUrlExternally(context, "$installUrl/installations/new?state=$state")
                    }
                },
                onCheckInstallation = ::recheckGithubStatus,
            )

            TextButton(
                onClick = { openUrlExternally(context, SYNC_HELP_URL) },
                modifier = Modifier.padding(top = 16.dp),
            ) { Text("Learn more about syncing.") }

            backfillCandidates?.let { candidates ->
                GitHubBackfillDialog(
                    projects = candidates,
                    onFinished = { backfillCandidates = null },
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
    status: GitHubStatus,
    busy: Boolean,
    error: String?,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onFinishSetup: (String) -> Unit,
    onCheckInstallation: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text("GitHub")
            Text(
                when (status) {
                    GitHubStatus.NotConnected -> "Let Holder manage repositories."
                    is GitHubStatus.AuthorizationRequired -> "Your GitHub sign-in needs to be renewed."
                    is GitHubStatus.InstallationRequired -> "Signed in -- one more step is needed on GitHub."
                    is GitHubStatus.Connected -> "Connected as @${status.login}"
                },
            )
            error?.let { message -> Text(message, color = MaterialTheme.colorScheme.error) }
        }
        when {
            busy -> Row {
                CircularProgressIndicator(modifier = Modifier.padding(12.dp))
                TextButton(onClick = onCancel) { Text("Cancel sign-in") }
            }
            status is GitHubStatus.Connected -> TextButton(onClick = onDisconnect) { Text("Disconnect") }
            status is GitHubStatus.InstallationRequired -> Column {
                Button(onClick = { onFinishSetup(status.installUrl) }) { Text("Finish setup") }
                // Setup URL returns are only correlated hints. This authenticated check is
                // always available when the return was missing, stale, or opened elsewhere.
                TextButton(onClick = onCheckInstallation) { Text("Check installation") }
            }
            status is GitHubStatus.AuthorizationRequired -> Button(onClick = onConnect) { Text("Reconnect") }
            else -> Button(onClick = onConnect) { Text("Connect") }
        }
    }
    if (status is GitHubStatus.Connected) {
        TextButton(
            onClick = { onOpenUrl(status.installationSettingsUrl) },
            modifier = Modifier.padding(top = 4.dp),
        ) { Text("Manage repository access") }
    }
}
