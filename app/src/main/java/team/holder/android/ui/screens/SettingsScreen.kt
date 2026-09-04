package team.holder.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.holder.android.HolderProject
import team.holder.android.HolderSettings
import team.holder.android.git.backup.SnapshotWriter
import team.holder.android.git.github.DeviceAuthorization
import team.holder.android.git.github.GitHubBackfill
import team.holder.android.git.github.GitHubConnection
import team.holder.android.git.github.GitHubStatus
import team.holder.android.resource.drive.GoogleDriveConnection
import team.holder.android.resource.s3.S3Connection
import team.holder.android.sync.GitSyncScheduler
import team.holder.android.ui.GitHubBackfillDialog
import team.holder.android.ui.GitHubDeviceFlowDialog
import team.holder.android.ui.openUrlExternally
import team.holder.android.ui.theme.HolderFontFamilyOption
import team.holder.android.ui.theme.HolderFontSizeOption
import team.holder.android.ui.theme.HolderThemeOption
import team.holder.android.ui.theme.swatchColor

private val BACKGROUND_SYNC_INTERVAL_OPTIONS_MINUTES = listOf(15, 30, 60, 120)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onRestoreBackupClick: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val separateTitle by HolderSettings.separateTitleEnabled(context).collectAsState(initial = true)
    val backgroundSyncEnabled by HolderSettings.gitBackgroundSyncEnabled(context).collectAsState(initial = false)
    val backgroundSyncIntervalMinutes by HolderSettings.gitBackgroundSyncIntervalMinutes(context)
        .collectAsState(initial = HolderSettings.DEFAULT_BACKGROUND_SYNC_INTERVAL_MINUTES)
    var intervalMenuExpanded by remember { mutableStateOf(false) }
    var preparingBackup by remember { mutableStateOf(false) }
    var prepareBackupResult by remember { mutableStateOf<String?>(null) }
    val themeOption by HolderSettings.themeOption(context).collectAsState(initial = HolderThemeOption.SYSTEM)
    var themeMenuExpanded by remember { mutableStateOf(false) }
    val fontSizeOption by HolderSettings.fontSizeOption(context).collectAsState(initial = HolderFontSizeOption.SYSTEM)
    var fontSizeMenuExpanded by remember { mutableStateOf(false) }
    val fontFamilyOption by HolderSettings.fontFamilyOption(context)
        .collectAsState(initial = HolderFontFamilyOption.DEFAULT)
    var fontFamilyMenuExpanded by remember { mutableStateOf(false) }
    val preserveTrailingWhitespace by HolderSettings.preserveTrailingWhitespace(context).collectAsState(initial = false)
    val trimTwoSpaceLineEndings by HolderSettings.trimTwoSpaceLineEndings(context).collectAsState(initial = false)
    val trimWhitespaceInCodeBlocks by HolderSettings.trimWhitespaceInCodeBlocks(context).collectAsState(initial = false)
    val driveConnectedAccountEmail by GoogleDriveConnection.connectedAccountEmail(context).collectAsState(initial = null)
    // The folder id, not the email, is what actually determines "connected" -- it's always
    // set on a successful connect, where the email is best-effort (see GoogleDriveAuth's
    // EMAIL_SCOPE comment) and only ever used for display below.
    val driveFolderId by GoogleDriveConnection.folderId(context).collectAsState(initial = null)
    val driveConnected = driveFolderId != null
    var driveConnecting by remember { mutableStateOf(false) }
    var driveError by remember { mutableStateOf<String?>(null) }
    // Bridges StartIntentSenderForResult's fixed, registration-time callback into the single
    // suspend call GoogleDriveConnection.connect needs for the account/consent screen --
    // there's at most one Drive connect attempt in flight at a time, so one slot is enough.
    var pendingConsent by remember { mutableStateOf<CompletableDeferred<ActivityResult>?>(null) }
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        pendingConsent?.complete(result)
        pendingConsent = null
    }
    val s3ConnectedBucket by S3Connection.connectedBucket(context).collectAsState(initial = null)
    var s3Connecting by remember { mutableStateOf(false) }
    var s3Error by remember { mutableStateOf<String?>(null) }
    var s3ShowConnectDialog by remember { mutableStateOf(false) }
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
                title = { Text("Settings") },
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
                    Text("Theme")
                    Text(themeOption.description)
                }
                Box {
                    Button(onClick = { themeMenuExpanded = true }) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(themeOption.swatchColor()),
                        )
                        Text(themeOption.label, modifier = Modifier.padding(start = 8.dp))
                    }
                    DropdownMenu(
                        expanded = themeMenuExpanded,
                        onDismissRequest = { themeMenuExpanded = false },
                    ) {
                        HolderThemeOption.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                leadingIcon = {
                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clip(CircleShape)
                                            .background(option.swatchColor()),
                                    )
                                },
                                onClick = {
                                    themeMenuExpanded = false
                                    scope.launch { HolderSettings.setThemeOption(context, option) }
                                },
                            )
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Font size")
                    Text(
                        if (fontSizeOption == HolderFontSizeOption.SYSTEM) {
                            "Follows your device's font size setting."
                        } else {
                            "Overrides your device's font size setting."
                        },
                    )
                }
                Box {
                    Button(onClick = { fontSizeMenuExpanded = true }) {
                        Text(fontSizeOption.label)
                    }
                    DropdownMenu(
                        expanded = fontSizeMenuExpanded,
                        onDismissRequest = { fontSizeMenuExpanded = false },
                    ) {
                        HolderFontSizeOption.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    fontSizeMenuExpanded = false
                                    scope.launch { HolderSettings.setFontSizeOption(context, option) }
                                },
                            )
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Font")
                    Text("The typeface used throughout the app.")
                }
                Box {
                    Button(onClick = { fontFamilyMenuExpanded = true }) {
                        Text("Aa", fontFamily = fontFamilyOption.fontFamily)
                        Text(fontFamilyOption.label, modifier = Modifier.padding(start = 8.dp))
                    }
                    DropdownMenu(
                        expanded = fontFamilyMenuExpanded,
                        onDismissRequest = { fontFamilyMenuExpanded = false },
                    ) {
                        HolderFontFamilyOption.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                leadingIcon = { Text("Aa", fontFamily = option.fontFamily) },
                                onClick = {
                                    fontFamilyMenuExpanded = false
                                    scope.launch { HolderSettings.setFontFamilyOption(context, option) }
                                },
                            )
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

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

            Text("Backup")
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

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            StorageProviderConnectionRow(
                title = "Google Drive",
                connectedSubtitle = driveConnectedAccountEmail?.let { "Connected as $it" } ?: "Connected",
                disconnectedSubtitle = "Store photo attachments in your own Google Drive.",
                connected = driveConnected,
                connecting = driveConnecting,
                error = driveError,
                onConnect = {
                    driveError = null
                    driveConnecting = true
                    scope.launch {
                        runCatching {
                            GoogleDriveConnection.connect(context) { request ->
                                val deferred = CompletableDeferred<ActivityResult>()
                                pendingConsent = deferred
                                consentLauncher.launch(request)
                                deferred.await()
                            }
                        }.onFailure { failure ->
                            driveError = failure.message ?: "Could not connect to Google Drive"
                        }
                        driveConnecting = false
                    }
                },
                onDisconnect = { scope.launch { GoogleDriveConnection.disconnect(context) } },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            StorageProviderConnectionRow(
                title = "S3-compatible storage",
                connectedSubtitle = "Connected to ${s3ConnectedBucket.orEmpty()}",
                disconnectedSubtitle = "Store attachments in your own S3-compatible bucket (AWS S3, MinIO, ...).",
                connected = s3ConnectedBucket != null,
                connecting = s3Connecting,
                error = s3Error,
                onConnect = {
                    s3Error = null
                    s3ShowConnectDialog = true
                },
                onDisconnect = { scope.launch { S3Connection.disconnect(context) } },
            )

            if (s3ShowConnectDialog) {
                S3ConnectDialog(
                    connecting = s3Connecting,
                    error = s3Error,
                    onDismiss = { s3ShowConnectDialog = false },
                    onConnect = { endpoint, region, bucket, accessKeyId, secretAccessKey ->
                        s3Error = null
                        s3Connecting = true
                        scope.launch {
                            runCatching {
                                S3Connection.connect(context, endpoint, region, bucket, accessKeyId, secretAccessKey)
                            }.onSuccess {
                                s3ShowConnectDialog = false
                            }.onFailure { failure ->
                                s3Error = failure.message ?: "Could not connect to this S3 bucket"
                            }
                            s3Connecting = false
                        }
                    },
                )
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

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Separate title field")
                    Text(
                        text = if (separateTitle) {
                            "Cards have a distinct Title field, separate from the body."
                        } else {
                            "No Title field -- the first line of a card is its title."
                        },
                    )
                }
                Switch(
                    checked = separateTitle,
                    onCheckedChange = { enabled ->
                        scope.launch { HolderSettings.setSeparateTitleEnabled(context, enabled) }
                    },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Preserve trailing whitespace")
                    Text("Keeps spaces and tabs at the ends of lines.")
                }
                Switch(
                    checked = preserveTrailingWhitespace,
                    onCheckedChange = { enabled ->
                        scope.launch { HolderSettings.setPreserveTrailingWhitespace(context, enabled) }
                    },
                )
            }

            if (!preserveTrailingWhitespace) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Trim two-space hard breaks")
                        Text("Two invisible trailing spaces can force a Markdown line break.")
                    }
                    Switch(
                        checked = trimTwoSpaceLineEndings,
                        onCheckedChange = { enabled ->
                            scope.launch { HolderSettings.setTrimTwoSpaceLineEndings(context, enabled) }
                        },
                    )
                }

                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Trim trailing whitespace in code")
                        Text("Code blocks normally preserve their contents literally.")
                    }
                    Switch(
                        checked = trimWhitespaceInCodeBlocks,
                        onCheckedChange = { enabled ->
                            scope.launch { HolderSettings.setTrimWhitespaceInCodeBlocks(context, enabled) }
                        },
                    )
                }
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

