package team.holder.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.holder.android.HolderLinkKind
import team.holder.android.HolderNative
import team.holder.android.HolderSearchResult
import team.holder.android.ui.CenteredMessage
import team.holder.android.ui.LoadState

private const val SEARCH_DEBOUNCE_MS = 300L
private val DEFAULT_KINDS = listOf("ref", "depends_on", "example_of", "blocks", "related_to")
private const val OTHER_KIND_CHIP = "Other"

/** Picks a target card (by search, since there's no card_id to browse from on this screen) plus
 * a relationship kind and optional label, then calls HolderNative.addCardLink. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddConnectionScreen(
    fromCardId: String,
    projectId: String,
    onAdded: () -> Unit,
    onCancel: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var searchState by remember { mutableStateOf<LoadState<List<HolderSearchResult>>?>(null) }
    var target by remember { mutableStateOf<HolderSearchResult?>(null) }
    var selectedKind by remember { mutableStateOf(DEFAULT_KINDS.first()) }
    var customKind by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var linkKinds by remember { mutableStateOf<List<HolderLinkKind>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        linkKinds = runCatching {
            withContext(Dispatchers.IO) { HolderNative.listLinkKinds() }
        }.getOrDefault(emptyList())
    }

    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            searchState = null
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        searchState = LoadState.Loading
        searchState = runCatching {
            withContext(Dispatchers.IO) { HolderNative.searchCards(projectId, trimmed) }
        }.fold(
            onSuccess = { LoadState.Success(it.filter { result -> result.cardId != fromCardId }) },
            onFailure = { LoadState.Error(it.message ?: it::class.java.simpleName) },
        )
    }

    val kind = if (selectedKind == OTHER_KIND_CHIP) customKind.trim() else selectedKind
    val canAdd = target != null && kind.isNotEmpty() && !saving

    // Suggests built-in kinds as the user types a custom one, so they don't reinvent
    // "depends_on" as "requires_completion_of" without knowing the catalog already has a
    // curated forward/reverse label for it. Hides once the field exactly matches a suggestion
    // -- nothing left to suggest at that point.
    val customKindTrimmed = customKind.trim()
    val kindSuggestions = if (customKindTrimmed.isEmpty()) {
        emptyList()
    } else {
        linkKinds.filter { candidate ->
            candidate.id != customKindTrimmed &&
                (
                    candidate.id.contains(customKindTrimmed, ignoreCase = true) ||
                        candidate.forward.contains(customKindTrimmed, ignoreCase = true)
                    )
        }.take(6)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add connection") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp)) {
            val selected = target
            if (selected == null) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search for a card to connect to") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                when (val state = searchState) {
                    null -> {}
                    is LoadState.Loading -> CenteredMessage { CircularProgressIndicator() }
                    is LoadState.Error -> CenteredMessage { Text("Search failed: ${state.message}") }
                    is LoadState.Success -> {
                        if (state.value.isEmpty()) {
                            CenteredMessage { Text("No matching cards") }
                        } else {
                            LazyColumn {
                                items(state.value, key = { it.cardId }) { result ->
                                    ListItem(
                                        headlineContent = { Text(result.title) },
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            target = result
                                            query = ""
                                            searchState = null
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                ListItem(
                    headlineContent = { Text(selected.title) },
                    supportingContent = { Text("Connecting to this card") },
                    trailingContent = {
                        IconButton(onClick = { target = null }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Change target card")
                        }
                    },
                )
            }

            Text("Relationship", modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (DEFAULT_KINDS + OTHER_KIND_CHIP).forEach { option ->
                    FilterChip(
                        selected = selectedKind == option,
                        onClick = { selectedKind = option },
                        label = { Text(option) },
                    )
                }
            }
            if (selectedKind == OTHER_KIND_CHIP) {
                OutlinedTextField(
                    value = customKind,
                    onValueChange = { customKind = it },
                    label = { Text("Custom relationship") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                kindSuggestions.forEach { suggestion ->
                    ListItem(
                        headlineContent = { Text(suggestion.forward) },
                        supportingContent = { Text(suggestion.id) },
                        modifier = Modifier.fillMaxWidth().clickable { customKind = suggestion.id },
                    )
                }
            }

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )

            errorMessage?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }

            Button(
                enabled = canAdd,
                onClick = {
                    val toCardId = target?.cardId ?: return@Button
                    saving = true
                    errorMessage = null
                    scope.launch {
                        val result = runCatching {
                            withContext(Dispatchers.IO) {
                                HolderNative.addCardLink(fromCardId, toCardId, kind, label.trim().ifEmpty { null })
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
                Text(if (saving) "Adding..." else "Add connection")
            }
        }
    }
}
