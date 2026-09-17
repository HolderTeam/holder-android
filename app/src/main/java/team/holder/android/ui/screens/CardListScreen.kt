package team.holder.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.holder.android.CardPlacementIntent
import team.holder.android.HolderCard
import team.holder.android.HolderNative
import team.holder.android.HolderSearchResult
import team.holder.android.HolderSettings
import team.holder.android.R
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
    onCreateChildCard: (HolderCard) -> Unit,
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
    // Guards a native write (delete or a Board move) against double-tap: a second tap can reach
    // the same dialog/menu-item action before recomposition dismisses it, re-running the write.
    var isSubmitting by remember { mutableStateOf(false) }
    // Set (briefly) when a Board move-action or drag-drop fails, so the failure is surfaced the
    // same way delete failures already crash silently into the void -- rather than not at all.
    var moveError by remember { mutableStateOf<String?>(null) }
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

    // Every Board move -- menu-driven (Part A) or drag-driven (Part B) -- funnels through here:
    // one in-flight guard, one refresh-after, one place errors surface from.
    fun performMove(card: HolderCard, intent: CardPlacementIntent, targetCardId: String? = null) {
        if (isSubmitting) return
        isSubmitting = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { HolderNative.moveCard(projectId, card.cardId, intent, targetCardId = targetCardId) }
            }
            isSubmitting = false
            result.onFailure { moveError = it.message ?: it::class.java.simpleName }
            refresh()
        }
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
                        if (boardViewEnabled) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Switch to List view")
                        } else {
                            Icon(painterResource(R.drawable.ic_folder), contentDescription = "Switch to Board view")
                        }
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
                        onCreateChildCardRequested = {
                            menuOpenFor = null
                            onCreateChildCard(it)
                        },
                        boardViewEnabled = boardViewEnabled,
                        currentParentId = currentParentId,
                        onDrillIn = { card ->
                            breadcrumbs = breadcrumbs + (card.cardId to card.title)
                            currentParentId = card.cardId
                        },
                        onMove = { card, intent, targetCardId ->
                            menuOpenFor = null
                            performMove(card, intent, targetCardId)
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

    moveError?.let { message ->
        AlertDialog(
            onDismissRequest = { moveError = null },
            title = { Text("Couldn't move card") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { moveError = null }) { Text("OK") }
            },
        )
    }
}

/** In-progress drag: which card, and how far the pointer has moved from where the drag started
 * (in the enclosing LazyColumn's own coordinate space -- see [DragHandle]'s onDragStart, which
 * seeds [startTop]/[startHeight] from that card's own [LazyListState] layout info). Approximates
 * the pointer's absolute Y as the row's original vertical center plus the accumulated drag delta,
 * rather than tracking the handle's exact touch point within the row -- close enough at this
 * feature's 25%/75% drop-zone granularity, and far simpler than reconciling the handle's local
 * touch coordinates with the row's. */
private data class DragState(
    val cardId: String,
    val startTop: Float,
    val startHeight: Float,
    val offsetY: Float = 0f,
)

private enum class DropPosition { BEFORE, AFTER, INTO }

