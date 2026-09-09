package team.holder.android.ui.screens

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.holder.android.HolderCard
import team.holder.android.HolderCardLinks
import team.holder.android.HolderNative
import team.holder.android.HolderSettings
import team.holder.android.R
import team.holder.android.splitLeadingHeading
import team.holder.android.ui.CenteredMessage
import team.holder.android.ui.LoadState
import team.holder.android.ui.cardSequenceLinks
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
    var showOverflowMenu by remember { mutableStateOf(false) }
    // Guards delete against double-tap, same rationale as CardListScreen's isSubmitting.
    var isDeleting by remember { mutableStateOf(false) }
    var focusMode by remember { mutableStateOf(false) }
    var cardMeta by remember(cardId, refreshKey) { mutableStateOf<HolderCard?>(null) }

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

    // There's no single-card fetch, so this piggybacks on the project's full list -- same
    // approach ConnectionsSummary's Next/Previous/Follows/Precedes already use.
    LaunchedEffect(cardId, projectId, refreshKey) {
        cardMeta = runCatching {
            withContext(Dispatchers.IO) { HolderNative.listCards(projectId).find { it.cardId == cardId } }
        }.getOrNull()
    }

    Scaffold(
        topBar = {
            // Experimental bottom-bar layout: the top row is Back/title/metadata/Overflow only
            // -- Focus/Tools/Child moved down into a BottomAppBar (see bottomBar below), which
            // also docks the Edit FAB inside it instead of floating separately. Easy to revert
            // to the single-row TopAppBar by restoring actions = { Focus, Connections, Add,
            // overflow Box } here and dropping bottomBar/the Scaffold-level floatingActionButton
            // swap below.
            if (!focusMode) {
                TopAppBar(
                    title = {
                        Column {
                            Text(cardTitle.ifEmpty { "Card" })
                            cardMeta?.let { meta ->
                                Text(
                                    lastEditedSummary(meta),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        val loaded = state as? LoadState.Success
                        Box {
                            IconButton(onClick = { showOverflowMenu = true }, enabled = loaded != null) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    onClick = {
                                        showOverflowMenu = false
                                        showDeleteDialog = true
                                    },
                                )
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            // Slim custom bar (Row + surfaceContainer background, tight vertical padding)
            // instead of the full-height Material3 BottomAppBar -- matching the bottom
            // toolbar CardEditScreen already uses for its markdown formatting actions, just
            // with labels since these are named navigation actions rather than glyph buttons.
            // Four evenly-weighted labeled actions, no FAB -- matching Google Photos' bottom
            // bar (Share/Edit/Add to/Bin), rather than singling Edit out as a docked FAB.
            if (!focusMode) {
                val loaded = state as? LoadState.Success
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .navigationBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    CardViewActionButton(
                        icon = {
                            Icon(painterResource(R.drawable.ic_fullscreen), contentDescription = "Focus mode")
                        },
                        label = "Focus",
                        onClick = { focusMode = true },
                    )
                    CardViewActionButton(
                        icon = { Icon(painterResource(R.drawable.ic_flask), contentDescription = "Tools") },
                        label = "Tools",
                        onClick = onConnectionsClick,
                    )
                    CardViewActionButton(
                        icon = { Icon(Icons.Filled.Add, contentDescription = "New child card") },
                        label = "Child",
                        onClick = onCreateChildCard,
                    )
                    CardViewActionButton(
                        icon = { Icon(Icons.Filled.Edit, contentDescription = "Edit") },
                        label = "Edit",
                        onClick = { loaded?.let { onEdit(it.value) } },
                    )
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
                // Deliberately drops innerPadding's bottom component here -- applying it to this
                // outer container would reserve a permanently-visible blank strip above the
                // bottom bar (visible even mid-scroll) instead of letting content scroll behind
                // the bar's own opaque background, the way Google Docs' bottom bar does. The same
                // amount is instead applied as trailing space inside the scrollable Column below,
                // so it becomes scroll distance -- the last line can still clear the bar -- not a
                // fixed viewport clip.
                val bottomInset = innerPadding.calculateBottomPadding()
                // BoxWithConstraints + heightIn(min = ...) + SpaceBetween: when the card is
                // shorter than the screen, this pushes the connections summary down to the
                // bottom of the visible area instead of leaving it stranded right under a short
                // card. When the card is long enough to need scrolling, there's no leftover
                // space to distribute, so the summary just falls back to sitting directly after
                // the content, same as before.
                BoxWithConstraints(
                    modifier = Modifier
                        .padding(
                            start = innerPadding.calculateStartPadding(LocalLayoutDirection.current),
                            top = innerPadding.calculateTopPadding(),
                            end = innerPadding.calculateEndPadding(LocalLayoutDirection.current),
                        )
                        .padding(16.dp),
                ) {
                    val minHeight = maxHeight
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = minHeight)
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = bottomInset),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        HolderMarkdownViewer(
                            markdown = displayed,
                            projectId = projectId,
                            cardId = cardId,
                            onNavigateToCard = onNavigateToCard,
                            onNavigateToTag = onNavigateToTag,
                        )
                        // Hidden in focus mode along with the rest of the chrome -- focus mode
                        // means just the card content, nothing else.
                        if (!focusMode) {
                            ConnectionsSummary(
                                cardId = cardId,
                                projectId = projectId,
                                refreshKey = refreshKey,
                                onNavigateToCard = onNavigateToCard,
                            )
                        }
                    }
                }
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
                            // Explicitly back on the main thread before onDeleted() -- which
                            // navigates -- rather than relying on withContext(IO) to resume there
                            // on its own; see AddMilestoneScreen for why.
                            withContext(Dispatchers.Main.immediate) {
                                isDeleting = false
                                showDeleteDialog = false
                                onDeleted()
                            }
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

/** A labeled icon action for the card viewer's second toolbar row -- min 72dp wide with 8dp
 * vertical padding around icon+label, so the combined touch target stays comfortably above
 * Android's 48dp minimum even though the visible icon itself is smaller. */
@Composable
private fun CardViewActionButton(icon: @Composable () -> Unit, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .widthIn(min = 64.dp)
            .padding(vertical = 4.dp),
    ) {
        icon()
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** "Last edited: 9 days ago", or "Created 9 days ago" for a card that's never been touched
 * since -- otherwise a never-edited card would misleadingly claim an edit that didn't happen. */
private fun lastEditedSummary(card: HolderCard): String {
    val neverEdited = card.updatedAt == card.createdAt
    val relative = DateUtils.getRelativeTimeSpanString(
        (if (neverEdited) card.createdAt else card.updatedAt) * 1000,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    )
    return if (neverEdited) "Created $relative" else "Last edited: $relative"
}

/**
 * A concise, read-only glance at cardId's connections, appended below the card content -- the
 * full editable graph lives in ConnectionsScreen, reached via the top bar's Connections icon.
 */
@Composable
private fun ConnectionsSummary(
    cardId: String,
    projectId: String,
    refreshKey: Any,
    onNavigateToCard: (cardId: String, title: String) -> Unit,
) {
    var state by remember(cardId, refreshKey) { mutableStateOf<LoadState<HolderCardLinks>>(LoadState.Loading) }
    var allCards by remember(cardId, refreshKey) { mutableStateOf<List<HolderCard>>(emptyList()) }

    LaunchedEffect(cardId, refreshKey) {
        state = runCatching {
            withContext(Dispatchers.IO) { HolderNative.listCardLinks(cardId) }
        }.fold(
            onSuccess = { LoadState.Success(it) },
            onFailure = { LoadState.Error(it.message ?: it::class.java.simpleName) },
        )
    }

    LaunchedEffect(cardId, projectId, refreshKey) {
        allCards = runCatching {
            withContext(Dispatchers.IO) { HolderNative.listCards(projectId) }
        }.getOrDefault(emptyList())
    }

    val links = (state as? LoadState.Success)?.value

    if (links == null) return
    val sequence = cardSequenceLinks(cardId, links.parent?.cardId, allCards)
    // Excludes "resource" links (a photo attached via the toolbar's structural attachment
    // record) -- there's no card to navigate to, and the image is already visible inline in
    // the body via its holder://resource/ reference.
    val navigableOutgoing = links.outgoing.filter { it.toType != "resource" }
    val isEmpty = links.parent == null && links.children.isEmpty() &&
        navigableOutgoing.isEmpty() && links.backlinks.isEmpty() &&
        sequence.next == null && sequence.previous == null &&
        sequence.follows == null && sequence.precedes == null
    if (isEmpty) return

    Column(modifier = Modifier.padding(top = 24.dp)) {
        HorizontalDivider()
        sequence.next?.let { next ->
            ConnectionSummaryLinkRow(label = "Next", title = next.title) {
                onNavigateToCard(next.cardId, next.title)
            }
        }
        sequence.previous?.let { previous ->
            ConnectionSummaryLinkRow(label = "Previous", title = previous.title) {
                onNavigateToCard(previous.cardId, previous.title)
            }
        }
        sequence.follows?.let { follows ->
            ConnectionSummaryLinkRow(label = "Follows", title = follows.title) {
                onNavigateToCard(follows.cardId, follows.title)
            }
        }
        sequence.precedes?.let { precedes ->
            ConnectionSummaryLinkRow(label = "Precedes", title = precedes.title) {
                onNavigateToCard(precedes.cardId, precedes.title)
            }
        }
        links.parent?.let { parent ->
            ConnectionSummaryLinkRow(label = "Child of", title = parent.title) {
                onNavigateToCard(parent.cardId, parent.title)
            }
        }
        links.children.forEach { child ->
            ConnectionSummaryLinkRow(label = "Parent of", title = child.title) {
                onNavigateToCard(child.cardId, child.title)
            }
        }
        navigableOutgoing.forEach { link ->
            ConnectionSummaryLinkRow(
                label = HolderNative.linkKindLabel(link.kind, forward = true),
                title = link.toTitle ?: link.toCardId,
                linkLabel = link.label,
            ) { onNavigateToCard(link.toCardId, link.toTitle ?: "") }
        }
        links.backlinks.forEach { link ->
            ConnectionSummaryLinkRow(
                label = HolderNative.linkKindLabel(link.kind, forward = false),
                title = link.fromTitle ?: link.fromCardId,
                linkLabel = link.label,
            ) { onNavigateToCard(link.fromCardId, link.fromTitle ?: "") }
        }
    }
}

/** A connections-summary row, self-describing its relationship (e.g. "Depends on: Finish
 * The holder-core Split", "Parent of: Sub Card") rather than grouping same-relationship rows
 * under a shared section header. linkLabel is the link's own free-text annotation (distinct
 * from label, the kind's display word) -- muted and middle-dot-separated so it reads as a note
 * about the connection rather than a continuation of the title, matching ConnectionsScreen.
 * Ellipsized rather than wrapped: this summary is meant to stay glanceable even when kind +
 * title + linkLabel together would otherwise run past one line. */
@Composable
private fun ConnectionSummaryLinkRow(label: String, title: String, linkLabel: String? = null, onClick: () -> Unit) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append(label) }
            append(": ")
            append(title)
            if (!linkLabel.isNullOrBlank()) {
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                    append(" · ")
                    append(linkLabel)
                }
            }
        },
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    )
}
