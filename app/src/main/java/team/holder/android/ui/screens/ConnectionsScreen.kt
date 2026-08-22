package team.holder.android.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.holder.android.HolderBacklink
import team.holder.android.HolderCard
import team.holder.android.HolderCardLinks
import team.holder.android.HolderNative
import team.holder.android.HolderOutgoingLink
import team.holder.android.ui.CenteredMessage
import team.holder.android.ui.LoadState
import team.holder.android.ui.cardSequenceLinks

private val CARD_DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneId.systemDefault())
private val CARD_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

/**
 * "About this card": its own created/updated timestamps, plus its connections -- hierarchy
 * (parent/children, shown automatically like desktop Holder's Connections tool) and explicit
 * front-matter links, with add/remove for outgoing links. Parent/children and backlinks are
 * read-only here -- hierarchy moves through parent_card_id (not this screen), and a backlink's
 * own from-card owns that connection.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConnectionsScreen(
    cardId: String,
    projectId: String,
    cardTitle: String,
    refreshKey: Any,
    onAddConnection: () -> Unit,
    onNavigateToCard: (cardId: String, title: String) -> Unit,
    onBack: () -> Unit,
) {
    var linksState by remember(cardId) { mutableStateOf<LoadState<HolderCardLinks>>(LoadState.Loading) }
    var allCards by remember(cardId) { mutableStateOf<List<HolderCard>>(emptyList()) }
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

    // There's no single-card fetch, so the dates and the Next/Previous/Follows/Precedes rows
    // below all piggyback on the project's full list -- same approach CardViewScreen's
    // ConnectionsSummary uses. Failing just leaves those rows off rather than blocking the
    // rest of the screen.
    LaunchedEffect(cardId, projectId, refreshKey) {
        allCards = runCatching {
            withContext(Dispatchers.IO) { HolderNative.listCards(projectId) }
        }.getOrDefault(emptyList())
    }

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
                title = {
                    Column {
                        Text(cardTitle.ifEmpty { "Card" })
                        Text(
                            "About this card",
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
                    val sequence = cardSequenceLinks(cardId, links.parent?.cardId, allCards)
                    val noConnections = links.parent == null && links.children.isEmpty() &&
                        links.outgoing.isEmpty() && links.backlinks.isEmpty() &&
                        sequence.next == null && sequence.previous == null &&
                        sequence.follows == null && sequence.precedes == null
                    LazyColumn(modifier = Modifier.padding(innerPadding)) {
                        allCards.find { it.cardId == cardId }?.let { CardDates(it) }
                        if (noConnections) {
                            item {
                                Text(
                                    "No connections yet",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        } else {
                            item { SectionHeader("Connections") }
                            sequence.next?.let { next ->
                                item {
                                    ConnectionRow(label = "Next", title = next.title) {
                                        onNavigateToCard(next.cardId, next.title)
                                    }
                                }
                            }
                            sequence.previous?.let { previous ->
                                item {
                                    ConnectionRow(label = "Previous", title = previous.title) {
                                        onNavigateToCard(previous.cardId, previous.title)
                                    }
                                }
                            }
                            sequence.follows?.let { follows ->
                                item {
                                    ConnectionRow(label = "Follows", title = follows.title) {
                                        onNavigateToCard(follows.cardId, follows.title)
                                    }
                                }
                            }
                            sequence.precedes?.let { precedes ->
                                item {
                                    ConnectionRow(label = "Precedes", title = precedes.title) {
                                        onNavigateToCard(precedes.cardId, precedes.title)
                                    }
                                }
                            }
                            links.parent?.let { parent ->
                                item {
                                    ConnectionRow(label = "Child of", title = parent.title) {
                                        onNavigateToCard(parent.cardId, parent.title)
                                    }
                                }
                            }
                            items(links.children, key = { "child:${it.cardId}" }) { child ->
                                ConnectionRow(label = "Parent of", title = child.title) {
                                    onNavigateToCard(child.cardId, child.title)
                                }
                            }
                            items(links.outgoing, key = { "out:${it.toCardId}:${it.kind}" }) { link ->
                                Box {
                                    ListItem(
                                        headlineContent = {
                                            Text(
                                                connectionHeadline(
                                                    kindLabel = HolderNative.linkKindLabel(link.kind, forward = true),
                                                    title = link.toTitle ?: link.toCardId,
                                                    label = link.label,
                                                    primary = MaterialTheme.colorScheme.primary,
                                                    muted = MaterialTheme.colorScheme.onSurfaceVariant,
                                                ),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
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
                            items(links.backlinks, key = { "back:${it.fromCardId}:${it.kind}" }) { link ->
                                ListItem(
                                    headlineContent = {
                                        Text(
                                            connectionHeadline(
                                                kindLabel = HolderNative.linkKindLabel(link.kind, forward = false),
                                                title = link.fromTitle ?: link.fromCardId,
                                                label = link.label,
                                                primary = MaterialTheme.colorScheme.primary,
                                                muted = MaterialTheme.colorScheme.onSurfaceVariant,
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
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

/** Created always shows; Updated only shows once it actually diverges from Created -- a
 * never-edited card would otherwise display the same instant twice. */
