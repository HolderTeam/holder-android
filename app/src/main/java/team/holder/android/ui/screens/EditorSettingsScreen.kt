package team.holder.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import team.holder.android.HolderSettings

/** How cards themselves are edited -- title field, trailing-whitespace handling -- as opposed
 * to AppearanceSettingsScreen's how the app looks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val separateTitle by HolderSettings.separateTitleEnabled(context).collectAsState(initial = true)
    val preserveTrailingWhitespace by HolderSettings.preserveTrailingWhitespace(context).collectAsState(initial = false)
    val trimTwoSpaceLineEndings by HolderSettings.trimTwoSpaceLineEndings(context).collectAsState(initial = false)
    val trimWhitespaceInCodeBlocks by HolderSettings.trimWhitespaceInCodeBlocks(context).collectAsState(initial = false)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Editor") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Use first line as title")
                    Text("Instead of a separate title field.")
                }
                // Inverted at this boundary only -- HolderSettings.separateTitleEnabled (and its
                // "true" default) stays exactly as CardEditScreen/CardViewScreen already read it
                // everywhere else; this row just presents and toggles its opposite, since the
                // feature worth naming and defaulting off is the opt-in one (first line as
                // title), not the default distinct-title-field behavior.
                Switch(
                    checked = !separateTitle,
                    onCheckedChange = { useHeadingAsTitle ->
                        scope.launch { HolderSettings.setSeparateTitleEnabled(context, !useHeadingAsTitle) }
                    },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Preserve trailing whitespace")
                    Text("Keeps spaces and tabs at the ends of lines.")
                }
                Switch(
                    checked = preserveTrailingWhitespace,
                    onCheckedChange = { enabled ->
                        scope.launch { HolderSettings.setPreserveTrailingWhitespace(context, enabled) }
                    },
                )
            }

            if (!preserveTrailingWhitespace) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Trim two-space hard breaks")
                        Text("Two invisible trailing spaces can force a Markdown line break.")
                    }
                    Switch(
                        checked = trimTwoSpaceLineEndings,
                        onCheckedChange = { enabled ->
                            scope.launch { HolderSettings.setTrimTwoSpaceLineEndings(context, enabled) }
                        },
                    )
                }

                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Trim trailing whitespace in code")
                        Text("Code blocks normally preserve their contents literally.")
                    }
                    Switch(
                        checked = trimWhitespaceInCodeBlocks,
                        onCheckedChange = { enabled ->
                            scope.launch { HolderSettings.setTrimWhitespaceInCodeBlocks(context, enabled) }
                        },
                    )
                }
            }
        }
    }
}