private data class DropTarget(val cardId: String, val position: DropPosition)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CardListBody(
    state: LoadState<List<HolderCard>>,
    menuOpenFor: String?,
    onCardClick: (cardId: String, title: String) -> Unit,
    onLongClick: (String) -> Unit,
    onDismissMenu: () -> Unit,
    onDeleteRequested: (HolderCard) -> Unit,
    onCreateChildCardRequested: (HolderCard) -> Unit,
    boardViewEnabled: Boolean,
    currentParentId: String?,
    onDrillIn: (HolderCard) -> Unit,
    onMove: (card: HolderCard, intent: CardPlacementIntent, targetCardId: String?) -> Unit,
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
                val lazyListState = rememberLazyListState()
                // Hoisted above the per-row `items` scope: the row currently being dragged and
                // the row currently under the pointer are two different composable instances, so
                // both need to read the same state to render (dim the dragged row, highlight the
                // hovered one).
                val dragStateHolder = remember { mutableStateOf<DragState?>(null) }
                val dropTargetHolder = remember { mutableStateOf<DropTarget?>(null) }
                val dragState by dragStateHolder
                val dropTarget by dropTargetHolder

                LazyColumn(state = lazyListState) {
                    items(cards, key = { it.cardId }) { card ->
                        val childCount = if (boardViewEnabled) {
                            allCards.count { it.parentCardId == card.cardId }
                        } else {
                            0
                        }
                        val isDragging = dragState?.cardId == card.cardId
                        val rowDropTarget = dropTarget?.takeIf { it.cardId == card.cardId }

                        Box {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("cardRow_${card.cardId}")
                                    .graphicsLayer(alpha = if (isDragging) 0.6f else 1f)
                                    .let { m ->
                                        if (rowDropTarget?.position == DropPosition.INTO) {
                                            m.background(MaterialTheme.colorScheme.primaryContainer)
                                        } else {
                                            m
                                        }
                                    },
                            ) {
                                if (rowDropTarget?.position == DropPosition.BEFORE) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(2.dp)
                                            .background(MaterialTheme.colorScheme.primary),
                                    )
                                }

                                val trailing: (@Composable () -> Unit)? = if (boardViewEnabled) {
                                    {
                                        DragHandle(
                                            card = card,
                                            lazyListState = lazyListState,
                                            dragStateHolder = dragStateHolder,
                                            dropTargetHolder = dropTargetHolder,
                                            onMove = onMove,
                                        )
                                    }
                                } else {
                                    null
                                }

                                if (childCount > 0) {
                                    ListItem(
                                        leadingContent = { Icon(painterResource(R.drawable.ic_folder), contentDescription = null) },
                                        headlineContent = { Text(card.title) },
                                        supportingContent = {
                                            Text("$childCount ${if (childCount == 1) "item" else "items"}")
                                        },
                                        trailingContent = trailing,
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
                                        trailingContent = trailing,
                                        modifier = Modifier.combinedClickable(
                                            onClick = { onCardClick(card.cardId, card.title) },
                                            onLongClick = { onLongClick(card.cardId) },
                                        ),
                                    )
                                }

                                if (rowDropTarget?.position == DropPosition.AFTER) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(2.dp)
                                            .background(MaterialTheme.colorScheme.primary),
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = menuOpenFor == card.cardId,
                                onDismissRequest = onDismissMenu,
                            ) {
                                if (boardViewEnabled) {
                                    DropdownMenuItem(
                                        text = { Text("Create Child Card") },
                                        onClick = { onCreateChildCardRequested(card) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Move Up a Level") },
                                        enabled = card.parentCardId != null,
                                        onClick = { onMove(card, CardPlacementIntent.UP_LEVEL, null) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Move Up") },
                                        onClick = { onMove(card, CardPlacementIntent.LEFT, null) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Move Down") },
                                        onClick = { onMove(card, CardPlacementIntent.RIGHT, null) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Move to Top") },
                                        onClick = { onMove(card, CardPlacementIntent.TO_START, null) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Move to Bottom") },
                                        onClick = { onMove(card, CardPlacementIntent.TO_END, null) },
                                    )
                                    HorizontalDivider()
                                }
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

/** Board mode's drag-to-reorder trigger. A dedicated handle rather than the row's own long-press
 * (which already opens [DropdownMenu] everywhere in this app) -- see flowboard.md Phase 2. Drag
 * starts immediately on touch-down-and-move (no long-press-first) since the handle is already a
 * small, separate touch target from the row's tap/long-press regions, so there's no ambiguous
 * gesture to disambiguate the way there would be on the row itself.
 *
 * Resolves the drop by finding which visible row the pointer ends up over (via [lazyListState]'s
 * layout info) and which third of that row's height it's in: top ~25% -> before, bottom ~25% ->
 * after, middle ~50% -> into (reparent) -- flowboard.md's own 25%/75% split, read against
 * vertical position instead of desktop's horizontal grid thirds. */
@Composable
private fun DragHandle(
    card: HolderCard,
    lazyListState: LazyListState,
    dragStateHolder: MutableState<DragState?>,
    dropTargetHolder: MutableState<DropTarget?>,
    onMove: (card: HolderCard, intent: CardPlacementIntent, targetCardId: String?) -> Unit,
) {
    Icon(
        painter = painterResource(R.drawable.ic_drag_handle),
        contentDescription = "Reorder ${card.title}",
        modifier = Modifier
            .padding(8.dp)
            .pointerInput(card.cardId) {
                detectDragGestures(
                    onDragStart = {
                        val info = lazyListState.layoutInfo.visibleItemsInfo.find { it.key == card.cardId }
                        dragStateHolder.value = if (info != null) {
                            DragState(cardId = card.cardId, startTop = info.offset.toFloat(), startHeight = info.size.toFloat())
                        } else {
                            null
                        }
                        dropTargetHolder.value = null
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val current = dragStateHolder.value ?: return@detectDragGestures
                        val updated = current.copy(offsetY = current.offsetY + dragAmount.y)
                        dragStateHolder.value = updated

                        val pointerY = updated.startTop + (updated.startHeight / 2f) + updated.offsetY
                        val hovered = lazyListState.layoutInfo.visibleItemsInfo.find { info ->
                            pointerY >= info.offset && pointerY < info.offset + info.size
                        }
                        dropTargetHolder.value = if (hovered != null && hovered.key != card.cardId) {
                            val fraction = (pointerY - hovered.offset) / hovered.size.toFloat()
                            val position = when {
                                fraction < 0.25f -> DropPosition.BEFORE
                                fraction > 0.75f -> DropPosition.AFTER
                                else -> DropPosition.INTO
                            }
                            DropTarget(cardId = hovered.key as String, position = position)
                        } else {
                            null
                        }
                    },
                    onDragEnd = {
                        val target = dropTargetHolder.value
                        dragStateHolder.value = null
                        dropTargetHolder.value = null
                        if (target != null && target.cardId != card.cardId) {
                            val intent = when (target.position) {
                                DropPosition.BEFORE -> CardPlacementIntent.BEFORE
                                DropPosition.AFTER -> CardPlacementIntent.AFTER
                                DropPosition.INTO -> CardPlacementIntent.INTO
                            }
                            onMove(card, intent, target.cardId)
                        }
                    },
                    onDragCancel = {
                        dragStateHolder.value = null
                        dropTargetHolder.value = null
                    },
                )
            },
    )
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
