package team.holder.android.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CardListScreen(
    projectId: String,
    projectName: String,
    refreshKey: Any,
    onCardClick: (HolderCard) -> Unit,
    onCreateCard: () -> Unit,
    onBack: () -> Unit,
) {
    var state by remember(projectId) { mutableStateOf<LoadState<List<HolderCard>>>(LoadState.Loading) }
    var cardPendingDelete by remember { mutableStateOf<HolderCard?>(null) }
    var menuOpenFor by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        state = runCatching {
            withContext(Dispatchers.IO) { HolderNative.listCards(projectId) }
        }.fold(
            onSuccess = { LoadState.Success(it) },
            onFailure = { LoadState.Error(it.message ?: it::class.java.simpleName) },
        )
    }

    LaunchedEffect(projectId, refreshKey) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(projectName.ifEmpty { "Cards" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateCard) {
                Icon(Icons.Filled.Add, contentDescription = "New card")
            }
        },
    ) { innerPadding ->
        when (val current = state) {
            is LoadState.Loading -> CenteredMessage(Modifier.padding(innerPadding)) {
                CircularProgressIndicator()
            }
            is LoadState.Error -> CenteredMessage(Modifier.padding(innerPadding)) {
                Text("Failed to load cards: ${current.message}")
            }
            is LoadState.Success -> {
                if (current.value.isEmpty()) {
                    CenteredMessage(Modifier.padding(innerPadding)) {
                        Text("No cards yet")
                    }
                } else {
                    LazyColumn(modifier = Modifier.padding(innerPadding)) {
                        items(current.value, key = { it.cardId }) { card ->
                            Box {
                                ListItem(
                                    headlineContent = { Text(card.title) },
                                    modifier = Modifier.combinedClickable(
                                        onClick = { onCardClick(card) },
                                        onLongClick = { menuOpenFor = card.cardId },
                                    ),
                                )
                                DropdownMenu(
                                    expanded = menuOpenFor == card.cardId,
                                    onDismissRequest = { menuOpenFor = null },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Delete") },
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

    cardPendingDelete?.let { card ->
        AlertDialog(
            onDismissRequest = { cardPendingDelete = null },
            title = { Text("Delete \"${card.title}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    cardPendingDelete = null
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { HolderNative.deleteCard(card.cardId) } }
                        refresh()
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { cardPendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}
