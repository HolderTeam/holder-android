package team.holder.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import team.holder.android.ui.theme.HolderFontFamilyOption
import team.holder.android.ui.theme.HolderFontSizeOption
import team.holder.android.ui.theme.HolderThemeOption
import team.holder.android.ui.theme.swatchColor

/** Theme, font size, and font -- how the app looks, as opposed to EditorSettingsScreen's how
 * cards themselves are edited. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val themeOption by HolderSettings.themeOption(context).collectAsState(initial = HolderThemeOption.SYSTEM)
    var themeMenuExpanded by remember { mutableStateOf(false) }
    val fontSizeOption by HolderSettings.fontSizeOption(context).collectAsState(initial = HolderFontSizeOption.SYSTEM)
    var fontSizeMenuExpanded by remember { mutableStateOf(false) }
    val fontFamilyOption by HolderSettings.fontFamilyOption(context)
        .collectAsState(initial = HolderFontFamilyOption.DEFAULT)
    var fontFamilyMenuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appearance") },
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
                    Text("Font size")
                    Text(
                        if (fontSizeOption == HolderFontSizeOption.SYSTEM) {
                            "Follows your device's font size setting."
                        } else {
                            "Overrides your device's font size setting."
                        },
                    )
                }
                Box {
                    Button(onClick = { fontSizeMenuExpanded = true }) {
                        Text(fontSizeOption.label)
                    }
                    DropdownMenu(
                        expanded = fontSizeMenuExpanded,
                        onDismissRequest = { fontSizeMenuExpanded = false },
                    ) {
                        HolderFontSizeOption.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    fontSizeMenuExpanded = false
                                    scope.launch { HolderSettings.setFontSizeOption(context, option) }
                                },
                            )
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Font")
                    Text("The typeface used throughout the app.")
                }
                Box {
                    Button(onClick = { fontFamilyMenuExpanded = true }) {
                        Text("Aa", fontFamily = fontFamilyOption.fontFamily)
                        Text(fontFamilyOption.label, modifier = Modifier.padding(start = 8.dp))
                    }
                    DropdownMenu(
                        expanded = fontFamilyMenuExpanded,
                        onDismissRequest = { fontFamilyMenuExpanded = false },
                    ) {
                        HolderFontFamilyOption.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                leadingIcon = { Text("Aa", fontFamily = option.fontFamily) },
                                onClick = {
                                    fontFamilyMenuExpanded = false
                                    scope.launch { HolderSettings.setFontFamilyOption(context, option) }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
