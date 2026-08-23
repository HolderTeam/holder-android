package team.holder.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.holder.android.HolderNative

private val DISPLAY_DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy")
private val DISPLAY_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
private val SUGGESTED_KINDS =
    listOf("Deadline", "Appointment", "Event", "Exam", "Birthday", "Expiry", "Renewal", "Service", "MOT")

/** Creates a milestone on cardId: a start date (defaulting to today), optionally a specific time
 * (all-day otherwise) and an end (a span rather than a point), plus a free-text kind and
 * description -- mirroring MILESTONE_IDEA.md's own mockup ("Date becomes start" once End is
 * added). Editing an existing milestone isn't supported yet; this screen only ever creates one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMilestoneScreen(
    cardId: String,
    onAdded: () -> Unit,
    onCancel: () -> Unit,
) {
    val startDateState = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
    var startTime by remember { mutableStateOf(LocalTime.NOON) }
    var allDay by remember { mutableStateOf(true) }
    var hasEnd by remember { mutableStateOf(false) }
    val endDateState = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
    var endTime by remember { mutableStateOf(LocalTime.NOON) }
    var kind by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var showStartDatePicker by remember { mutableStateOf(false) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    val startDate = startDateState.selectedLocalDate()
    val endDate = endDateState.selectedLocalDate()
    val startAt = startDate?.let { toEpochSeconds(it, if (allDay) LocalTime.MIDNIGHT else startTime) }
    val endAt = if (hasEnd) {
        endDate?.let { toEpochSeconds(it, if (allDay) LocalTime.MIDNIGHT else endTime) }
    } else {
        null
    }
    val endBeforeStart = startAt != null && endAt != null && endAt < startAt
    val canSave = startAt != null && !endBeforeStart && !saving

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add milestone") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
        ) {
            Text("Start", style = MaterialTheme.typography.labelLarge)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                OutlinedButton(onClick = { showStartDatePicker = true }) {
                    Text(startDate?.let { DISPLAY_DATE_FORMAT.format(it) } ?: "Pick a date")
                }
                if (!allDay) {
                    OutlinedButton(onClick = { showStartTimePicker = true }) {
                        Text(DISPLAY_TIME_FORMAT.format(startTime))
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text("All day", modifier = Modifier.padding(end = 8.dp))
                Switch(checked = allDay, onCheckedChange = { allDay = it })
            }

            if (!hasEnd) {
                TextButton(onClick = { hasEnd = true }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("+ Add end")
                }
            } else {
                Text("End", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    OutlinedButton(onClick = { showEndDatePicker = true }) {
                        Text(endDate?.let { DISPLAY_DATE_FORMAT.format(it) } ?: "Pick a date")
                    }
                    if (!allDay) {
                        OutlinedButton(onClick = { showEndTimePicker = true }) {
                            Text(DISPLAY_TIME_FORMAT.format(endTime))
                        }
                    }
                }
                TextButton(onClick = { hasEnd = false }, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Remove end")
                }
                if (endBeforeStart) {
                    Text(
                        "End is before start",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            Text("Kind", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 24.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                SUGGESTED_KINDS.forEach { option ->
                    FilterChip(
                        selected = kind == option,
                        onClick = { kind = if (kind == option) "" else option },
                        label = { Text(option) },
                    )
                }
            }
            OutlinedTextField(
                value = kind,
                onValueChange = { kind = it },
                label = { Text("Kind (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description (optional)") },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )

            errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }

            Button(
                enabled = canSave,
                onClick = {
                    val start = startAt ?: return@Button
                    saving = true
                    errorMessage = null
                    scope.launch {
                        val result = runCatching {
                            withContext(Dispatchers.IO) {
                                HolderNative.addCardMilestone(
                                    cardId = cardId,
                                    startAt = start,
                                    endAt = endAt,
                                    allDay = allDay,
                                    kind = kind.trim().ifEmpty { null },
                                    description = description.trim().ifEmpty { null },
                                )
                            }
                        }
                        saving = false
                        result.fold(
                            onSuccess = { onAdded() },
                            onFailure = { errorMessage = it.message ?: it::class.java.simpleName },
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                Text(if (saving) "Adding..." else "Add milestone")
            }
        }
    }

    if (showStartDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showStartDatePicker = false },
            confirmButton = {
                TextButton(onClick = { showStartDatePicker = false }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showStartDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = startDateState)
        }
    }
    if (showEndDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showEndDatePicker = false },
            confirmButton = {
                TextButton(onClick = { showEndDatePicker = false }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showEndDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = endDateState)
        }
    }
    if (showStartTimePicker) {
        TimePickerDialog(
            initial = startTime,
            onDismiss = { showStartTimePicker = false },
            onConfirm = {
                startTime = it
                showStartTimePicker = false
            },
        )
    }
    if (showEndTimePicker) {
        TimePickerDialog(
            initial = endTime,
            onDismiss = { showEndTimePicker = false },
            onConfirm = {
                endTime = it
                showEndTimePicker = false
            },
        )
    }
}

/** M3 has no ready-made TimePickerDialog (unlike DatePickerDialog) -- this is the standard
 * recipe: a plain TimePicker wrapped in a Dialog with our own Cancel/OK row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(initial: LocalTime, onDismiss: () -> Unit, onConfirm: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp),
        ) {
            TimePicker(state = state)
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) { Text("OK") }
            }
        }
    }
}

private fun DatePickerState.selectedLocalDate(): LocalDate? =
    selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }

private fun toEpochSeconds(date: LocalDate, time: LocalTime): Long =
    LocalDateTime.of(date, time).atZone(ZoneId.systemDefault()).toEpochSecond()
