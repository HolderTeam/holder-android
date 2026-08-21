package team.holder.android.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.holder.android.HolderBacklink
import team.holder.android.HolderCardLinks
import team.holder.android.HolderNative
import team.holder.android.HolderOutgoingLink
import team.holder.android.ui.CenteredMessage
import team.holder.android.ui.LoadState

/**
 * Shows cardId's explicit connections (front-matter "links", not hierarchy or inline
 * [[wikilinks]]) with add/remove for outgoing connections. Backlinks are read-only here since
 * removing one means editing the *other* card's front matter, not this one's.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConnectionsScreen(
    cardId: String,
    refreshKey: Any,
    onAddConnection: () -> Unit,
    onNavigateToCard: (cardId: String, title: String) -> Unit,
    onBack: () -> Unit,
) {
    var linksState by remember(cardId) { mutableStateOf<LoadState<HolderCardLinks>>(LoadState.Loading) }
    var menuOpenFor by remember { mutableStateOf<String?>(null) }
    var pendingRemove by remember { mutableStateOf<HolderOutgoingLink?>(null) }
    // Guards remove against double-tap, same rationale as other screens' isSubmitting.
    var isSubmitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        linksState = runCatching {
            withContext(Dispatchers.IO) { HolderNative.listCardLinks(cardId) }
        }.fold(
            onSuccess = { LoadState.Success(it) },
            onFailure = { LoadState.Error(it.message ?: it::class.java.simpleName) },
        )
    }

    LaunchedEffect(cardId, refreshKey) { refresh() }

    fun remove(link: HolderOutgoingLink) {
        if (isSubmitting) return
        isSubmitting = true
        pendingRemove = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { HolderNative.removeCardLink(cardId, link.toCardId, link.kind) }
            }
            isSubmitting = false
            refresh()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connections") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddConnection) {
                Icon(Icons.Filled.Add, contentDescription = "Add connection")
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            when (val state = linksState) {
                is LoadState.Loading -> CenteredMessage(Modifier.fillMaxSize()) { CircularProgressIndicator() }
                is LoadState.Error ->
                    CenteredMessage(Modifier.fillMaxSize()) { Text("Failed to load connections: ${state.message}") }
                is LoadState.Success -> {
                    val links = state.value
                    if (links.outgoing.isEmpty() && links.backlinks.isEmpty()) {
                        CenteredMessage(Modifier.fillMaxSize()) { Text("No connections yet") }
                    } else {
                        LazyColumn(modifier = Modifier.padding(innerPadding)) {
                            if (links.outgoing.isNotEmpty()) {
                                item { SectionHeader("Outgoing") }
                                items(links.outgoing, key = { "out:${it.toCardId}:${it.kind}" }) { link ->
                                    Box {
                                        ListItem(
                                            headlineContent = { Text(link.toTitle ?: link.toCardId) },
                                            supportingContent = { Text(connectionSubtitle(link.kind, link.label)) },
                                            modifier = Modifier.combinedClickable(
                                                onClick = { onNavigateToCard(link.toCardId, link.toTitle ?: "") },
                                                onLongClick = { menuOpenFor = "${link.toCardId}:${link.kind}" },
                                            ),
                                        )
                                        DropdownMenu(
                                            expanded = menuOpenFor == "${link.toCardId}:${link.kind}",
                                            onDismissRequest = { menuOpenFor = null },
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Remove") },
                                                onClick = {
                                                    menuOpenFor = null
                                                    pendingRemove = link
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                            if (links.backlinks.isNotEmpty()) {
                                item { SectionHeader("Backlinks") }
                                items(links.backlinks, key = { "back:${it.fromCardId}:${it.kind}" }) { link ->
                                    ListItem(
                                        headlineContent = { Text(link.fromTitle ?: link.fromCardId) },
                                        supportingContent = { Text(connectionSubtitle(link.kind, link.label)) },
                                        modifier = Modifier.combinedClickable(
                                            onClick = { onNavigateToCard(link.fromCardId, link.fromTitle ?: "") },
                                            onLongClick = {},
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingRemove?.let { link ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("Remove connection to \"${link.toTitle ?: link.toCardId}\"?") },
            confirmButton = {
                TextButton(onClick = { remove(link) }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Column(modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

private fun connectionSubtitle(kind: String, label: String?): String =
    if (label.isNullOrBlank()) kind else "$kind — $label"
