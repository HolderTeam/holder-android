package team.holder.android.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.holder.android.HolderCard
import team.holder.android.HolderCardLinks
import team.holder.android.HolderMilestone
import team.holder.android.HolderNative
import team.holder.android.HolderOutgoingLink
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
 * The bottom bar (Connections, Milestones, Resources) is the permanent way in to each concern,
 * so a tile only renders -- header included -- when it actually has something to show; an empty
 * section would just repeat what the bar already offers. Connections leads the bar, reflecting
 * a deliberate bias toward cards being linked rather than islands.
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
    onTagClick: (String) -> Unit,
    onBack: () -> Unit,
) {
    var allCards by remember(cardId) { mutableStateOf<List<HolderCard>>(emptyList()) }
    var linksState by remember(cardId) { mutableStateOf<LoadState<HolderCardLinks>>(LoadState.Loading) }
    var milestones by remember(cardId) { mutableStateOf<List<HolderMilestone>>(emptyList()) }
    var historySummary by remember(cardId) { mutableStateOf<HistorySummary?>(null) }
    var tags by remember(cardId) { mutableStateOf<List<String>>(emptyList()) }
    var editableTags by remember(cardId) { mutableStateOf<List<String>>(emptyList()) }
    var projectTags by remember(projectId) { mutableStateOf<List<String>>(emptyList()) }
    var tagRefreshKey by remember { mutableStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    suspend fun refreshTags() {
        tags = runCatching {
            withContext(Dispatchers.IO) { HolderNative.listCardTags(cardId) }
        }.getOrDefault(tags)
        // The subset of `tags` on the card's trailing tag line -- the only ones removeCardTag
        // can actually remove. Drives which chips get a remove ("x") control at all, rather
        // than showing one on every chip and letting removal fail reactively.
        editableTags = runCatching {
            withContext(Dispatchers.IO) { HolderNative.listEditableCardTags(cardId) }
        }.getOrDefault(editableTags)
    }

    fun addTag(tag: String) {
        if (tag.isBlank()) return
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { HolderNative.addCardTag(cardId, tag) }
            }
            result.onFailure {
                snackbarHostState.showSnackbar("\"$tag\" isn't a valid tag.")
            }
            refreshTags()
            tagRefreshKey++ // Re-fetch project tags too -- a brand new tag should appear as a suggestion later.
        }
    }

    fun removeTag(tag: String) {
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { HolderNative.removeCardTag(cardId, tag) }
            }.getOrNull()
            if (result == HolderNative.RemoveTagResult.PRESENT_OUTSIDE_EDITABLE_TAG_LINE) {
                snackbarHostState.showSnackbar("\"$tag\" appears in the card text -- edit it there to remove it.")
            } else {
                refreshTags()
            }
        }
    }

    LaunchedEffect(cardId, projectId, refreshKey) {
        allCards = runCatching {
            withContext(Dispatchers.IO) { HolderNative.listCards(projectId) }
        }.getOrDefault(emptyList())
    }
    LaunchedEffect(cardId, refreshKey) { refreshTags() }
    LaunchedEffect(projectId, refreshKey, tagRefreshKey) {
        projectTags = runCatching {
            withContext(Dispatchers.IO) { HolderNative.listProjectTags(projectId) }
        }.getOrDefault(emptyList()).map { it.tag }
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .navigationBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ToolsBarButton(
                    icon = { Icon(Icons.Filled.Link, contentDescription = "Connections") },
                    label = "Connections",
                    onClick = onConnectionsClick,
                )
                ToolsBarButton(
                    icon = { Icon(Icons.Filled.DateRange, contentDescription = "Milestones") },
                    label = "Milestones",
                    onClick = onMilestonesClick,
                )
                ToolsBarButton(
                    icon = { Icon(painterResource(R.drawable.ic_file), contentDescription = "Resources") },
                    label = "Resources",
                    onClick = onResourcesClick,
                )
            }
        },
    ) { innerPadding ->
        val connectionsSummary = (linksState as? LoadState.Success)?.let {
            connectionsSummary(cardId, it.value, allCards)
        }
        val resourceAttachments = (linksState as? LoadState.Success)?.value?.outgoing
            ?.filter { it.toType == "resource" }

        LazyColumn(modifier = Modifier.padding(innerPadding)) {
            allCards.find { it.cardId == cardId }?.let { card ->
                item { CardVitalsLine(card, historySummary, onClick = onHistoryClick) }
            }

            item {
                TagsRow(
                    tags = tags,
                    editableTags = editableTags,
                    suggestions = projectTags,
                    onAdd = ::addTag,
                    onRemove = ::removeTag,
                    onTagClick = onTagClick,
                )
            }

            val connectionsVisible = when (linksState) {
                is LoadState.Loading, is LoadState.Error -> true
                is LoadState.Success -> connectionsSummary?.isEmpty == false
            }
            if (connectionsVisible) {
                item {
                    ToolTile(title = "Connections", onClick = onConnectionsClick) {
                        when (val state = linksState) {
                            is LoadState.Loading -> LoadingLine()
                            is LoadState.Error -> ErrorLine("Couldn't load connections: ${state.message}")
                            is LoadState.Success -> ConnectionsTileBody(
                                summary = requireNotNull(connectionsSummary),
                                onNavigateToCard = onNavigateToCard,
                            )
                        }
                    }
                }
            }

            if (milestones.isNotEmpty()) {
                item {
                    ToolTile(title = "Milestones", onClick = onMilestonesClick) {
                        MilestonesTileBody(milestones)
                    }
                }
            }

            // On error, the failure is already surfaced by the Connections tile above -- no
            // need for a second error line here, so the tile just stays hidden.
            val resourcesVisible = linksState is LoadState.Loading || !resourceAttachments.isNullOrEmpty()
            if (resourcesVisible) {
                item {
                    ToolTile(title = "Resources", onClick = onResourcesClick) {
                        when (linksState) {
                            is LoadState.Loading -> LoadingLine()
                            else -> ResourcesTileBody(requireNotNull(resourceAttachments))
                        }
                    }
                }
            }
        }
    }
}

