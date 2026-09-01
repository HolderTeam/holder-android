package team.holder.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.holder.android.HolderNative
import team.holder.android.RecoveryTokenImportGlobalResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoverProjectScreen(onBack: () -> Unit, initialToken: String? = null) {
    var pin by remember { mutableStateOf("") }
    // initialToken comes from opening a .hrk file directly (see MainActivity's ACTION_VIEW
    // handling) rather than pasting one by hand -- seeded once, same as every other field here;
    // still just an editable starting value, not a locked-in source of truth.
    var token by remember { mutableStateOf(initialToken.orEmpty()) }
    var isBusy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<RecoveryTokenImportGlobalResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recover Project") },
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
            Text(
                "Paste a recovery token exported from another device, along with its PIN, to " +
                    "recover that project here. If this device doesn't already have the " +
                    "project, it will be created.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it },
                label = { Text("PIN") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Recovery token") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Button(
                enabled = !isBusy && pin.isNotBlank() && token.isNotBlank(),
                modifier = Modifier.padding(top = 16.dp),
                onClick = {
                    if (!isBusy) {
                        isBusy = true
                        error = null
                        result = null
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    HolderNative.importRecoveryTokenGlobal(pin, token)
                                }
                            }.fold(
                                onSuccess = { result = it },
                                onFailure = { error = it.message ?: it::class.java.simpleName },
                            )
                            isBusy = false
                        }
                    }
                },
            ) { Text("Recover") }

            if (isBusy) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp))
            }
            result?.let { r ->
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    Text(
                        if (r.projectCreated) "Project recovered (newly created)." else "Project key re-imported.",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (r.remoteHintPresent) {
                        val remoteSummary = if (r.remoteConfigured) {
                            "configured"
                        } else {
                            "failed to configure" + (r.remoteError?.let { " -- $it" } ?: "")
                        }
                        Text("Remote: $remoteSummary")
                        Text("Pull: ${r.pullStatus}" + (r.pullError?.let { " -- $it" } ?: ""))
                    }
                }
            }
        }
    }
}
