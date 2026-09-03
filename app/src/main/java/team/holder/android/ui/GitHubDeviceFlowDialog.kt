package team.holder.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import team.holder.android.git.github.DeviceAuthorization

/**
 * Shown while a [team.holder.android.git.github.GitHubConnection.connect] call is in
 * flight: the code to enter at github.com, an "Open GitHub" button, and a waiting
 * indicator -- [team.holder.android.git.github.GitHubConnection.connect] keeps polling in
 * the background until the user approves, so this dialog is purely presentational. Shared
 * between Settings' "Connect GitHub" row and `RecoverProjectScreen`'s device-registration
 * flow (see GITHUB_INTEGRATION_ANDROID_PLAN.md's wiring points 1 and 3) rather than
 * duplicated in both.
 *
 * [onCancel] is expected to also cancel the coroutine actually running `connect` (the
 * caller owns that `Job`, not this dialog) -- dismissing this dialog without doing so
 * would leave a Device Flow poll running invisibly in the background.
 *
 * GitHub's Device Flow response has no `verification_uri_complete` (a code-pre-filled
 * URL) -- confirmed with a real request, not assumed -- so there's no way to skip manual
 * code entry on GitHub's own page. The one thing this dialog *can* do about that: copy the
 * code to the clipboard the moment "Open GitHub" is tapped, so a single-phone user who has
 * to switch away from Holder to approve can paste rather than having to remember/retype it.
 */
@Composable
fun GitHubDeviceFlowDialog(authorization: DeviceAuthorization, onCancel: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Connect to GitHub") },
        text = {
            Column {
                Text(
                    "Tap \"Open GitHub\" -- this code is copied for you, so you can just paste " +
                        "it in. Approve access, then come back here; Holder continues " +
                        "automatically.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    authorization.userCode,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text("Waiting for you to approve in the browser...", modifier = Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(authorization.userCode))
                    openUrlExternally(context, authorization.verificationUri)
                },
            ) { Text("Open GitHub") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        },
    )
}
