package team.holder.android.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import team.holder.android.HolderCard
import team.holder.android.HolderCardLinks
import team.holder.android.HolderMilestone
import team.holder.android.HolderNative
import team.holder.android.R
import team.holder.android.ui.CenteredMessage
import team.holder.android.ui.LoadState
import team.holder.android.ui.cardSequenceLinks
import team.holder.android.ui.markdown.ResourceAttachmentKind
import team.holder.android.ui.markdown.ResourceImage
import team.holder.android.ui.markdown.rememberResourceAttachmentKind

/**
 * The card inspector/dashboard: where a card stops being a note and becomes an object in
 * Holder's knowledge system. Each row below is a *summary*, not a bare count -- the point of
 * this screen is to show the interesting information at a glance, not hide it behind a chevron.
 * Tapping a row's header/chevron area opens the full screen for that concern (the relationship
 * editor, resource list, calendar, or history timeline); tapping an individual named connection
 * jumps straight to that card instead, preserving Holder's existing graph-navigation feel.
 *
 * Sections never disappear when empty -- this screen is the one place a user discovers what
 * Holder can attach to a card, so an empty section still shows its header with a way in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    cardId: String,
    projectId: String,
    cardTitle: String,
    refreshKey: Any,
    onNavigateToCard: (cardId: String, title: String) -> Unit,
    onConnectionsClick: () -> Unit,
    onResourcesClick: () -> Unit,
    onMilestonesClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onBack: () -> Unit,
) {
    var allCards by remember(cardId) { mutableStateOf<List<HolderCard>>(emptyList()) }
    var linksState by remember(cardId) { mutableStateOf<LoadState<HolderCardLinks>>(LoadState.Loading) }
    var milestones by remember(cardId) { mutableStateOf<List<HolderMilestone>>(emptyList()) }
    var historySummary by remember(cardId) { mutableStateOf<HistorySummary?>(null) }

    LaunchedEffect(cardId, projectId, refreshKey) {
        allCards = runCatching {
            withContext(Dispatchers.IO) { HolderNative.listCards(projectId) }
        }.getOrDefault(emptyList())
    }
    LaunchedEffect(cardId, refreshKey) {
        linksState = runCatching {
            withContext(Dispatchers.IO) { HolderNative.listCardLinks(cardId) }
        }.fold(
            onSuccess = { LoadState.Success(it) },
            onFailure = { LoadState.Error(it.message ?: it::class.java.simpleName) },
        )
    }
    LaunchedEffect(cardId, refreshKey) {
        milestones = runCatching {
            withContext(Dispatchers.IO) { HolderNative.listCardMilestones(cardId) }
        }.getOrDefault(emptyList())
    }
    LaunchedEffect(cardId, projectId, refreshKey) {
        historySummary = runCatching {
            withContext(Dispatchers.IO) { HolderNative.listCardHistory(projectId, cardId) }
        }.getOrNull()?.let { page ->
            HistorySummary(
                versionCount = page.entries.sumOf { it.commitCount },
                hasMore = page.nextCursor != null,
                lastEditedAt = page.entries.firstOrNull()?.endedAt,
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(cardTitle.ifEmpty { "Card" })
                        Text(
                            "Tools",
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
        LazyColumn(modifier = Modifier.padding(innerPadding)) {
            allCards.find { it.cardId == cardId }?.let { card ->
                item { CardVitalsLine(card, historySummary) }
            }

            item {
                ToolTile(title = "Connections", onClick = onConnectionsClick) {
                    when (val state = linksState) {
                        is LoadState.Loading -> LoadingLine()
                        is LoadState.Error -> ErrorLine("Couldn't load connections: ${state.message}")
                        is LoadState.Success -> ConnectionsTileBody(
                            cardId = cardId,
                            links = state.value,
                            allCards = allCards,
                            onNavigateToCard = onNavigateToCard,
                        )
                    }
                }
            }

            item {
                ToolTile(title = "Resources", onClick = onResourcesClick) {
                    when (val state = linksState) {
                        is LoadState.Loading -> LoadingLine()
                        is LoadState.Error -> {} // Already surfaced by the Connections tile above.
                        is LoadState.Success -> ResourcesTileBody(state.value)
                    }
                }
            }

            item {
                ToolTile(title = "Milestones", onClick = onMilestonesClick) {
                    MilestonesTileBody(milestones)
                }
            }

            item {
                ToolTile(title = "History", onClick = onHistoryClick) {
                    HistoryTileBody(historySummary)
                }
            }
        }
    }
}

private data class HistorySummary(val versionCount: Int, val hasMore: Boolean, val lastEditedAt: Long?)

/** A tile's shell: a tappable header (opens the full screen for this concern) plus whatever
 * summary content [body] renders beneath it. Kept deliberately plain -- no card background or
 * elevation -- so the page reads as one continuous inspector rather than a stack of cards. */
@Composable
private fun ToolTile(title: String, onClick: () -> Unit, body: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
        )
        body()
    }
}

@Composable
private fun LoadingLine() {
    CircularProgressIndicator(modifier = Modifier.padding(start = 16.dp, bottom = 8.dp).size(16.dp), strokeWidth = 2.dp)
}

