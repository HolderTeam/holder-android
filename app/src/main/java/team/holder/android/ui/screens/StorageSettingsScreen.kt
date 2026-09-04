package team.holder.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import team.holder.android.resource.drive.GoogleDriveConnection
import team.holder.android.resource.s3.S3Connection
import team.holder.android.ui.openUrlExternally

// Mirrors BackupSettingsScreen's/SyncSettingsScreen's own help-link constants -- same
// website, same /android/<topic> shape.
private const val STORAGE_HELP_URL = "https://www.holder.team/android/storage"

/** Where attachments (photos, files) live -- Google Drive or S3-compatible storage. Not
 * project sync (see SyncSettingsScreen for git/GitHub): these providers store Resource/Asset
 * bytes, never card content itself. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Storage") },
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
            StorageProviderConnectionRow(
                title = "Google Drive",
                connectedSubtitle = driveConnectedAccountEmail?.let { "Connected as $it" } ?: "Connected",
                disconnectedSubtitle = "Simple cloud storage.",
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
                disconnectedSubtitle = "Your own bucket.",
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

            TextButton(
                onClick = { openUrlExternally(context, STORAGE_HELP_URL) },
                modifier = Modifier.padding(top = 16.dp),
            ) { Text("Learn more about storage.") }
        }
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
