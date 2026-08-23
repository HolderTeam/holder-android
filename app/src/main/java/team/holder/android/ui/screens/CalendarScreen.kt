package team.holder.android.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.holder.android.HolderCard
import team.holder.android.HolderMilestone
import team.holder.android.HolderNative
import team.holder.android.ui.CenteredMessage
import team.holder.android.ui.LoadState

// A generous, human-scale window rather than an unbounded query -- covers essentially any
// realistic milestone (birthdays, renewals, deadlines years out) without relying on SQL
// comparisons against Long extremes. The month grid pages within this same fetch (no re-query
// per month) since it's already wide enough for realistic browsing.
private const val YEARS_BACK = 5L
private const val YEARS_FORWARD = 10L
private val CALENDAR_ZONE = ZoneId.systemDefault()
private val DAY_HEADER_FORMAT = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")
private val MILESTONE_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm").withZone(CALENDAR_ZONE)
private val MONTH_YEAR_FORMAT = DateTimeFormatter.ofPattern("MMMM yyyy")

private fun Long.toLocalDate(): LocalDate = Instant.ofEpochSecond(this).atZone(CALENDAR_ZONE).toLocalDate()

/** Every milestone across projectId: a month grid (milestone/created/updated days marked) above
 * a chronological list, the project-level view MILESTONE_IDEA.md calls "the Calendar". The list
 * lands scrolled to today by default; tapping a day in the grid swaps it for that day's detail
 * (milestones due, cards created/updated) instead, since that's information the flat list -- Milestone
 * only -- doesn't otherwise show. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CalendarScreen(
    projectId: String,
    refreshKey: Any,
    onNavigateToCard: (cardId: String, title: String) -> Unit,
    onBack: () -> Unit,
) {
    val today = LocalDate.now(CALENDAR_ZONE)
    var state by remember(projectId) { mutableStateOf<LoadState<List<HolderMilestone>>>(LoadState.Loading) }
    var allCards by remember(projectId) { mutableStateOf<List<HolderCard>>(emptyList()) }
    var visibleMonth by remember(projectId) { mutableStateOf(YearMonth.from(today)) }
    var selectedDay by remember(projectId) { mutableStateOf<LocalDate?>(null) }
    var menuOpenFor by remember { mutableStateOf<String?>(null) }
    var pendingRemove by remember { mutableStateOf<HolderMilestone?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        state = runCatching {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis() / 1000
                HolderNative.listMilestonesInRange(
                    projectId,
                    now - YEARS_BACK * 365 * 24 * 60 * 60,
                    now + YEARS_FORWARD * 365 * 24 * 60 * 60,
                )
            }
        }.fold(
            onSuccess = { LoadState.Success(it) },
            onFailure = { LoadState.Error(it.message ?: it::class.java.simpleName) },
        )
    }

    LaunchedEffect(projectId, refreshKey) { refresh() }
    LaunchedEffect(projectId, refreshKey) {
        allCards = runCatching {
            withContext(Dispatchers.IO) { HolderNative.listCards(projectId) }
        }.getOrDefault(emptyList())
    }

    fun remove(milestone: HolderMilestone) {
        if (isSubmitting) return
        isSubmitting = true
        pendingRemove = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    HolderNative.removeCardMilestone(milestone.cardId, milestone.milestoneId)
                }
            }
            isSubmitting = false
            refresh()
        }
    }

    val milestones = (state as? LoadState.Success)?.value ?: emptyList()
    val milestoneDays = milestones.map { it.startAt.toLocalDate() }.toSet()
    val createdDays = allCards.map { it.createdAt.toLocalDate() }.toSet()
    // Dedup with created, same rule the About screen uses: a card touched only once shows as
    // "created", not "created and updated" -- otherwise every fresh card lights two dots.
    val updatedDays = allCards
        .filter { it.updatedAt != it.createdAt }
        .map { it.updatedAt.toLocalDate() }
        .toSet()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calendar") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            MonthGrid(
                month = visibleMonth,
                today = today,
                selectedDay = selectedDay,
                milestoneDays = milestoneDays,
                createdDays = createdDays,
                updatedDays = updatedDays,
                onPreviousMonth = { visibleMonth = visibleMonth.minusMonths(1) },
                onNextMonth = { visibleMonth = visibleMonth.plusMonths(1) },
                onToday = { visibleMonth = YearMonth.from(today) },
                onDayClick = { date -> selectedDay = if (selectedDay == date) null else date },
            )
            HorizontalDivider()

            val selected = selectedDay
            if (selected != null) {
                DayDetailPanel(
                    date = selected,
                    milestones = milestones.filter { it.startAt.toLocalDate() == selected },
                    createdCards = allCards.filter { it.createdAt.toLocalDate() == selected },
                    updatedCards = allCards.filter {
                        it.updatedAt != it.createdAt && it.updatedAt.toLocalDate() == selected
                    },
                    menuOpenFor = menuOpenFor,
                    onDismiss = { selectedDay = null },
                    onNavigateToCard = onNavigateToCard,
                    onLongClickMilestone = { menuOpenFor = it },
                    onDismissMenu = { menuOpenFor = null },
                    onRemoveRequested = {
                        menuOpenFor = null
                        pendingRemove = it
                    },
                    modifier = Modifier.weight(1f),
                )
            } else {
                when (val loaded = state) {
                    is LoadState.Loading -> CenteredMessage(Modifier.weight(1f)) { CircularProgressIndicator() }
                    is LoadState.Error -> CenteredMessage(Modifier.weight(1f)) {
                        Text("Failed to load milestones: ${loaded.message}")
                    }
                    is LoadState.Success -> {
                        if (loaded.value.isEmpty()) {
                            CenteredMessage(Modifier.weight(1f)) { Text("No milestones yet") }
                        } else {
                            val grouped = loaded.value
                                .groupBy { it.startAt.toLocalDate() }
                                .toSortedMap()
                            var scrollTarget = 0
                            var flatIndex = 0
                            for ((date, dayMilestones) in grouped) {
                                if (scrollTarget == 0 && !date.isBefore(today)) {
                                    scrollTarget = flatIndex
                                }
                                flatIndex += 1 + dayMilestones.size
                            }

                            LaunchedEffect(grouped.keys) {
                                listState.animateScrollToItem(scrollTarget)
                            }

                            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                                grouped.forEach { (date, dayMilestones) ->
                                    item { DayHeader(date, today) }
                                    items(dayMilestones, key = { it.milestoneId }) { milestone ->
                                        MilestoneRow(
                                            milestone = milestone,
                                            expanded = menuOpenFor == milestone.milestoneId,
                                            onClick = {
                                                onNavigateToCard(milestone.cardId, milestone.cardTitle ?: "")
                                            },
                                            onLongClick = { menuOpenFor = milestone.milestoneId },
                                            onDismissMenu = { menuOpenFor = null },
                                            onRemoveRequested = {
                                                menuOpenFor = null
                                                pendingRemove = milestone
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

    pendingRemove?.let { milestone ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("Remove this milestone?") },
            confirmButton = {
                TextButton(onClick = { remove(milestone) }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    today: LocalDate,
    selectedDay: LocalDate?,
    milestoneDays: Set<LocalDate>,
    createdDays: Set<LocalDate>,
    updatedDays: Set<LocalDate>,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onToday: () -> Unit,
    onDayClick: (LocalDate) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onPreviousMonth) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
            }
            Text(
                MONTH_YEAR_FORMAT.format(month),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onNextMonth) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
            }
            TextButton(onClick = onToday) { Text("Today") }
        }

        val firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
        Row(modifier = Modifier.fillMaxWidth()) {
            repeat(7) { offset ->
                val dow: DayOfWeek = firstDayOfWeek.plus(offset.toLong())
                Text(
                    dow.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        val firstOfMonth = month.atDay(1)
        val leadingDays = (firstOfMonth.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
        val gridStart = firstOfMonth.minusDays(leadingDays.toLong())
        val totalCells = ((month.lengthOfMonth() + leadingDays + 6) / 7) * 7
        for (weekStart in 0 until totalCells step 7) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (offset in 0 until 7) {
                    val date = gridStart.plusDays((weekStart + offset).toLong())
                    DayCell(
                        date = date,
                        inCurrentMonth = YearMonth.from(date) == month,
                        isToday = date == today,
                        isSelected = date == selectedDay,
                        hasMilestone = date in milestoneDays,
                        hasCreated = date in createdDays,
                        hasUpdated = date in updatedDays,
                        onClick = { onDayClick(date) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    inCurrentMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    hasMilestone: Boolean,
    hasCreated: Boolean,
    hasUpdated: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .aspectRatio(1f)
            .clickable(enabled = inCurrentMonth, onClick = onClick)
            .padding(2.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(28.dp)
                .then(
                    if (isSelected) {
                        Modifier.background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                    } else {
                        Modifier
                    }
                ),
        ) {
            Text(
                date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    !inCurrentMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                    isToday -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.height(6.dp)) {
            // Order follows significance: a milestone matters more than a card merely having
            // been touched, and creating a card is more notable than editing one.
            if (hasMilestone) Dot(MaterialTheme.colorScheme.tertiary)
            if (hasCreated) Dot(MaterialTheme.colorScheme.primary)
            if (hasUpdated) Dot(MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(modifier = Modifier.size(4.dp).background(color, CircleShape))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayDetailPanel(
    date: LocalDate,
    milestones: List<HolderMilestone>,
    createdCards: List<HolderCard>,
    updatedCards: List<HolderCard>,
    menuOpenFor: String?,
    onDismiss: () -> Unit,
    onNavigateToCard: (cardId: String, title: String) -> Unit,
    onLongClickMilestone: (String) -> Unit,
    onDismissMenu: () -> Unit,
    onRemoveRequested: (HolderMilestone) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
        ) {
            Text(
                DAY_HEADER_FORMAT.format(date),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("Show all") }
        }

        if (milestones.isEmpty() && createdCards.isEmpty() && updatedCards.isEmpty()) {
            CenteredMessage(Modifier.weight(1f)) { Text("Nothing on this day") }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                if (milestones.isNotEmpty()) {
                    item { CalendarSectionHeader("Milestones") }
                    items(milestones, key = { "m:${it.milestoneId}" }) { milestone ->
                        MilestoneRow(
                            milestone = milestone,
                            expanded = menuOpenFor == milestone.milestoneId,
                            onClick = { onNavigateToCard(milestone.cardId, milestone.cardTitle ?: "") },
                            onLongClick = { onLongClickMilestone(milestone.milestoneId) },
                            onDismissMenu = onDismissMenu,
                            onRemoveRequested = { onRemoveRequested(milestone) },
                        )
                    }
                }
                if (createdCards.isNotEmpty()) {
                    item { CalendarSectionHeader("Created") }
                    items(createdCards, key = { "c:${it.cardId}" }) { card ->
                        CardActivityRow(card.title) { onNavigateToCard(card.cardId, card.title) }
                    }
                }
                if (updatedCards.isNotEmpty()) {
                    item { CalendarSectionHeader("Updated") }
                    items(updatedCards, key = { "u:${it.cardId}" }) { card ->
                        CardActivityRow(card.title) { onNavigateToCard(card.cardId, card.title) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarSectionHeader(title: String) {
    Column(modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun CardActivityRow(title: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun DayHeader(date: LocalDate, today: LocalDate) {
    Column(modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp)) {
        Text(
            if (date == today) "Today · ${DAY_HEADER_FORMAT.format(date)}" else DAY_HEADER_FORMAT.format(date),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MilestoneRow(
    milestone: HolderMilestone,
    expanded: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDismissMenu: () -> Unit,
    onRemoveRequested: () -> Unit,
) {
    Box {
        ListItem(
            headlineContent = { Text(milestone.cardTitle?.ifEmpty { "Untitled card" } ?: "Untitled card") },
            supportingContent = { Text(milestoneSupportingText(milestone)) },
            modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = onDismissMenu) {
            DropdownMenuItem(text = { Text("Remove") }, onClick = onRemoveRequested)
        }
    }
}

/** "Renewal · 09:30 · Car insurance renewal" -- kind, then time (omitted for an all-day
 * milestone), then description, only the parts that are actually set. */
private fun milestoneSupportingText(milestone: HolderMilestone): String {
    val parts = mutableListOf<String>()
    milestone.kind?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
    if (!milestone.allDay) {
        parts.add(MILESTONE_TIME_FORMAT.format(Instant.ofEpochSecond(milestone.startAt)))
    }
    milestone.description?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
    return if (parts.isEmpty()) "Milestone" else parts.joinToString(" · ")
}
