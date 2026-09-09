package team.holder.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.holder.android.HolderCardLinks
import team.holder.android.HolderNative
import team.holder.android.HolderOutgoingLink
import team.holder.android.R
import team.holder.android.resource.AttachFlowConnectDialog
import team.holder.android.resource.openResourceExternally
import team.holder.android.resource.rememberAttachFlow
import team.holder.android.ui.CenteredMessage
import team.holder.android.ui.LoadState
import team.holder.android.ui.markdown.ResourceAttachmentKind
import team.holder.android.ui.markdown.ResourceImage
import team.holder.android.ui.markdown.ResourceImageViewerDialog
import team.holder.android.ui.markdown.rememberResourceAttachmentKind

/**
 * cardId's attached resources -- photos, files, anything attached to the card as a structural
 * record rather than referenced inline in its Markdown body (see AttachmentRow's doc comment
 * for that distinction). Reached from the Tools dashboard's Resources tile.
 *
 * Attaching happens here directly -- "attach to card" as a distinct operation from "insert a
 * reference into the body" -- via the Attach action, which opens a bottom sheet offering a
 * Photo (the curated system picker, no permission needed) or a File (any document, via SAF) so
 * neither loses out to the other. Both funnel into the same [rememberAttachFlow] the editor
 * uses, just discarding the Markdown reference it returns instead of inserting it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResourcesScreen(
    projectId: String,
    cardId: String,
    cardTitle: String,
    refreshKey: Any,
    onBack: () -> Unit,
) {
    var linksState by remember(cardId) { mutableStateOf<LoadState<HolderCardLinks>>(LoadState.Loading) }
    var viewerAttachment by remember { mutableStateOf<HolderOutgoingLink?>(null) }
    var showAttachSheet by remember { mutableStateOf(false) }
    // Bumped after a successful attach so the list below notices without leaving this screen;
    // MainActivity's shared refreshKey handles noticing from everywhere else instead.
    var localRefreshKey by remember(cardId) { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }

    val attachFlow = rememberAttachFlow(projectId, cardId) {
        // No Markdown reference to insert here -- importAsset already linked the card and
        // resource on its own; see attachPickedFile's doc comment.
        localRefreshKey++
    }
    LaunchedEffect(attachFlow.attachError) {
        attachFlow.attachError?.let { message -> snackbarHostState.showSnackbar("Couldn't attach: $message") }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) attachFlow.attach(uri)
    }
    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) attachFlow.attach(uri)
    }

    LaunchedEffect(cardId, refreshKey, localRefreshKey) {
        linksState = runCatching {
            withContext(Dispatchers.IO) { HolderNative.listCardLinks(cardId) }
        }.fold(
            onSuccess = { LoadState.Success(it) },
            onFailure = { LoadState.Error(it.message ?: it::class.java.simpleName) },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(cardTitle.ifEmpty { "Card" })
                        Text(
                            "Resources",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            // The only control on this screen, so it gets the FAB treatment (matching
            // CalendarScreen's own Add-milestone FAB) rather than a quieter app bar icon.
            FloatingActionButton(onClick = { if (!attachFlow.attaching) showAttachSheet = true }) {
                if (attachFlow.attaching) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Add, contentDescription = "Attach")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            when (val state = linksState) {
                is LoadState.Loading -> CenteredMessage(Modifier.fillMaxSize()) { CircularProgressIndicator() }
                is LoadState.Error ->
                    CenteredMessage(Modifier.fillMaxSize()) { Text("Failed to load resources: ${state.message}") }
                is LoadState.Success -> {
                    val attachments = state.value.outgoing.filter { it.toType == "resource" }
                    if (attachments.isEmpty()) {
                        CenteredMessage(Modifier.fillMaxSize()) {
                            Text(
                                "No resources yet",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.padding(innerPadding)) {
                            items(attachments, key = { "attachment:${it.toCardId}" }) { link ->
                                AttachmentRow(link = link, onOpenImage = { viewerAttachment = link })
                            }
                        }
                    }
                }
            }
        }
    }

    viewerAttachment?.let { link ->
        ResourceImageViewerDialog(
            resourceId = link.toCardId,
            altText = link.label ?: "Photo",
            onDismiss = { viewerAttachment = null },
        )
    }

    if (showAttachSheet) {
        ModalBottomSheet(onDismissRequest = { showAttachSheet = false }) {
            ListItem(
                headlineContent = { Text("Photo") },
                supportingContent = { Text("Pick from your photos") },
                leadingContent = {
                    Icon(painterResource(R.drawable.ic_attach_photo), contentDescription = null)
                },
                modifier = Modifier.clickable {
                    showAttachSheet = false
                    photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            )
            ListItem(
                headlineContent = { Text("File") },
                supportingContent = { Text("Pick any document") },
                leadingContent = {
                    Icon(painterResource(R.drawable.ic_file), contentDescription = null)
                },
                modifier = Modifier.clickable {
                    showAttachSheet = false
                    filePickerLauncher.launch(arrayOf("*/*"))
                },
            )
        }
    }

    AttachFlowConnectDialog(attachFlow)
}

/** A single resource row: an image thumbnail (tap opens the full-screen viewer, via
 * [onOpenImage]) or a generic file icon (tap opens externally, via
 * [team.holder.android.resource.openResourceExternally]) -- decided the same way
 * [team.holder.android.ui.markdown.ResourceAttachment] decides it for an inline body reference,
 * by the Resource's own recorded media type, not by guesswork. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AttachmentRow(link: HolderOutgoingLink, onOpenImage: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var openError by remember(link.toCardId) { mutableStateOf<String?>(null) }
    val displayName = link.label ?: "Attachment"
    val kind = rememberResourceAttachmentKind(link.toCardId, displayName)

    Column {
        ListItem(
            leadingContent = {
                if (kind is ResourceAttachmentKind.Image) {
                    ResourceImage(
                        resourceId = link.toCardId,
                        altText = displayName,
                        modifier = Modifier.size(48.dp).clip(MaterialTheme.shapes.small),
                    )
                } else {
                    Icon(
                        painterResource(R.drawable.ic_file),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp).padding(8.dp),
                    )
                }
            },
            headlineContent = { Text(displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            modifier = Modifier.combinedClickable(
                onClick = {
                    if (kind is ResourceAttachmentKind.Image) {
                        onOpenImage()
                    } else {
                        openError = null
                        scope.launch {
                            runCatching { openResourceExternally(context, link.toCardId) }
                                .onFailure { failure -> openError = failure.message ?: failure::class.java.simpleName }
                        }
                    }
                },
                onLongClick = {},
            ),
        )
        openError?.let { message ->
            Text(
                text = "Couldn't open \"$displayName\": $message",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
        }
    }
}
