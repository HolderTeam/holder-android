package team.holder.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.holder.android.HolderNative
import team.holder.android.HolderSettings
import team.holder.android.splitLeadingHeading
import team.holder.android.ui.CenteredMessage
import team.holder.android.ui.LoadState
import team.holder.android.ui.markdown.HolderMarkdownViewer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardViewScreen(
    cardId: String,
    projectId: String,
    cardTitle: String,
    refreshKey: Any,
    onEdit: (content: String) -> Unit,
    onNavigateToCard: (cardId: String, title: String) -> Unit,
    onNavigateToTag: (tag: String) -> Unit,
    onConnectionsClick: () -> Unit,
    onCreateChildCard: () -> Unit,
    onDeleted: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val separateTitle by HolderSettings.separateTitleEnabled(context).collectAsState(initial = true)
    var state by remember(cardId, refreshKey) { mutableStateOf<LoadState<String>>(LoadState.Loading) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    // Guards delete against double-tap, same rationale as CardListScreen's isSubmitting.
    var isDeleting by remember { mutableStateOf(false) }
    var focusMode by remember { mutableStateOf(false) }

    // Back exits focus mode instead of leaving the card, mirroring a video player's fullscreen.
    BackHandler(enabled = focusMode) { focusMode = false }

    LaunchedEffect(cardId, refreshKey) {
        state = runCatching {
            withContext(Dispatchers.IO) { HolderNative.getCardContent(cardId) }
        }.fold(
            onSuccess = { LoadState.Success(it) },
            onFailure = { LoadState.Error(it.message ?: it::class.java.simpleName) },
        )
    }

    Scaffold(
        topBar = {
            if (!focusMode) {
                TopAppBar(
                    title = { Text(cardTitle.ifEmpty { "Card" }) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        val loaded = state as? LoadState.Success
                        IconButton(onClick = { focusMode = true }) {
                            Icon(Icons.Filled.Fullscreen, contentDescription = "Focus mode")
                        }
                        IconButton(onClick = onConnectionsClick) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Connections")
                        }
                        IconButton(
                            onClick = { showDeleteDialog = true },
                            enabled = loaded != null,
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                        IconButton(
                            onClick = { loaded?.let { onEdit(it.value) } },
                            enabled = loaded != null,
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit")
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!focusMode) {
                FloatingActionButton(onClick = onCreateChildCard) {
                    Icon(Icons.Filled.Add, contentDescription = "New child card")
                }
            }
        },
    ) { innerPadding ->
        when (val current = state) {
            is LoadState.Loading -> CenteredMessage(Modifier.padding(innerPadding)) {
                CircularProgressIndicator()
            }
            is LoadState.Error -> CenteredMessage(Modifier.padding(innerPadding)) {
                Text("Failed to load card: ${current.message}")
            }
            is LoadState.Success -> {
                // Focus mode behaves as if separate-title were off, so the title stays visible
                // in the content even though the app bar (which would normally show it) is gone.
                val displayed = if (separateTitle && !focusMode) {
                    splitLeadingHeading(current.value) ?: current.value
                } else {
                    current.value
                }
                HolderMarkdownViewer(
                    markdown = displayed,
                    projectId = projectId,
                    cardId = cardId,
                    onNavigateToCard = onNavigateToCard,
                    onNavigateToTag = onNavigateToTag,
                    modifier = Modifier
                        .padding(innerPadding)
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { if (!isDeleting) showDeleteDialog = false },
            title = { Text("Delete \"${cardTitle.ifEmpty { "this card" }}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    if (!isDeleting) {
                        isDeleting = true
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { HolderNative.deleteCard(cardId) } }
                            isDeleting = false
                            showDeleteDialog = false
                            onDeleted()
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(enabled = !isDeleting, onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }
}
