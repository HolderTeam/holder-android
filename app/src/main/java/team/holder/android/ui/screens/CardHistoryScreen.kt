package team.holder.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.holder.android.HolderCardHistoryComparison
import team.holder.android.HolderCardHistoryEntry
import team.holder.android.HolderCardHistoryPage
import team.holder.android.HolderCardHistorySave
import team.holder.android.HolderNative
import team.holder.android.ui.CenteredMessage
import team.holder.android.ui.LoadState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HISTORY_ZONE = ZoneId.systemDefault()
private val DAY_HEADER_FORMAT = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")
private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm").withZone(HISTORY_ZONE)
private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(HISTORY_ZONE)

private fun Long.toLocalDate(): LocalDate = Instant.ofEpochSecond(this).atZone(HISTORY_ZONE).toLocalDate()

private enum class ComparisonMode { SINCE, CHANGE, VERSION }

/**
 * Card-scoped History, reached from [CardViewScreen]'s toolbar. A narrow-display adaptation of
 * holder-desktop's side-by-side History toolbox: the timeline and a selected comparison are
 * separate screens here (toggled by [selectedEntry] rather than by navigation, so returning from
 * the comparison never re-fetches the timeline), not two panes of one screen. Read-only except
 * for [HolderNative.restoreCardHistory], which -- like desktop's own Restore -- writes a new
 * forward commit rather than altering anything in place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardHistoryScreen(
    cardId: String,
    projectId: String,
    cardTitle: String,
    onRestored: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pageState by remember(cardId) { mutableStateOf<LoadState<HolderCardHistoryPage>>(LoadState.Loading) }
    var loadingOlder by remember { mutableStateOf(false) }
    var pagingError by remember { mutableStateOf<String?>(null) }
    var selectedEntry by remember { mutableStateOf<HolderCardHistoryEntry?>(null) }
    var comparisonMode by remember { mutableStateOf(ComparisonMode.SINCE) }
    var comparisonState by remember { mutableStateOf<LoadState<HolderCardHistoryComparison>?>(null) }

    suspend fun load() {
        pageState = runCatching {
            withContext(Dispatchers.IO) { HolderNative.listCardHistory(projectId, cardId) }
        }.fold(
            onSuccess = { LoadState.Success(it) },
            onFailure = { LoadState.Error(it.message ?: it::class.java.simpleName) },
        )
    }

    LaunchedEffect(cardId) { load() }

    // Detail is a toggled state, not a nav route, so the system back gesture should close it
    // (mirroring CardViewScreen's own focusMode) before it pops this screen off the stack.
    BackHandler(enabled = selectedEntry != null) { selectedEntry = null }

    val loadedPage = (pageState as? LoadState.Success)?.value
    val headOid = loadedPage?.headOid

    // A background compare-request cancels automatically (Compose replaces the coroutine) if
    // the selection or mode changes again before it returns -- a late response can never
    // overwrite what's now shown, the same guarantee desktop's serial-number check provides.
    LaunchedEffect(selectedEntry?.lastOid, comparisonMode, headOid) {
        val entry = selectedEntry ?: return@LaunchedEffect
        if (comparisonMode == ComparisonMode.SINCE && entry.lastOid == headOid) {
            // The selected entry already IS the captured current saved version: showing a
            // HEAD-to-HEAD diff would be meaningless, so this is a distinct, non-loading state.
            comparisonState = null
            return@LaunchedEffect
        }
        val (fromOid, toOid) = when (comparisonMode) {
            ComparisonMode.CHANGE -> entry.parentOids.firstOrNull() to entry.lastOid
            ComparisonMode.VERSION -> entry.lastOid to entry.lastOid
            ComparisonMode.SINCE -> entry.lastOid to (headOid ?: entry.lastOid)
        }
        comparisonState = LoadState.Loading
        comparisonState = runCatching {
            withContext(Dispatchers.IO) {
                HolderNative.compareCardHistory(projectId, cardId, fromOid, toOid)
            }
        }.fold(
            onSuccess = { LoadState.Success(it) },
            onFailure = { LoadState.Error(it.message ?: it::class.java.simpleName) },
        )
    }

    val entry = selectedEntry
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (entry != null) "Saved version" else "History")
                        Text(
                            cardTitle.ifEmpty { "Card" },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (entry != null) selectedEntry = null else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (entry == null) {
            HistoryTimelineContent(
                modifier = Modifier.padding(innerPadding),
                pageState = pageState,
                loadingOlder = loadingOlder,
                pagingError = pagingError,
                onRetry = { scope.launch { load() } },
                onSelect = { selected ->
                    comparisonMode = if (selected.lastOid == headOid) ComparisonMode.CHANGE else ComparisonMode.SINCE
                    selectedEntry = selected
                },
                onLoadOlder = {
                    val current = loadedPage ?: return@HistoryTimelineContent
                    val cursor = current.nextCursor ?: return@HistoryTimelineContent
                    scope.launch {
                        loadingOlder = true
                        pagingError = null
                        val result = runCatching {
                            withContext(Dispatchers.IO) {
                                HolderNative.listCardHistory(projectId, cardId, cursor = cursor)
                            }
                        }
                        loadingOlder = false
                        result.fold(
                            onSuccess = { next ->
                                pageState = LoadState.Success(
                                    current.copy(
                                        entries = current.entries + next.entries,
                                        nextCursor = next.nextCursor,
                                        scanLimited = next.scanLimited,
                                    )
                                )
                            },
                            onFailure = { pagingError = it.message ?: it::class.java.simpleName },
                        )
                    }
                },
            )
        } else {
            HistoryDetailContent(
                modifier = Modifier.padding(innerPadding),
                entry = entry,
                headOid = headOid,
                mode = comparisonMode,
                onModeChange = { comparisonMode = it },
                comparisonState = comparisonState,
                onRestore = { oid ->
                    scope.launch {
                        HolderNative.restoreCardHistory(cardId, oid)
                        onRestored()
                        selectedEntry = null
                        load()
                    }
                },
            )
        }
    }
}

@Composable
private fun HistoryTimelineContent(
    pageState: LoadState<HolderCardHistoryPage>,
    loadingOlder: Boolean,
    pagingError: String?,
    onRetry: () -> Unit,
    onSelect: (HolderCardHistoryEntry) -> Unit,
    onLoadOlder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (pageState) {
        is LoadState.Loading -> CenteredMessage(modifier.fillMaxSize()) { CircularProgressIndicator() }
        is LoadState.Error -> CenteredMessage(modifier.fillMaxSize()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Couldn't load history: ${pageState.message}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                TextButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) { Text("Retry") }
            }
        }
        is LoadState.Success -> {
            val page = pageState.value
            if (page.entries.isEmpty()) {
                CenteredMessage(modifier.fillMaxSize()) {
                    Text("No saved history yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val grouped = page.entries.groupBy { it.endedAt.toLocalDate() }
                val today = LocalDate.now(HISTORY_ZONE)
                val yesterday = today.minusDays(1)
                LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    grouped.forEach { (date, entries) ->
                        item(key = "header-$date") {
                            Text(
                                when (date) {
                                    today -> "Today"
                                    yesterday -> "Yesterday"
                                    else -> DAY_HEADER_FORMAT.format(date)
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        items(entries, key = { it.lastOid }) { entry ->
                            HistoryEntryRow(entry = entry, isCurrent = entry.lastOid == page.headOid, onClick = { onSelect(entry) })
                        }
                    }
                    if (page.nextCursor != null) {
                        item(key = "load-older") {
                            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                if (loadingOlder) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                } else {
                                    OutlinedButton(onClick = onLoadOlder) {
                                        Text(if (page.scanLimited) "Continue scanning older history" else "Load older history")
                                    }
                                }
                                pagingError?.let {
                                    Text(
                                        "Couldn't load more history: $it",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 4.dp),
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

@Composable
private fun HistoryEntryRow(entry: HolderCardHistoryEntry, isCurrent: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = false, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(10.dp)
                .background(
                    if (entry.isMerge) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    CircleShape,
                ),
        )
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${TIME_FORMAT.format(Instant.ofEpochSecond(entry.startedAt))} · ${entry.authorName}",
                    style = MaterialTheme.typography.labelLarge,
                )
                if (entry.isMerge) {
                    Text(
                        "  ·  Merge",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (isCurrent) {
                    Text(
                        "  ·  Current",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(entry.summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
            if (entry.commitCount > 1) {
                Text(
                    "${entry.commitCount} autosaved versions",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun HistoryDetailContent(
    entry: HolderCardHistoryEntry,
    headOid: String?,
    mode: ComparisonMode,
    onModeChange: (ComparisonMode) -> Unit,
    comparisonState: LoadState<HolderCardHistoryComparison>?,
    onRestore: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showRestoreDialog by remember { mutableStateOf(false) }
    var detailsExpanded by remember { mutableStateOf(false) }
    val isCurrent = entry.lastOid == headOid

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(
            "${TIMESTAMP_FORMAT.format(Instant.ofEpochSecond(entry.endedAt))} · ${entry.authorName}",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(entry.summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))

        Row(modifier = Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == ComparisonMode.SINCE,
                onClick = { onModeChange(ComparisonMode.SINCE) },
                label = { Text("Since this version") },
            )
            FilterChip(
                selected = mode == ComparisonMode.CHANGE,
                onClick = { onModeChange(ComparisonMode.CHANGE) },
                label = { Text("This change") },
            )
            FilterChip(
                selected = mode == ComparisonMode.VERSION,
                onClick = { onModeChange(ComparisonMode.VERSION) },
                label = { Text("View version") },
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        if (mode == ComparisonMode.SINCE && isCurrent) {
            Text(
                "This is the current saved version. Choose This change to see how it was made.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            when (comparisonState) {
                null -> {}
                is LoadState.Loading -> CenteredMessage(Modifier.fillMaxWidth().padding(vertical = 24.dp)) {
                    CircularProgressIndicator()
                }
                is LoadState.Error -> Text(
                    "Couldn't compare this version: ${comparisonState.message}",
                    color = MaterialTheme.colorScheme.error,
                )
                is LoadState.Success -> {
                    val comparison = comparisonState.value
                    if (mode == ComparisonMode.VERSION) {
                        VersionContent(comparison)
                    } else {
                        DiffContent(comparison)
                    }
                }
            }
        }

        Button(
            onClick = { showRestoreDialog = true },
            enabled = !isCurrent,
            modifier = Modifier.padding(top = 20.dp),
        ) {
            Text("Restore this version")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        TextButton(onClick = { detailsExpanded = !detailsExpanded }) {
            Text(if (detailsExpanded) "Details ▴" else "Details ▾")
        }
        if (detailsExpanded) {
            SelectionContainer { GitDetails(entry) }
        }
    }

    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text("Restore this version?") },
            text = {
                Text(
                    "This writes a new commit with this version's title, body, links, milestones, " +
                        "location, and Trash state -- it never rewrites history, so the current saved " +
                        "version remains recoverable afterwards.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreDialog = false
                    onRestore(entry.lastOid)
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { showRestoreDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun VersionContent(comparison: HolderCardHistoryComparison) {
    val version = comparison.to
    if (!version.exists) {
        Text("No saved card at this version.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    SelectionContainer {
        Column {
            Text(version.title, style = MaterialTheme.typography.titleLarge)
            Text(version.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun DiffContent(comparison: HolderCardHistoryComparison) {
    if (comparison.lines.isEmpty()) {
        Text("No text changed in this comparison.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    SelectionContainer {
        Column {
            comparison.lines.forEach { line ->
                val (background, prefix) = when (line.origin) {
                    "+" -> Color(0x3300C853) to "+ "
                    "-" -> Color(0x33D50000) to "- "
                    else -> Color.Transparent to "  "
                }
                Text(
                    prefix + line.text,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().background(background).padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            if (comparison.truncated) {
                Text(
                    "Diff shortened.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun GitDetails(entry: HolderCardHistoryEntry) {
    Column {
        entry.saves.forEach { save -> GitDetailsSave(save) }
        Text(
            "Parents: " + if (entry.parentOids.isEmpty()) "none (creation)" else entry.parentOids.joinToString(),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun GitDetailsSave(save: HolderCardHistorySave) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(save.oid, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text(save.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            Text(
                "Authored ${TIMESTAMP_FORMAT.format(Instant.ofEpochSecond(save.authoredAt))} · " +
                    "Committed ${TIMESTAMP_FORMAT.format(Instant.ofEpochSecond(save.committedAt))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
