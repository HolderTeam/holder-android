package team.holder.android.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import team.holder.android.HolderSettings
import team.holder.android.R
import team.holder.android.combineTitleAndBody
import team.holder.android.resource.attachPickedPhoto
import team.holder.android.resource.drive.GoogleDriveAuthException
import team.holder.android.resource.drive.GoogleDriveConnection
import team.holder.android.splitLeadingHeading
import team.holder.android.titleFromFirstLine
import team.holder.android.ui.markdown.HolderMarkdownEditor
import team.holder.android.ui.markdown.MarkdownFormattingToolbar
import team.holder.android.ui.markdown.trimTrailingWhitespaceForSave

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CardEditScreen(
    screenTitle: String,
    initialTitle: String,
    initialContent: String,
    saving: Boolean,
    errorMessage: String? = null,
    // Attaching a photo needs a real, already-persisted card to attach to -- cardId is null
    // for the "new card" screen (see MainActivity's "projects/{projectId}/cards/new" route),
    // which hides the attach button entirely rather than offering something that would fail.
    projectId: String = "",
    cardId: String? = null,
    onSave: (title: String, content: String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val separateTitle by HolderSettings.separateTitleEnabled(context).collectAsState(initial = true)
    val preserveTrailingWhitespace by HolderSettings.preserveTrailingWhitespace(context).collectAsState(initial = false)
    val trimTwoSpaceLineEndings by HolderSettings.trimTwoSpaceLineEndings(context).collectAsState(initial = false)
    val trimWhitespaceInCodeBlocks by HolderSettings.trimWhitespaceInCodeBlocks(context).collectAsState(initial = false)

    // Two independent field states, one per mode -- only one is ever shown, but both are
    // deterministically derived from initialContent regardless of which value `separateTitle`
    // resolves to (it loads asynchronously from DataStore), so whichever ends up displayed is
    // already correctly initialized. See splitLeadingHeading/combineTitleAndBody: cards always
    // store the title as a leading `# Title` heading, so switching modes is non-destructive.
    // All three TextFieldStates survive process death via their own built-in Saver.
    val titleState = rememberTextFieldState(initialTitle)
    val initialSeparateBody = remember(initialContent) { splitLeadingHeading(initialContent) ?: initialContent }
    val separateBodyState = rememberTextFieldState(initialSeparateBody)
    val initialFirstLineBody = remember(initialContent) { initialContent.ifBlank { "# Untitled\n\n" } }
    val firstLineBodyState = rememberTextFieldState(initialFirstLineBody)

    // Undo/redo acts on whichever field last had focus (defaulting to the body, since that's
    // where most editing happens); each TextFieldState tracks its own history independently
    // via its built-in undoState.
    var titleFocused by remember { mutableStateOf(false) }
    val activeUndo = if (!separateTitle) {
        firstLineBodyState.undoState
    } else if (titleFocused) {
        titleState.undoState
    } else {
        separateBodyState.undoState
    }
    val activeBodyState = if (separateTitle) separateBodyState else firstLineBodyState

    val scope = rememberCoroutineScope()
    var attaching by remember { mutableStateOf(false) }
    var attachError by remember { mutableStateOf<String?>(null) }
    // Non-null exactly when the most recent attach attempt failed specifically because Drive
    // isn't connected -- holds onto the already-picked photo so a successful inline connect
    // (below) can retry the same attach without sending the user back through the picker.
    var pendingConnectUri by remember { mutableStateOf<Uri?>(null) }
    // Same StartIntentSenderForResult<->suspend bridge as SettingsScreen's Connect button --
    // see its comment for why a single slot is enough.
    var pendingConsent by remember { mutableStateOf<CompletableDeferred<ActivityResult>?>(null) }
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        pendingConsent?.complete(result)
        pendingConsent = null
    }

    suspend fun performAttach(uri: Uri) {
        if (cardId == null) return
        attachError = null
        val result = runCatching { attachPickedPhoto(context, projectId, cardId, uri) }
        result.fold(
            onSuccess = { markdown ->
                pendingConnectUri = null
                insertOwnLine(activeBodyState, markdown)
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

    val photoPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null || cardId == null) return@rememberLauncherForActivityResult
        attaching = true
        scope.launch {
            performAttach(uri)
            attaching = false
        }
    }

    val connectAndRetryAttach = {
        val uri = pendingConnectUri
        if (uri != null) {
            attaching = true
            scope.launch {
                runCatching {
                    GoogleDriveConnection.connect(context) { request ->
                        val deferred = CompletableDeferred<ActivityResult>()
                        pendingConsent = deferred
                        consentLauncher.launch(request)
                        deferred.await()
                    }
                }.fold(
                    onSuccess = { performAttach(uri) },
                    onFailure = { failure -> attachError = failure.message ?: "Could not connect to Google Drive" },
                )
                attaching = false
            }
        }
    }

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

    val derivedTitle = if (separateTitle) {
        titleState.text.toString()
    } else {
        titleFromFirstLine(firstLineBodyState.text.toString())
    }

    val isDirty = if (separateTitle) {
        titleState.text.toString() != initialTitle || separateBodyState.text.toString() != initialSeparateBody
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
                    IconButton(onClick = { activeUndo.undo() }, enabled = activeUndo.canUndo) {
                        Icon(painterResource(R.drawable.ic_undo), contentDescription = "Undo")
                    }
                    IconButton(onClick = { activeUndo.redo() }, enabled = activeUndo.canRedo) {
                        Icon(painterResource(R.drawable.ic_redo), contentDescription = "Redo")
                    }
                    if (saving) {
                        CircularProgressIndicator(modifier = Modifier.padding(12.dp))
                    } else {
                        IconButton(
                            onClick = {
                                if (!hasSubmitted) {
                                    hasSubmitted = true
                                    val rawContent = if (separateTitle) {
                                        combineTitleAndBody(titleState.text.toString(), separateBodyState.text.toString())
                                    } else {
                                        firstLineBodyState.text.toString()
                                    }
                                    val content = trimTrailingWhitespaceForSave(
                                        rawContent,
                                        preserve = preserveTrailingWhitespace,
                                        trimTwoSpaceLineEndings = trimTwoSpaceLineEndings,
                                        trimWhitespaceInCodeBlocks = trimWhitespaceInCodeBlocks,
                                    )
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
        bottomBar = {
            // Formatting only makes sense for the body -- hidden while the plain single-line
            // Title field has focus in Separate mode; always shown in First line mode, since
            // there's only the one field.
            if (!separateTitle || !titleFocused) {
                MarkdownFormattingToolbar(
                    state = activeBodyState,
                    modifier = Modifier.fillMaxWidth().imePadding(),
                    // Attaching needs a real, already-persisted card (see AssetImportService)
                    // -- null on the "new card" screen, which hides the button entirely.
                    onAttachPhoto = if (cardId != null && !attaching) {
                        { photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    } else {
                        null
                    },
                    attaching = attaching,
                )
            }
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
            if (attachError != null) {
                Text(
                    text = "Couldn't attach photo: $attachError",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            if (separateTitle) {
                OutlinedTextField(
                    state = titleState,
                    label = { Text("Title") },
                    lineLimits = TextFieldLineLimits.SingleLine,
                    modifier = Modifier.fillMaxWidth().onFocusChanged { titleFocused = it.isFocused },
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

    if (pendingConnectUri != null) {
        AlertDialog(
            onDismissRequest = { if (!attaching) pendingConnectUri = null },
            title = { Text("Connect Google Drive?") },
            text = { Text("Attaching a photo stores it in your own Google Drive.") },
            confirmButton = {
                TextButton(enabled = !attaching, onClick = connectAndRetryAttach) { Text("Connect") }
            },
            dismissButton = {
                TextButton(enabled = !attaching, onClick = { pendingConnectUri = null }) { Text("Cancel") }
            },
        )
    }
}

/** Inserts [text] at the cursor on its own line -- a blank line before it unless the cursor
 * is already at the start of a line, always a trailing newline -- mirroring
 * holder-desktop's MarkdownResourceImageController.block_insertion for the same
 * `![label](holder://resource/<id>)` references, so a card edited on either platform ends up
 * with the same shape around an attached image. */
@OptIn(ExperimentalFoundationApi::class)
private fun insertOwnLine(state: TextFieldState, text: String) {
    state.edit {
        val cursor = selection.start
        val content = asCharSequence()
        val atLineStart = cursor == 0 || content[cursor - 1] == '\n'
        val insertion = (if (atLineStart) "" else "\n") + text + "\n"
        replace(cursor, cursor, insertion)
        placeCursorBeforeCharAt(cursor + insertion.length)
    }
}