private fun LazyListScope.CardDates(card: HolderCard) {
    item { DateRow(label = "Created", epochSeconds = card.createdAt) }
    if (card.updatedAt != card.createdAt) {
        item { DateRow(label = "Updated", epochSeconds = card.updatedAt) }
    }
}

@Composable
private fun DateRow(label: String, epochSeconds: Long) {
    Text(
        dateHeadline(
            label = label,
            epochSeconds = epochSeconds,
            primary = MaterialTheme.colorScheme.primary,
            muted = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** A single-line "Label: Title" row (e.g. "Next: Finish The Split") for connections that carry
 * no extra annotation -- Next/Previous/Follows/Precedes and the hierarchy links. Outgoing and
 * backlinks use connectionHeadline directly instead, since they can carry a custom label. */
@Composable
private fun ConnectionRow(label: String, title: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(
                connectionHeadline(
                    kindLabel = label,
                    title = title,
                    label = null,
                    primary = MaterialTheme.colorScheme.primary,
                    muted = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = {}),
    )
}

@Composable
private fun SectionHeader(title: String) {
    Column(modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** "Depends on: My New Card" or, with a custom label, "Depends on: My New Card · waiting on
 * review" -- kept to one line (see the Text callers' maxLines) rather than splitting the
 * relationship onto its own line below the title, which reads as if it were describing the
 * linked card itself rather than the relationship to it. The custom label gets a muted color
 * (distinct from both the default title black and the primary-colored kind) so it reads as an
 * annotation rather than a continuation of the title -- a plain separator alone (dash, colon)
 * doesn't achieve that since both sides would still be the same color. */
private fun connectionHeadline(kindLabel: String, title: String, label: String?, primary: Color, muted: Color) =
    buildAnnotatedString {
        withStyle(SpanStyle(color = primary)) { append(kindLabel) }
        append(": ")
        append(title)
        if (!label.isNullOrBlank()) {
            withStyle(SpanStyle(color = muted)) {
                append(" · ")
                append(label)
            }
        }
    }

/** "Created Mar 4, 2025 · 14:34:07" -- the date carries the same weight as a connection's
 * title, with the time tacked on muted since the exact second rarely matters, but is there
 * for the rare case (a field note, a timestamped observation) where it does. */
private fun dateHeadline(label: String, epochSeconds: Long, primary: Color, muted: Color): AnnotatedString {
    val instant = Instant.ofEpochSecond(epochSeconds)
    return buildAnnotatedString {
        withStyle(SpanStyle(color = primary)) { append(label) }
        append(" ")
        append(CARD_DATE_FORMAT.format(instant))
        withStyle(SpanStyle(color = muted)) {
            append(" · ")
            append(CARD_TIME_FORMAT.format(instant))
        }
    }
}
