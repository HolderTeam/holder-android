package team.holder.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.holder.android.HolderCard
import team.holder.android.HolderNative
import team.holder.android.HolderSearchResult
import team.holder.android.HolderSettings
import team.holder.android.ui.CenteredMessage
import team.holder.android.ui.LoadState
import team.holder.android.ui.sortKeyOrderedSiblings

private const val SEARCH_DEBOUNCE_MS = 300L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CardListScreen(
    projectId: String,
    projectName: String,
    refreshKey: Any,
    onCardClick: (cardId: String, title: String) -> Unit,
    onCreateCard: () -> Unit,
    onTrashClick: () -> Unit,
    onCalendarClick: () -> Unit,
    onBack: () -> Unit,
) {
    var cardsState by remember(projectId) { mutableStateOf<LoadState<List<HolderCard>>>(LoadState.Loading) }
    var query by remember(projectId) { mutableStateOf("") }
    // Null means "not searching" -- show the normal card list instead.
    var searchState by remember(projectId) { mutableStateOf<LoadState<List<HolderSearchResult>>?>(null) }
    var cardPendingDelete by remember { mutableStateOf<HolderCard?>(null) }
    var menuOpenFor by remember { mutableStateOf<String?>(null) }
    // Guards delete against double-tap: a second tap can reach the same dialog
    // button before recomposition dismisses it, re-running the delete.
    var isSubmitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val boardViewEnabled by HolderSettings.boardViewEnabled(context).collectAsState(initial = false)
    var currentParentId by remember(projectId) { mutableStateOf<String?>(null) }
    // (cardId, title) trail from the project root down to the folder currently open; root excluded.
    var breadcrumbs by remember(projectId) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    fun navigateUpOrBack() {
        if (boardViewEnabled && currentParentId != null) {
            val popped = breadcrumbs.dropLast(1)
            breadcrumbs = popped
            currentParentId = popped.lastOrNull()?.first
        } else {
            onBack()
        }
    }

    // Toggling Board view off while mid-drill-down shouldn't leave stale scoping behind.
    LaunchedEffect(boardViewEnabled) {
        if (!boardViewEnabled) {
            currentParentId = null
            breadcrumbs = emptyList()
        }
    }

    suspend fun refresh() {
        cardsState = runCatching {
            withContext(Dispatchers.IO) { HolderNative.listCards(projectId) }
        }.fold(
            onSuccess = { LoadState.Success(it) },
            onFailure = { LoadState.Error(it.message ?: it::class.java.simpleName) },
        )
    }

    LaunchedEffect(projectId, refreshKey) { refresh() }

    LaunchedEffect(projectId, query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            searchState = null
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        searchState = LoadState.Loading
        searchState = runCatching {
            withContext(Dispatchers.IO) { HolderNative.searchCards(projectId, trimmed) }
        }.fold(
            onSuccess = { LoadState.Success(it) },
            onFailure = { LoadState.Error(it.message ?: it::class.java.simpleName) },
        )
    }

    BackHandler(enabled = true) { navigateUpOrBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(projectName.ifEmpty { "Cards" }) },
                navigationIcon = {
                    IconButton(onClick = { navigateUpOrBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { scope.launch { HolderSettings.setBoardViewEnabled(context, !boardViewEnabled) } },
                    ) {
                        Icon(
                            if (boardViewEnabled) Icons.AutoMirrored.Filled.List else Icons.Filled.Folder,
                            contentDescription = if (boardViewEnabled) "Switch to List view" else "Switch to Board view",
                        )
                    }
                    IconButton(onClick = onCalendarClick) {
                        Icon(Icons.Filled.DateRange, contentDescription = "Calendar")
                    }
                    IconButton(onClick = onTrashClick) {
                        Icon(Icons.Filled.Delete, contentDescription = "Trash")
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
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search cards") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            )

            if (boardViewEnabled && breadcrumbs.isNotEmpty() && searchState == null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = projectName.ifEmpty { "Cards" },
                        modifier = Modifier.clickable {
                            breadcrumbs = emptyList()
                            currentParentId = null
                        },
                    )
                    breadcrumbs.forEachIndexed { index, (cardId, title) ->
                        Text(" ▸ ")
                        Text(
                            text = title,
                            modifier = if (index == breadcrumbs.lastIndex) {
                                Modifier
                            } else {
                                Modifier.clickable {
                                    breadcrumbs = breadcrumbs.take(index + 1)
                                    currentParentId = cardId
                                }
                            },
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                val search = searchState
                if (search != null) {
                    SearchResultsList(search, onCardClick)
                } else {
                    CardListBody(
                        state = cardsState,
                        menuOpenFor = menuOpenFor,
                        onCardClick = onCardClick,
                        onLongClick = { menuOpenFor = it },
                        onDismissMenu = { menuOpenFor = null },
                        onDeleteRequested = {
                            menuOpenFor = null
                            cardPendingDelete = it
                        },
                        boardViewEnabled = boardViewEnabled,
                        currentParentId = currentParentId,
                        onDrillIn = { card ->
                            breadcrumbs = breadcrumbs + (card.cardId to card.title)
                            currentParentId = card.cardId
                        },
                    )
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
                    if (!isSubmitting) {
                        isSubmitting = true
                        cardPendingDelete = null
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { HolderNative.deleteCard(card.cardId) } }
                            isSubmitting = false
                            refresh()
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { cardPendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CardListBody(
    state: LoadState<List<HolderCard>>,
    menuOpenFor: String?,
    onCardClick: (cardId: String, title: String) -> Unit,
    onLongClick: (String) -> Unit,
    onDismissMenu: () -> Unit,
    onDeleteRequested: (HolderCard) -> Unit,
    boardViewEnabled: Boolean,
    currentParentId: String?,
    onDrillIn: (HolderCard) -> Unit,
) {
    when (state) {
        is LoadState.Loading -> CenteredMessage { CircularProgressIndicator() }
        is LoadState.Error -> CenteredMessage { Text("Failed to load cards: ${state.message}") }
        is LoadState.Success -> {
            val allCards = state.value
            val cards = if (boardViewEnabled) sortKeyOrderedSiblings(currentParentId, allCards) else allCards
            if (cards.isEmpty()) {
                CenteredMessage { Text("No cards yet") }
            } else {
                LazyColumn {
                    items(cards, key = { it.cardId }) { card ->
                        val childCount = if (boardViewEnabled) {
                            allCards.count { it.parentCardId == card.cardId }
                        } else {
                            0
                        }
                        Box {
                            if (childCount > 0) {
                                ListItem(
                                    leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null) },
                                    headlineContent = { Text(card.title) },
                                    supportingContent = {
                                        Text("$childCount ${if (childCount == 1) "item" else "items"}")
                                    },
                                    modifier = Modifier
                                        .combinedClickable(
                                            onClick = { onDrillIn(card) },
                                            onLongClick = { onLongClick(card.cardId) },
                                        )
                                        .semantics {
                                            contentDescription = "${card.title}, folder, " +
                                                "$childCount ${if (childCount == 1) "item" else "items"}"
                                        },
                                )
                            } else {
                                ListItem(
                                    headlineContent = { Text(card.title) },
                                    modifier = Modifier.combinedClickable(
                                        onClick = { onCardClick(card.cardId, card.title) },
                                        onLongClick = { onLongClick(card.cardId) },
                                    ),
                                )
                            }
                            DropdownMenu(
                                expanded = menuOpenFor == card.cardId,
                                onDismissRequest = onDismissMenu,
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    onClick = { onDeleteRequested(card) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultsList(
    state: LoadState<List<HolderSearchResult>>,
    onResultClick: (cardId: String, title: String) -> Unit,
) {
    when (state) {
        is LoadState.Loading -> CenteredMessage { CircularProgressIndicator() }
        is LoadState.Error -> CenteredMessage { Text("Search failed: ${state.message}") }
        is LoadState.Success -> {
            if (state.value.isEmpty()) {
                CenteredMessage { Text("No matching cards") }
            } else {
                LazyColumn {
                    items(state.value, key = { it.cardId }) { result ->
                        ListItem(
                            headlineContent = { Text(result.title) },
                            supportingContent = { Text(result.snippet) },
                            modifier = Modifier.combinedClickable(
                                onClick = { onResultClick(result.cardId, result.title) },
                                onLongClick = {},
                            ),
                        )
                    }
                }
            }
        }
    }
}
