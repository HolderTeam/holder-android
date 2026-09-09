package team.holder.android.resource

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import team.holder.android.resource.drive.GoogleDriveAuthException
import team.holder.android.resource.drive.GoogleDriveConnection

/**
 * Holds the state behind attaching a picked file to a card -- shared by [team.holder.android.ui.screens.CardEditScreen]
 * (which inserts the returned Markdown reference into the body) and
 * [team.holder.android.ui.screens.ResourcesScreen] (which discards it, since attaching a
 * structural link is the whole point there; see [attachPickedFile]'s doc comment). Both need
 * the same Drive-not-connected recovery: stash the picked [Uri], prompt to connect, and retry
 * the same attach on success rather than sending the user back through the picker.
 */
class AttachFlowState internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val projectId: String,
    private val cardId: String,
    private val requestConsent: suspend (IntentSenderRequest) -> ActivityResult,
    private val onAttached: (markdown: String) -> Unit,
) {
    var attaching by mutableStateOf(false)
        private set
    var attachError by mutableStateOf<String?>(null)
        private set
    var pendingConnectUri by mutableStateOf<Uri?>(null)
        private set

    private suspend fun performAttach(uri: Uri) {
        attachError = null
        val result = runCatching { attachPickedFile(context, projectId, cardId, uri) }
        result.fold(
            onSuccess = { markdown ->
                pendingConnectUri = null
                onAttached(markdown)
            },
            onFailure = { failure ->
                if (failure is GoogleDriveAuthException) {
                    pendingConnectUri = uri
                } else {
                    attachError = failure.message ?: failure::class.java.simpleName
                }
            },
        )
    }

    fun attach(uri: Uri) {
        attaching = true
        scope.launch {
            performAttach(uri)
            attaching = false
        }
    }

    fun connectAndRetry() {
        val uri = pendingConnectUri ?: return
        attaching = true
        scope.launch {
            runCatching { GoogleDriveConnection.connect(context, requestConsent) }.fold(
                onSuccess = { performAttach(uri) },
                onFailure = { failure -> attachError = failure.message ?: "Could not connect to Google Drive" },
            )
            attaching = false
        }
    }

    fun dismissConnectPrompt() {
        if (!attaching) pendingConnectUri = null
    }
}

@Composable
fun rememberAttachFlow(
    projectId: String,
    cardId: String,
    onAttached: (markdown: String) -> Unit,
): AttachFlowState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingConsent by remember { mutableStateOf<CompletableDeferred<ActivityResult>?>(null) }
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        pendingConsent?.complete(result)
        pendingConsent = null
    }
    return remember(projectId, cardId) {
        AttachFlowState(
            context = context,
            scope = scope,
            projectId = projectId,
            cardId = cardId,
            requestConsent = { request ->
                val deferred = CompletableDeferred<ActivityResult>()
                pendingConsent = deferred
                consentLauncher.launch(request)
                deferred.await()
            },
            onAttached = onAttached,
        )
    }
}

/** The "Connect Google Drive?" prompt for [state], shown whenever an attach attempt fails
 * because Drive isn't connected yet. A no-op composable when there's nothing pending. */
@Composable
fun AttachFlowConnectDialog(state: AttachFlowState) {
    if (state.pendingConnectUri != null) {
        AlertDialog(
            onDismissRequest = { state.dismissConnectPrompt() },
            title = { Text("Connect Google Drive?") },
            text = { Text("Attaching a file stores it in your own Google Drive.") },
            confirmButton = {
                TextButton(enabled = !state.attaching, onClick = state::connectAndRetry) { Text("Connect") }
            },
            dismissButton = {
                TextButton(enabled = !state.attaching, onClick = state::dismissConnectPrompt) { Text("Cancel") }
            },
        )
    }
}
