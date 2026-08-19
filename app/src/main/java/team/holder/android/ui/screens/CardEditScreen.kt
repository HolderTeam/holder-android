package team.holder.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import team.holder.android.HolderSettings
import team.holder.android.combineTitleAndBody
import team.holder.android.splitLeadingHeading
import team.holder.android.titleFromFirstLine
import team.holder.android.ui.markdown.HolderMarkdownEditor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardEditScreen(
    screenTitle: String,
    initialTitle: String,
    initialContent: String,
    saving: Boolean,
    errorMessage: String? = null,
    onSave: (title: String, content: String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val separateTitle by HolderSettings.separateTitleEnabled(context).collectAsState(initial = true)

    // Two independent field states, one per mode -- only one is ever shown, but both are
    // deterministically derived from initialContent regardless of which value `separateTitle`
    // resolves to (it loads asynchronously from DataStore), so whichever ends up displayed is
    // already correctly initialized. See splitLeadingHeading/combineTitleAndBody: cards always
    // store the title as a leading `# Title` heading, so switching modes is non-destructive.
    var title by remember { mutableStateOf(initialTitle) }
    val initialSeparateBody = remember(initialContent) { splitLeadingHeading(initialContent) ?: initialContent }
    val separateBodyState = rememberTextFieldState(initialSeparateBody)
    val initialFirstLineBody = remember(initialContent) { initialContent.ifBlank { "# Untitled\n\n" } }
    val firstLineBodyState = rememberTextFieldState(initialFirstLineBody)

    // One-shot guard, local to this screen instance. `saving` isn't enough: it resets to
    // false as soon as the save completes (createCard/updateCard can finish in well under
    // 100ms), which is exactly what a later, independent save needs -- but it means a second
    // tap that reaches this same composed button *after* the first save already finished
    // (e.g. while the pop-back-stack transition is still settling) reads `saving == false`
    // and is treated as a legitimate new save. hasSubmitted never resets once tripped, so a
    // second tap on this specific instance is always a no-op, regardless of timing. It's
    // reset on failure so the user can retry.
    var hasSubmitted by remember { mutableStateOf(false) }
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) hasSubmitted = false
    }

    val derivedTitle = if (separateTitle) title else titleFromFirstLine(firstLineBodyState.text.toString())

    val isDirty = if (separateTitle) {
        title != initialTitle || separateBodyState.text.toString() != initialSeparateBody
    } else {
        firstLineBodyState.text.toString() != initialFirstLineBody
    }
    var showDiscardDialog by remember { mutableStateOf(false) }
    val requestCancel = { if (isDirty) showDiscardDialog = true else onCancel() }

    BackHandler(onBack = requestCancel)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(screenTitle) },
                navigationIcon = {
                    IconButton(onClick = requestCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
                    }
                },
                actions = {
                    if (saving) {
                        CircularProgressIndicator(modifier = Modifier.padding(12.dp))
                    } else {
                        IconButton(
                            onClick = {
                                if (!hasSubmitted) {
                                    hasSubmitted = true
                                    val content = if (separateTitle) {
                                        combineTitleAndBody(title, separateBodyState.text.toString())
                                    } else {
                                        firstLineBodyState.text.toString()
                                    }
                                    onSave(derivedTitle, content)
                                }
                            },
                            enabled = derivedTitle.isNotBlank(),
                        ) {
                            Icon(Icons.Filled.Check, contentDescription = "Save")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp)) {
            if (errorMessage != null) {
                Text(
                    text = "Failed to save: $errorMessage",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            if (separateTitle) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                HolderMarkdownEditor(
                    state = separateBodyState,
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp),
                )
            } else {
                HolderMarkdownEditor(
                    state = firstLineBodyState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard changes?") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    onCancel()
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("Keep editing") }
            },
        )
    }
}
