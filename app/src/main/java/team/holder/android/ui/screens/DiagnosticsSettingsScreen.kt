package team.holder.android.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import team.holder.android.ui.CenteredMessage

/**
 * An activity log for this device -- sync attempts, saves, restores, and failures -- not a
 * programmer's console. Nothing records into it yet; this is just the empty destination
 * Settings > Diagnostics reaches, ready for real entries to land in a later slice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsSettingsScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        CenteredMessage(Modifier.padding(innerPadding)) {
            Text(
                "No activity recorded yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