private data class HistorySummary(val versionCount: Int, val hasMore: Boolean, val lastEditedAt: Long?)

/** A labeled icon action for the bottom bar, matching [CardViewScreen]'s own action buttons
 * exactly -- min 64dp wide with 4dp vertical padding around icon+label. */
@Composable
private fun ToolsBarButton(icon: @Composable () -> Unit, label: String, onClick: () -> Unit) {
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
private fun SummaryLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

/** [ConnectionsTileBody]'s data, computed once and shared with the tile's own visibility check
 * so "is this section empty" and "what does it show" can't drift apart. */
private data class ConnectionsSummary(
    val named: List<Triple<String, String, String>>,
    val childCount: Int,
    val outgoingCount: Int,
    val backlinkCount: Int,
) {
    val isEmpty: Boolean get() = named.isEmpty() && childCount == 0 && outgoingCount == 0 && backlinkCount == 0
}

private fun connectionsSummary(cardId: String, links: HolderCardLinks, allCards: List<HolderCard>): ConnectionsSummary {
    val sequence = cardSequenceLinks(cardId, links.parent?.cardId, allCards)
    val named = buildList {
        links.parent?.let { add(Triple("Parent", it.cardId, it.title)) }
        sequence.follows?.let { add(Triple("Follows", it.cardId, it.title)) }
        sequence.precedes?.let { add(Triple("Precedes", it.cardId, it.title)) }
        sequence.next?.let { add(Triple("Next", it.cardId, it.title)) }
        sequence.previous?.let { add(Triple("Previous", it.cardId, it.title)) }
    }.take(3)
    return ConnectionsSummary(
        named = named,
        childCount = links.children.size,
        outgoingCount = links.outgoing.count { it.toType != "resource" },
        backlinkCount = links.backlinks.size,
    )
}

/** Named/curated relationships (parent, sequence links, up to 3 total) shown individually and
 * directly tappable to the linked card -- separate from this tile's own onClick, which opens
 * the full relationship editor. Backlinks are Holder-derived, not user-curated, so they're
 * summarized as a count rather than previewed individually. */
@Composable
private fun ConnectionsTileBody(
    summary: ConnectionsSummary,
    onNavigateToCard: (cardId: String, title: String) -> Unit,
) {
    summary.named.forEach { (label, targetCardId, title) ->
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
        if (summary.childCount > 0) add("${summary.childCount} ${if (summary.childCount == 1) "child" else "children"}")
        if (summary.outgoingCount > 0) add("${summary.outgoingCount} other")
        if (summary.backlinkCount > 0) {
            add("${summary.backlinkCount} ${if (summary.backlinkCount == 1) "backlink" else "backlinks"}")
        }
    }
    if (extras.isNotEmpty()) {
        SummaryLine(extras.joinToString(" · "))
    }
}

/** Filename + icon/thumbnail preview for up to 2 attached resources, matching the format the
 * full Resources screen uses -- a bare count would throw away exactly the glanceable
 * information a preview is for. */
@Composable
private fun ResourcesTileBody(attachments: List<HolderOutgoingLink>) {
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

/** "Created Sep 9 · Edited 41 minutes ago · 5 versions" -- doubles as the entry point to the
 * full History timeline (via [onClick]), so there's no separate History tile: this line already
 * carries its summary, and a second tap target for the same screen would be redundant. Tinted
 * primary, matching the other tiles' headers, so it doesn't read as inert page furniture.
 *
 * Created reads from card metadata (cheap, always correct, available before History's own
 * fetch resolves) rather than from [HistorySummary], which is deliberate: listCardHistory is
 * paginated and can come back scanLimited, so for a card with enough history the "Card created"
 * event might not even be in the loaded page. Edited/versions still come from HistorySummary,
 * appended once it's loaded rather than blocking this line's first paint on the slower fetch. */
@Composable
private fun CardVitalsLine(card: HolderCard, historySummary: HistorySummary?, onClick: () -> Unit) {
    val created = "Created ${DateUtils.formatDateTime(null, card.createdAt * 1000, DateUtils.FORMAT_SHOW_DATE)}"
    val text = historySummary?.let { summary ->
        val edited = summary.lastEditedAt?.let {
            DateUtils.getRelativeTimeSpanString(it * 1000, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        }
        val versions = "${summary.versionCount}${if (summary.hasMore) "+" else ""} " +
            if (summary.versionCount == 1 && !summary.hasMore) "version" else "versions"
        if (edited != null) "$created · Edited $edited · $versions" else "$created · $versions"
    } ?: created
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** Tags as removable chips, always visible right at the top of the page -- small, cheap, and
 * glanceable enough that, unlike Connections/Resources/Milestones, they don't need a dedicated
 * destination screen for *managing* them. Tapping a chip's body (not its "x") goes to the same
 * cards-with-this-tag list reached by tapping a #tag inline in the card body -- tags are still
 * a real navigable connection, just to a set of cards rather than one. Tapping "+" opens an
 * inline text field with live suggestions from the project's other tags (tap one to add it
 * directly), rather than navigating away.
 *
 * A chip only gets a remove ("x") control when the tag is in [editableTags] -- i.e. it's on the
 * card's trailing tag line, so removeCardTag can actually act on it. A tag that only occurs in
 * prose gets no "x" at all, rather than one that's greyed out or that fails when tapped: an
 * always-present control that sometimes silently doesn't work reads as broken, whereas a chip
 * that simply has no remove control doesn't promise anything it can't deliver. The reactive
 * snackbar in onRemove stays as a defensive fallback (e.g. editableTags going stale between a
 * fetch and a tap), not the primary signal anymore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagsRow(
    tags: List<String>,
    editableTags: List<String>,
    suggestions: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onTagClick: (String) -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun submit(tag: String) {
        val trimmed = tag.trim()
        if (trimmed.isNotEmpty()) onAdd(trimmed)
        input = ""
        adding = false
        keyboard?.hide()
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        tags.forEach { tag ->
            InputChip(
                selected = false,
                onClick = { onTagClick(tag) },
                label = { Text(tag) },
                trailingIcon = if (tag in editableTags) {
                    {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Remove $tag",
                            modifier = Modifier.size(16.dp).clickable { onRemove(tag) },
                        )
                    }
                } else {
                    null
                },
            )
        }
        if (adding) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Tag") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit(input) }),
                modifier = Modifier.focusRequester(focusRequester),
            )
        } else {
            AssistChip(
                onClick = { adding = true },
                label = { Text("Add tag") },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }

    if (adding) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        val matches = suggestions
            .filter { it !in tags && (input.isBlank() || it.startsWith(input.trim(), ignoreCase = true)) }
            .take(5)
        if (matches.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            ) {
                matches.forEach { suggestion ->
                    AssistChip(onClick = { submit(suggestion) }, label = { Text(suggestion) })
                }
            }
        }
    }
}
