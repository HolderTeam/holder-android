package team.holder.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import team.holder.android.HolderCardRef
import team.holder.android.HolderNative
import team.holder.android.ui.CenteredMessage
import team.holder.android.ui.LoadState

/** Shows every (non-trashed) card in a project carrying a given #tag, reached by tapping a tag
 * span rendered in HolderMarkdownViewer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagResultsScreen(
    projectId: String,
    tag: String,
    onNavigateToCard: (cardId: String, title: String) -> Unit,
    onBack: () -> Unit,
) {
    var state by remember(projectId, tag) { mutableStateOf<LoadState<List<HolderCardRef>>>(LoadState.Loading) }

    LaunchedEffect(projectId, tag) {
        state = runCatching {
            withContext(Dispatchers.IO) { HolderNative.cardsWithTag(projectId, tag) }
        }.fold(
            onSuccess = { LoadState.Success(it) },
            onFailure = { LoadState.Error(it.message ?: it::class.java.simpleName) },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("#$tag") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        when (val current = state) {
            is LoadState.Loading -> CenteredMessage(Modifier.fillMaxSize()) { CircularProgressIndicator() }
            is LoadState.Error ->
                CenteredMessage(Modifier.fillMaxSize()) { Text("Failed to load #$tag: ${current.message}") }
            is LoadState.Success -> {
                if (current.value.isEmpty()) {
                    CenteredMessage(Modifier.fillMaxSize()) { Text("No cards tagged #$tag") }
                } else {
                    LazyColumn(modifier = Modifier.padding(innerPadding)) {
                        items(current.value, key = { it.cardId }) { card ->
                            ListItem(
                                headlineContent = { Text(card.title) },
                                modifier = Modifier.clickable { onNavigateToCard(card.cardId, card.title) },
                            )
                        }
                    }
                }
            }
        }
    }
}
