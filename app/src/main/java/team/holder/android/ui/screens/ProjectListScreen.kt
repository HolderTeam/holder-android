package team.holder.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import team.holder.android.HolderNative
import team.holder.android.HolderProject
import team.holder.android.ui.CenteredMessage
import team.holder.android.ui.LoadState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreen(onProjectClick: (HolderProject) -> Unit) {
    var state by remember { mutableStateOf<LoadState<List<HolderProject>>>(LoadState.Loading) }

    LaunchedEffect(Unit) {
        state = runCatching {
            withContext(Dispatchers.IO) { HolderNative.listProjects() }
        }.fold(
            onSuccess = { LoadState.Success(it) },
            onFailure = { LoadState.Error(it.message ?: it::class.java.simpleName) },
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Holder") }) }) { innerPadding ->
        when (val current = state) {
            is LoadState.Loading -> CenteredMessage(Modifier.padding(innerPadding)) {
                CircularProgressIndicator()
            }
            is LoadState.Error -> CenteredMessage(Modifier.padding(innerPadding)) {
                Text("Failed to load projects: ${current.message}")
            }
            is LoadState.Success -> {
                if (current.value.isEmpty()) {
                    CenteredMessage(Modifier.padding(innerPadding)) {
                        Text("No projects yet")
                    }
                } else {
                    LazyColumn(modifier = Modifier.padding(innerPadding)) {
                        items(current.value, key = { it.projectId }) { project ->
                            ListItem(
                                headlineContent = { Text(project.name) },
                                modifier = Modifier.clickable { onProjectClick(project) },
                            )
                        }
                    }
                }
            }
        }
    }
}
