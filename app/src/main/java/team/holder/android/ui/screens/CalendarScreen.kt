package team.holder.android.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.holder.android.HolderMilestone
import team.holder.android.HolderNative
import team.holder.android.ui.CenteredMessage
import team.holder.android.ui.LoadState

// A generous, human-scale window rather than an unbounded query -- covers essentially any
// realistic milestone (birthdays, renewals, deadlines years out) without relying on SQL
// comparisons against Long extremes.
private const val YEARS_BACK = 5L
private const val YEARS_FORWARD = 10L
private val CALENDAR_ZONE = ZoneId.systemDefault()
private val DAY_HEADER_FORMAT = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")
private val MILESTONE_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm").withZone(CALENDAR_ZONE)

/** Every milestone across projectId, grouped by day and ordered chronologically -- the
 * project-level view MILESTONE_IDEA.md calls "the Calendar". Lands scrolled to today (or the
 * next upcoming day, if nothing's on today) rather than the oldest entry. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CalendarScreen(
    projectId: String,
    refreshKey: Any,
    onNavigateToCard: (cardId: String, title: String) -> Unit,
    onBack: () -> Unit,
) {
    var state by remember(projectId) { mutableStateOf<LoadState<List<HolderMilestone>>>(LoadState.Loading) }
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
        when (val loaded = state) {
            is LoadState.Loading -> CenteredMessage(Modifier.fillMaxSize().padding(innerPadding)) {
                CircularProgressIndicator()
            }
            is LoadState.Error -> CenteredMessage(Modifier.fillMaxSize().padding(innerPadding)) {
                Text("Failed to load milestones: ${loaded.message}")
            }
            is LoadState.Success -> {
                if (loaded.value.isEmpty()) {
                    CenteredMessage(Modifier.fillMaxSize().padding(innerPadding)) {
                        Text("No milestones yet")
                    }
                } else {
                    val grouped = loaded.value
                        .groupBy { Instant.ofEpochSecond(it.startAt).atZone(CALENDAR_ZONE).toLocalDate() }
                        .toSortedMap()
                    val today = LocalDate.now(CALENDAR_ZONE)
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

                    LazyColumn(state = listState, modifier = Modifier.padding(innerPadding)) {
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