/**
 * The connect/disconnect row shape shared by every connected storage provider's Settings
 * entry -- title, a connected-vs-disconnected subtitle, an inline error, and a
 * Connect/Disconnect/spinner control. Originally written once for Google Drive; factored out
 * here so a second provider (S3, WebDAV, ...) reuses the row instead of copy-pasting it. Each
 * provider still supplies its own [onConnect] -- what "connect" means (OAuth consent, a
 * pasted credential, ...) is provider-specific and stays that way. See
 * RESOURCE_STORAGE_ROADMAP.md's step 1.
 */
@Composable
private fun StorageProviderConnectionRow(
    title: String,
    connectedSubtitle: String,
    disconnectedSubtitle: String,
    connected: Boolean,
    connecting: Boolean,
    error: String?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title)
            Text(if (connected) connectedSubtitle else disconnectedSubtitle)
            error?.let { message -> Text(message, color = MaterialTheme.colorScheme.error) }
        }
        when {
            connecting -> CircularProgressIndicator(modifier = Modifier.padding(12.dp))
            connected -> TextButton(onClick = onDisconnect) { Text("Disconnect") }
            else -> Button(onClick = onConnect) { Text("Connect") }
        }
    }
}

/**
 * S3's connect flow, unlike Drive's, is manual entry rather than an OAuth consent screen --
 * see [S3Connection]'s doc comment for why (no QR-code desktop-aided handoff yet). Mirrors
 * desktop's own "Add S3-compatible Storage" dialog fields (`resources_tool_view.vala`):
 * endpoint, region, bucket, access key, secret key.
 */
