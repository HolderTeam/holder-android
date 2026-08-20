package team.holder.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import team.holder.android.HolderSettings
import team.holder.android.sync.GitSyncScheduler
import team.holder.android.ui.theme.HolderThemeOption
import team.holder.android.ui.theme.swatchColor

private val BACKGROUND_SYNC_INTERVAL_OPTIONS_MINUTES = listOf(15, 30, 60, 120)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val separateTitle by HolderSettings.separateTitleEnabled(context).collectAsState(initial = true)
    val backgroundSyncEnabled by HolderSettings.gitBackgroundSyncEnabled(context).collectAsState(initial = false)
    val backgroundSyncIntervalMinutes by HolderSettings.gitBackgroundSyncIntervalMinutes(context)
        .collectAsState(initial = HolderSettings.DEFAULT_BACKGROUND_SYNC_INTERVAL_MINUTES)
    var intervalMenuExpanded by remember { mutableStateOf(false) }
    val themeOption by HolderSettings.themeOption(context).collectAsState(initial = HolderThemeOption.SYSTEM)
    var themeMenuExpanded by remember { mutableStateOf(false) }

    // Keeps WorkManager's schedule in sync whenever either setting changes here, in addition
    // to the reconcile MainActivity does once at process start.
    LaunchedEffect(backgroundSyncEnabled, backgroundSyncIntervalMinutes) {
        GitSyncScheduler.reconcile(context, backgroundSyncEnabled, backgroundSyncIntervalMinutes)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxWidth().padding(innerPadding).padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Theme")
                    Text(themeOption.description)
                }
                Box {
                    Button(onClick = { themeMenuExpanded = true }) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(themeOption.swatchColor()),
                        )
                        Text(themeOption.label, modifier = Modifier.padding(start = 8.dp))
                    }
                    DropdownMenu(
                        expanded = themeMenuExpanded,
                        onDismissRequest = { themeMenuExpanded = false },
                    ) {
                        HolderThemeOption.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                leadingIcon = {
                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clip(CircleShape)
                                            .background(option.swatchColor()),
                                    )
                                },
                                onClick = {
                                    themeMenuExpanded = false
                                    scope.launch { HolderSettings.setThemeOption(context, option) }
                                },
                            )
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Separate title field")
                    Text(
                        text = if (separateTitle) {
                            "Cards have a distinct Title field, separate from the body."
                        } else {
                            "No Title field -- the first line of a card is its title."
                        },
                    )
                }
                Switch(
                    checked = separateTitle,
                    onCheckedChange = { enabled ->
                        scope.launch { HolderSettings.setSeparateTitleEnabled(context, enabled) }
                    },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Background git sync")
                    Text("Periodically pull and push projects with a remote configured, even when Holder isn't open. Uses battery and data.")
                }
                Switch(
                    checked = backgroundSyncEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch { HolderSettings.setGitBackgroundSyncEnabled(context, enabled) }
                    },
                )
            }

            if (backgroundSyncEnabled) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("Sync every", modifier = Modifier.weight(1f).padding(top = 12.dp))
                    Box {
                        Button(onClick = { intervalMenuExpanded = true }) {
                            Text("$backgroundSyncIntervalMinutes min")
                        }
                        DropdownMenu(
                            expanded = intervalMenuExpanded,
                            onDismissRequest = { intervalMenuExpanded = false },
                        ) {
                            BACKGROUND_SYNC_INTERVAL_OPTIONS_MINUTES.forEach { minutes ->
                                DropdownMenuItem(
                                    text = { Text("$minutes min") },
                                    onClick = {
                                        intervalMenuExpanded = false
                                        scope.launch {
                                            HolderSettings.setGitBackgroundSyncIntervalMinutes(context, minutes)
                                        }
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
