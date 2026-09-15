package team.holder.android.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import team.holder.android.HolderSettings
import team.holder.android.R
import team.holder.android.combineTitleAndBody
import team.holder.android.resource.AttachFlowConnectDialog
import team.holder.android.resource.createCameraCaptureUri
import team.holder.android.resource.rememberAttachFlow
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
    // Non-null only when this screen was reached by tapping a specific spot in the rendered
    // body (see HolderMarkdownViewer's onRequestEditAt / click_to_edit_position.md) -- null for
    // every other path in (the bottom-bar Edit button, long-press-anywhere, a fresh "new card"),
    // which keep today's behavior of landing at the end of the body.
    initialCursorOffset: Int? = null,
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
    val initialFirstLineBody = remember(initialContent) { initialContent.ifBlank { "# Untitled\n\n" } }

    // initialCursorOffset arrives measured against initialContent itself (see
    // HolderMarkdownViewer's onRequestEditAt / CardViewScreen's translation of a tap into that
    // coordinate space) -- separateBodyState needs it re-based onto initialSeparateBody, which is
    // initialContent with its leading "# Title" heading (and the blank line after it) already
    // stripped off the front. firstLineBodyState shows initialContent verbatim (short of the
    // blank-card fallback below, which a tap could never have produced an offset against in the
    // first place), so it takes the same offset unchanged. Both coerce into range as cheap
    // insurance against a stale offset from a body that's changed length since the tap, not a
    // case expected to actually happen in the normal flow.
    val headingPrefixLength = initialContent.length - initialSeparateBody.length
    val separateBodyState = rememberTextFieldState(
        initialSeparateBody,
        TextRange(
            (initialCursorOffset?.minus(headingPrefixLength) ?: initialSeparateBody.length)
                .coerceIn(0, initialSeparateBody.length),
        ),
    )
    val firstLineBodyState = rememberTextFieldState(
        initialFirstLineBody,
        TextRange((initialCursorOffset ?: initialFirstLineBody.length).coerceIn(0, initialFirstLineBody.length)),
    )

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

    // Attaching needs a real, already-persisted card -- cardId is null on the "new card" screen,
    // which hides the attach button entirely (see photoPickerLauncher below), so an empty
    // fallback here is never actually exercised.
    val attachFlow = rememberAttachFlow(projectId, cardId.orEmpty()) { markdown ->
        insertOwnLine(activeBodyState, markdown)
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null || cardId == null) return@rememberLauncherForActivityResult
        attachFlow.attach(uri)
    }

    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) pendingCameraUri?.let { attachFlow.attach(it) }
        pendingCameraUri = null
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
                    onAttachPhoto = if (cardId != null && !attachFlow.attaching) {
                        { photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    } else {
                        null
                    },
                    onAttachCamera = if (cardId != null && !attachFlow.attaching) {
                        {
                            val uri = createCameraCaptureUri(context)
                            pendingCameraUri = uri
                            cameraLauncher.launch(uri)
                        }
                    } else {
                        null
                    },
                    attaching = attachFlow.attaching,
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
            if (attachFlow.attachError != null) {
                Text(
                    text = "Couldn't attach photo: ${attachFlow.attachError}",
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
                    autoFocus = true,
                )
            } else {
                HolderMarkdownEditor(
                    state = firstLineBodyState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    autoFocus = true,
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

    AttachFlowConnectDialog(attachFlow)
}

/** Inserts [text] as its own CommonMark paragraph -- a full blank line before it (unless the
 * cursor is at the very start of the document) and after it, mirroring holder-desktop's
 * MarkdownResourceImageController.block_insertion for the same
 * `![label](holder://resource/<id>)` references, so a card edited on either platform ends up
 * with the same shape around an attached image. A single newline is only a *soft line break*
 * within the same paragraph as whatever precedes it -- inserting with just one, as this used
 * to, merges the reference into an adjacent line of text, which silently drops image
 * rendering entirely: HolderMarkdownViewer only renders `![...](holder://resource/...)` as an
 * image when it is the sole child of its own paragraph, so a merged paragraph falls back to
 * showing just the image's alt text as plain words. */
@OptIn(ExperimentalFoundationApi::class)
private fun insertOwnLine(state: TextFieldState, text: String) {
    state.edit {
        val cursor = selection.start
        val content = asCharSequence()
        val before = content.subSequence(0, cursor).toString()
        val leading = when {
            before.isEmpty() -> ""
            before.endsWith("\n\n") -> ""
            before.endsWith("\n") -> "\n"
            else -> "\n\n"
        }
        val insertion = "$leading$text\n\n"
        replace(cursor, cursor, insertion)
        placeCursorBeforeCharAt(cursor + insertion.length)
    }
}
