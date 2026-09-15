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
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.holder.android.HolderNative
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
    // What an otherwise-untitled card falls back to: "Untitled" normally, or
    // "Untitled child of {parent title}" when this screen was reached via a "Create child card"
    // action -- MainActivity decides which, this screen just shows/uses whatever it's given.
    // Never blank itself.
    defaultTitle: String = "Untitled",
    // Attaching a photo needs a real, already-persisted card to attach to -- cardId is null
    // for the "new card" screen (see MainActivity's "projects/{projectId}/cards/new" route),
    // which hides the attach button entirely rather than offering something that would fail.
    projectId: String = "",
    cardId: String? = null,
    onSave: (title: String, content: String) -> Unit,
    onCancel: () -> Unit,
    // Only ever called for an existing card (cardId != null) confirmed-deleted from the
    // "Delete this empty card?" dialog below -- unlike onCancel, this needs to pop back past the
    // now-nonexistent card's own view, not just one level. Never called for a "new card" screen,
    // so the default no-op is never actually exercised there.
    onDeleted: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
    val initialFirstLineBody = remember(initialContent) { initialContent.ifBlank { "# $defaultTitle\n\n" } }

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

    // Falls all the way back to defaultTitle (never blank) rather than gating Save on a
    // non-blank title at all -- a card with real content shouldn't be unsaveable just because
    // its Title field (Separate mode) was left empty. The middle step (the body's first line)
    // is exactly what First line mode already does unconditionally, reused here as the same
    // "something reasonable" a person would expect ("buy milk" typed straight into the body is
    // a perfectly good title on its own).
    val derivedTitle = if (separateTitle) {
        titleState.text.toString().ifBlank {
            titleFromFirstLine(separateBodyState.text.toString()).ifBlank { defaultTitle }
        }
    } else {
        titleFromFirstLine(firstLineBodyState.text.toString()).ifBlank { defaultTitle }
    }

    // Title and body both empty (Separate mode) or the one body field empty (First line mode)
    // -- nothing a fallback title alone can paper over, since there's no content either.
    // Handled by the two dialogs below instead of a plain save.
    val isCompletelyBlank = if (separateTitle) {
        titleState.text.isBlank() && separateBodyState.text.isBlank()
    } else {
        firstLineBodyState.text.isBlank()
    }

    val isDirty = if (separateTitle) {
        titleState.text.toString() != initialTitle || separateBodyState.text.toString() != initialSeparateBody
    } else {
        firstLineBodyState.text.toString() != initialFirstLineBody
    }
    var showDiscardDialog by remember { mutableStateOf(false) }
    val requestCancel = { if (isDirty) showDiscardDialog = true else onCancel() }

    // The actual write -- always produces a real, non-blank title (derivedTitle) and, for a
    // completely blank First line body, synthesizes the same "# {title}\n\n" shape
    // combineTitleAndBody already gives Separate mode, rather than persisting empty content
    // with nowhere for that title to live.
    val performSave = {
        hasSubmitted = true
        val rawContent = if (separateTitle) {
            combineTitleAndBody(derivedTitle, separateBodyState.text.toString())
        } else {
            firstLineBodyState.text.toString().ifBlank { "# $derivedTitle\n\n" }
        }
        val content = trimTrailingWhitespaceForSave(
            rawContent,
            preserve = preserveTrailingWhitespace,
            trimTwoSpaceLineEndings = trimTwoSpaceLineEndings,
            trimWhitespaceInCodeBlocks = trimWhitespaceInCodeBlocks,
        )
        onSave(derivedTitle, content)
    }

    var showCreateEmptyDialog by remember { mutableStateOf(false) }
    var showDeleteEmptyDialog by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }

    // Shared by the app bar's checkmark and the "Save" option on the discard-changes dialog --
    // hasSubmitted's own doc comment (its double-tap guard) applies identically either way.
    // Save is never simply blocked: a completely blank card routes to one of the two dialogs
    // below instead of silently doing nothing.
    val save = {
        if (!hasSubmitted) {
            if (isCompletelyBlank) {
                if (cardId == null) showCreateEmptyDialog = true else showDeleteEmptyDialog = true
            } else {
                performSave()
            }
        }
    }

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
                        IconButton(onClick = save) {
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
                    // Previews exactly what leaving this field blank will actually produce --
                    // the same fallback chain derivedTitle itself uses -- rather than a generic
                    // hint, so there's never a surprise between what's shown here and what gets
                    // saved.
                    placeholder = {
                        Text(titleFromFirstLine(separateBodyState.text.toString()).ifBlank { defaultTitle })
                    },
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
            title = { Text("Save changes?") },
            // All three in one row here, in this specific order (Save, Keep editing, Discard),
            // rather than split across confirmButton/dismissButton -- that slot pair only ever
            // renders two, and "Save" leaving without a trip back into the checkmark button is
            // the whole point of adding it.
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        showDiscardDialog = false
                        save()
                    }) { Text("Save") }
                    TextButton(onClick = { showDiscardDialog = false }) { Text("Keep editing") }
                    TextButton(onClick = {
                        showDiscardDialog = false
                        onCancel()
                    }) { Text("Discard") }
                }
            },
        )
    }

    // Fresh "new card" screen, still completely blank -- offers a placeholder instead of just
    // silently refusing to save (or silently creating a pointless empty card without asking).
    if (showCreateEmptyDialog) {
        AlertDialog(
            onDismissRequest = { showCreateEmptyDialog = false },
            title = { Text("Create placeholder?") },
            text = { Text("This empty card has no title or content") },
            confirmButton = {
                TextButton(onClick = {
                    showCreateEmptyDialog = false
                    performSave()
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCreateEmptyDialog = false
                    onCancel()
                }) { Text("Discard") }
            },
        )
    }

    // An existing card wiped down to nothing -- almost always means "get rid of this", so this
    // offers the real delete instead of quietly turning a once-real card into an empty
    // placeholder without asking.
    if (showDeleteEmptyDialog) {
        AlertDialog(
            onDismissRequest = { if (!isDeleting) showDeleteEmptyDialog = false },
            title = { Text("Delete this empty card?") },
            text = { Text("You have cleared the title and content.") },
            confirmButton = {
                TextButton(
                    enabled = !isDeleting,
                    onClick = {
                        if (!isDeleting && cardId != null) {
                            isDeleting = true
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { HolderNative.deleteCard(cardId) } }
                                withContext(Dispatchers.Main.immediate) {
                                    isDeleting = false
                                    showDeleteEmptyDialog = false
                                    onDeleted()
                                }
                            }
                        }
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(
                    enabled = !isDeleting,
                    onClick = {
                        showDeleteEmptyDialog = false
                        performSave()
                    },
                ) { Text("Keep") }
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
