package team.holder.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initialValue: String = "",
    confirmLabel: String = "OK",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    // An optional slot rendered below the text field, inside the scrollable region -- e.g.
    // ProjectListScreen's "Custom options" disclosure and its Encrypted/Synced/Visibility
    // choices, shown only for its "New project" call, not for the same dialog's reuse as the
    // Rename dialog. Kept generic rather than a dialog-specific parameter since this is a
    // shared, provider-agnostic component.
    extraContent: (@Composable () -> Unit)? = null,
    // A second, optional slot rendered BELOW extraContent's scrollable region, outside of it --
    // always visible regardless of scroll position, directly above Cancel/Create.
    // AlertDialog's own confirmButton/dismissButton row is a separate, always-reachable slot,
    // so anything genuinely important that lives inside the scrollable extraContent (e.g. the
    // Encrypted+Public warning) isn't guaranteed to actually be seen before the user taps
    // Create -- it could be scrolled out of view while Create stays perfectly tappable. This
    // slot exists specifically so that can't happen.
    pinnedContent: (@Composable () -> Unit)? = null,
) {
    var value by remember { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // Not scrollable itself -- only the inner Column (name field + extraContent) is,
            // via weight(1f) taking whatever space pinnedContent doesn't need. This is what
            // keeps pinnedContent always on-screen: Compose's Column measures non-weighted
            // children (pinnedContent) first, then gives the weighted scrollable Column
            // whatever's left, regardless of which one is declared first.
            Column {
                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                ) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text(label) },
                        singleLine = true,
                    )
                    extraContent?.invoke()
                }
                pinnedContent?.invoke()
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value) },
                enabled = value.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
