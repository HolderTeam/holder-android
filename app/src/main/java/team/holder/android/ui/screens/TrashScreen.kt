package team.holder.android.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.holder.android.HolderCard
import team.holder.android.HolderNative
import team.holder.android.ui.CenteredMessage
import team.holder.android.ui.LoadState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DELETED_AT_FORMAT = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault())

private fun formatDeletedAt(epochSeconds: Long?): String? =
    epochSeconds?.let { "Deleted ${DELETED_AT_FORMAT.format(Instant.ofEpochSecond(it))}" }

/**
 * Lists project_id's trashed cards (holder_card_delete moves them here instead of removing them
 * outright) with per-card restore/permanent-delete actions, mirroring desktop Holder's trash tool
 * (same "Restore"/"Delete Permanently"/"Empty Trash" terminology) minus the AI-message rows and
 * type filter desktop has, since Android doesn't sync AI threads yet.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TrashScreen(
    projectId: String,
    onBack: () -> Unit,
) {
    var cardsState by remember(projectId) { mutableStateOf<LoadState<List<HolderCard>>>(LoadState.Loading) }
    var menuOpenFor by remember { mutableStateOf<String?>(null) }
    var cardPendingDelete by remember { mutableStateOf<HolderCard?>(null) }
    var emptyTrashPending by remember { mutableStateOf(false) }
    // Guards restore/delete against double-tap: a second tap can reach the same button or dialog
    // action before recomposition clears it, re-running the request.
    var isSubmitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        cardsState = runCatching {
            withContext(Dispatchers.IO) { HolderNative.listTrashedCards(projectId) }
        }.fold(
            onSuccess = { LoadState.Success(it) },
            onFailure = { LoadState.Error(it.message ?: it::class.java.simpleName) },
        )
    }

    LaunchedEffect(projectId) { refresh() }

    fun restore(card: HolderCard) {
        if (isSubmitting) return
        isSubmitting = true
        menuOpenFor = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { HolderNative.restoreCard(card.cardId) } }
            isSubmitting = false
            refresh()
        }
    }

    fun deletePermanently(card: HolderCard) {
        if (isSubmitting) return
        isSubmitting = true
        cardPendingDelete = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { HolderNative.purgeCard(card.cardId) } }
            isSubmitting = false
            refresh()
        }
    }

    fun emptyTrash() {
        if (isSubmitting) return
        isSubmitting = true
        emptyTrashPending = false
        val current = (cardsState as? LoadState.Success)?.value.orEmpty()
        scope.launch {
            withContext(Dispatchers.IO) {
                for (card in current) {
                    runCatching { HolderNative.purgeCard(card.cardId) }
                }
            }
            isSubmitting = false
            refresh()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trash") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    val hasItems = (cardsState as? LoadState.Success)?.value?.isNotEmpty() == true
                    if (hasItems) {
                        IconButton(onClick = { emptyTrashPending = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Empty trash")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            when (val state = cardsState) {
                is LoadState.Loading -> CenteredMessage(Modifier.fillMaxSize()) { CircularProgressIndicator() }
                is LoadState.Error ->
                    CenteredMessage(Modifier.fillMaxSize()) { Text("Failed to load trash: ${state.message}") }
                is LoadState.Success -> {
                    if (state.value.isEmpty()) {
                        CenteredMessage(Modifier.fillMaxSize()) { Text("No deleted cards in this project") }
                    } else {
                        LazyColumn(modifier = Modifier.padding(innerPadding)) {
                            items(state.value, key = { it.cardId }) { card ->
                                Box {
                                    ListItem(
                                        headlineContent = { Text(card.title) },
                                        supportingContent = formatDeletedAt(card.deletedAt)?.let { { Text(it) } },
                                        modifier = Modifier.combinedClickable(
                                            onClick = {},
                                            onLongClick = { menuOpenFor = card.cardId },
                                        ),
                                        trailingContent = {
                                            TextButton(onClick = { restore(card) }) { Text("Restore") }
                                        },
                                    )
                                    DropdownMenu(
                                        expanded = menuOpenFor == card.cardId,
                                        onDismissRequest = { menuOpenFor = null },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Restore") },
                                            onClick = { restore(card) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Delete Permanently") },
                                            onClick = {
                                                menuOpenFor = null
                                                cardPendingDelete = card
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    cardPendingDelete?.let { card ->
        AlertDialog(
            onDismissRequest = { cardPendingDelete = null },
            title = { Text("Delete Permanently") },
            text = { Text("Permanently delete \"${card.title}\"? This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { deletePermanently(card) }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { cardPendingDelete = null }) { Text("Cancel") }
            },
        )
    }

    if (emptyTrashPending) {
        AlertDialog(
            onDismissRequest = { emptyTrashPending = false },
            title = { Text("Empty Trash") },
            text = { Text("Permanently delete all trashed cards in this project? This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { emptyTrash() }) { Text("Empty Trash") }
            },
            dismissButton = {
                TextButton(onClick = { emptyTrashPending = false }) { Text("Cancel") }
            },
        )
    }
}
