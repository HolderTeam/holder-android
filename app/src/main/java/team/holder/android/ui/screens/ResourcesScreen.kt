package team.holder.android.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import team.holder.android.resource.openResourceExternally
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
 * Read-only for now: attaching a resource from here, independent of the editor -- "attach to
 * card" as a distinct operation from "insert a reference into the body" -- is a deliberate
 * follow-up, not yet built.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResourcesScreen(
    cardId: String,
    cardTitle: String,
    refreshKey: Any,
    onBack: () -> Unit,
) {
    var linksState by remember(cardId) { mutableStateOf<LoadState<HolderCardLinks>>(LoadState.Loading) }
    var viewerAttachment by remember { mutableStateOf<HolderOutgoingLink?>(null) }

    LaunchedEffect(cardId, refreshKey) {
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