@Composable
private fun ErrorLine(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun EmptyLine(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun SummaryLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

/** Named/curated relationships (parent, sequence links, up to 3 total) shown individually and
 * directly tappable to the linked card -- separate from this tile's own onClick, which opens
 * the full relationship editor. Backlinks are Holder-derived, not user-curated, so they're
 * summarized as a count rather than previewed individually. */
@Composable
private fun ConnectionsTileBody(
    cardId: String,
    links: HolderCardLinks,
    allCards: List<HolderCard>,
    onNavigateToCard: (cardId: String, title: String) -> Unit,
) {
    val sequence = cardSequenceLinks(cardId, links.parent?.cardId, allCards)
    val named = buildList {
        links.parent?.let { add(Triple("Parent", it.cardId, it.title)) }
        sequence.follows?.let { add(Triple("Follows", it.cardId, it.title)) }
        sequence.precedes?.let { add(Triple("Precedes", it.cardId, it.title)) }
        sequence.next?.let { add(Triple("Next", it.cardId, it.title)) }
        sequence.previous?.let { add(Triple("Previous", it.cardId, it.title)) }
    }.take(3)
    val outgoingCount = links.outgoing.count { it.toType != "resource" }
    val backlinkCount = links.backlinks.size
    val childCount = links.children.size
    val totalOthers = outgoingCount + backlinkCount + childCount

    if (named.isEmpty() && totalOthers == 0) {
        EmptyLine("No connections yet")
        return
    }
    named.forEach { (label, targetCardId, title) ->
        Text(
            "$label: $title",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigateToCard(targetCardId, title) }
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    val extras = buildList {
        if (childCount > 0) add("$childCount ${if (childCount == 1) "child" else "children"}")
        if (outgoingCount > 0) add("$outgoingCount other")
        if (backlinkCount > 0) add("$backlinkCount ${if (backlinkCount == 1) "backlink" else "backlinks"}")
    }
    if (extras.isNotEmpty()) {
        SummaryLine(extras.joinToString(" · "))
    }
}

/** Filename + icon/thumbnail preview for up to 2 attached resources, matching the format the
 * full Resources screen uses -- a bare count would throw away exactly the glanceable
 * information a preview is for. */
@Composable
private fun ResourcesTileBody(links: HolderCardLinks) {
    val attachments = links.outgoing.filter { it.toType == "resource" }
    if (attachments.isEmpty()) {
        EmptyLine("No resources yet")
        return
    }
    attachments.take(2).forEach { link ->
        val displayName = link.label ?: "Attachment"
        val kind = rememberResourceAttachmentKind(link.toCardId, displayName)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            if (kind is ResourceAttachmentKind.Image) {
                ResourceImage(
                    resourceId = link.toCardId,
                    altText = displayName,
                    modifier = Modifier.size(24.dp).clip(MaterialTheme.shapes.extraSmall),
                )
            } else {
                Icon(painterResource(R.drawable.ic_file), contentDescription = null, modifier = Modifier.size(24.dp))
            }
            Text(
                displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
    if (attachments.size > 2) {
        SummaryLine("${attachments.size - 2} more")
    }
}

/** The next upcoming milestone's own headline, not a raw list -- milestones are fully
 * user-curated, so the single most relevant one (soonest that hasn't passed, falling back to
 * the most recent past one) is more useful here than an undifferentiated list would be. */
@Composable
private fun MilestonesTileBody(milestones: List<HolderMilestone>) {
    if (milestones.isEmpty()) {
        EmptyLine("No milestones yet")
        return
    }
    val now = Instant.now().epochSecond
    val next = milestones.filter { it.startAt >= now }.minByOrNull { it.startAt }
        ?: milestones.maxByOrNull { it.startAt }
    next?.let { milestone ->
        val label = milestone.kind?.takeIf { it.isNotBlank() } ?: "Milestone"
        SummaryLine("$label: ${DateUtils.formatDateTime(null, milestone.startAt * 1000, DateUtils.FORMAT_SHOW_DATE)}")
    }
    val remaining = milestones.size - 1
    if (remaining > 0) {
        SummaryLine("$remaining other ${if (remaining == 1) "milestone" else "milestones"}")
    }
}

/** The History tile's own body is deliberately empty once loaded -- "Edited ... · N versions"
 * already lives in [CardVitalsLine] at the top of the page, and repeating it here would be
 * exactly the duplication that line was introduced to remove. A spinner is still worth showing
 * while loading so the tile doesn't look broken before then. */
@Composable
private fun HistoryTileBody(summary: HistorySummary?) {
    if (summary == null) {
        LoadingLine()
    }
}

/** "Created Sep 9 · Edited 41 minutes ago · 5 versions" -- Created reads from card metadata
 * (cheap, always correct, available before History's own fetch resolves) rather than from
 * [HistorySummary], which is deliberate: listCardHistory is paginated and can come back
 * scanLimited, so for a card with enough history the "Card created" event might not even be in
 * the loaded page. Edited/versions still come from HistorySummary, appended once it's loaded
 * rather than blocking this line's first paint on the slower fetch. */
@Composable
private fun CardVitalsLine(card: HolderCard, historySummary: HistorySummary?) {
    val created = "Created ${DateUtils.formatDateTime(null, card.createdAt * 1000, DateUtils.FORMAT_SHOW_DATE)}"
    val text = historySummary?.let { summary ->
        val edited = summary.lastEditedAt?.let {
            DateUtils.getRelativeTimeSpanString(it * 1000, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        }
        val versions = "${summary.versionCount}${if (summary.hasMore) "+" else ""} " +
            if (summary.versionCount == 1 && !summary.hasMore) "version" else "versions"
        if (edited != null) "$created · Edited $edited · $versions" else "$created · $versions"
    } ?: created
    Column(modifier = Modifier.padding(top = 8.dp)) {
        SummaryLine(text)
    }
}
