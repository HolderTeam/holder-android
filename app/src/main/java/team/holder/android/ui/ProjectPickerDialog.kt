package team.holder.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import team.holder.android.HolderProject

/**
 * "Which project should this go into" -- the same weight as [TextInputDialog] (a plain
 * AlertDialog, not a full screen), shown only when there's more than one project to choose
 * from. Tapping a row both selects and confirms in one tap; nothing is pre-chosen or
 * auto-confirmed, [homeProjectName] is only used to sort that project to the top since it's
 * the one most people land in most often.
 */
@Composable
fun ProjectPickerDialog(
    projects: List<HolderProject>,
    homeProjectName: String,
    onSelect: (HolderProject) -> Unit,
    onDismiss: () -> Unit,
) {
    val ordered = projects.sortedByDescending { it.name == homeProjectName }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share to which project?") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ordered.forEach { project ->
                    ListItem(
                        headlineContent = { Text(project.name) },
                        modifier = Modifier.clickable { onSelect(project) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