@Composable
private fun S3ConnectDialog(
    connecting: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConnect: (endpoint: String, region: String, bucket: String, accessKeyId: String, secretAccessKey: String) -> Unit,
) {
    var endpoint by remember { mutableStateOf("") }
    var region by remember { mutableStateOf("") }
    var bucket by remember { mutableStateOf("") }
    var accessKeyId by remember { mutableStateOf("") }
    var secretAccessKey by remember { mutableStateOf("") }
    val canConnect = !connecting && endpoint.isNotBlank() && region.isNotBlank() && bucket.isNotBlank() &&
        accessKeyId.isNotBlank() && secretAccessKey.isNotBlank()

    AlertDialog(
        onDismissRequest = { if (!connecting) onDismiss() },
        title = { Text("Connect S3-compatible storage") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "The endpoint and bucket are shared through Git. Credentials stay on this device.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text("Endpoint") },
                    placeholder = { Text("https://s3.example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = region,
                    onValueChange = { region = it },
                    label = { Text("Region") },
                    placeholder = { Text("us-east-1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = bucket,
                    onValueChange = { bucket = it },
                    label = { Text("Bucket") },
                    placeholder = { Text("holder-family-assets") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = accessKeyId,
                    onValueChange = { accessKeyId = it },
                    label = { Text("Access key ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = secretAccessKey,
                    onValueChange = { secretAccessKey = it },
                    label = { Text("Secret access key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (connecting) {
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text("Checking connection...", modifier = Modifier.padding(start = 8.dp))
                    }
                }
                error?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConnect,
                onClick = { onConnect(endpoint, region, bucket, accessKeyId, secretAccessKey) },
            ) { Text("Connect") }
        },
        dismissButton = {
            TextButton(enabled = !connecting, onClick = onDismiss) { Text("Cancel") }
        },
    )
}
