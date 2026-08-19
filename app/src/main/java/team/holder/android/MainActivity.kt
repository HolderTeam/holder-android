package team.holder.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.holder.android.ui.CenteredMessage
import team.holder.android.ui.screens.CardEditScreen
import team.holder.android.ui.screens.CardListScreen
import team.holder.android.ui.screens.CardViewScreen
import team.holder.android.ui.screens.ProjectListScreen
import team.holder.android.ui.theme.HolderTheme
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val initError = runCatching {
            HolderNative.initialize(
                dataDir = File(filesDir, "holder"),
                schemaSql = assets.open("schema.sql").bufferedReader().use { it.readText() },
                welcomeContent = assets.open("WELCOME.md").bufferedReader().use { it.readText() },
            )
        }.exceptionOrNull()

        enableEdgeToEdge()
        setContent {
            HolderTheme {
                if (initError != null) {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        CenteredMessage(Modifier.padding(innerPadding)) {
                            Text("Failed to open Holder store: ${initError.message ?: initError::class.java.simpleName}")
                        }
                    }
                } else {
                    HolderNavHost()
                }
            }
        }
    }
}

@Composable
private fun HolderNavHost() {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()

    var selectedProjectName by remember { mutableStateOf("") }
    var selectedCardTitle by remember { mutableStateOf("") }
    var selectedCardContent by remember { mutableStateOf("") }
    var cardListRefreshKey by remember { mutableIntStateOf(0) }
    var cardViewRefreshKey by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    NavHost(navController = navController, startDestination = "projects") {
        composable("projects") {
            ProjectListScreen(
                onProjectClick = { project ->
                    selectedProjectName = project.name
                    navController.navigate("projects/${project.projectId}/cards")
                },
            )
        }
        composable("projects/{projectId}/cards") { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId").orEmpty()
            CardListScreen(
                projectId = projectId,
                projectName = selectedProjectName,
                refreshKey = cardListRefreshKey,
                onCardClick = { cardId, title ->
                    selectedCardTitle = title
                    navController.navigate("projects/$projectId/cards/$cardId")
                },
                onCreateCard = {
                    saveError = null
                    navController.navigate("projects/$projectId/cards/new")
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable("projects/{projectId}/cards/new") { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId").orEmpty()
            CardEditScreen(
                screenTitle = "New card",
                initialTitle = "",
                initialContent = "",
                saving = saving,
                errorMessage = saveError,
                onSave = { title, content ->
                    // CardEditScreen's own one-shot guard is what actually prevents a double-tap
                    // from calling this twice; this is just a secondary guard against onSave
                    // itself somehow firing twice concurrently.
                    if (!saving) {
                        saving = true
                        saveError = null
                        scope.launch {
                            val result = runCatching {
                                withContext(Dispatchers.IO) { HolderNative.createCard(projectId, title, content) }
                            }
                            saving = false
                            result.fold(
                                onSuccess = {
                                    cardListRefreshKey++
                                    navController.popBackStack()
                                },
                                onFailure = { saveError = it.message ?: it::class.java.simpleName },
                            )
                        }
                    }
                },
                onCancel = { navController.popBackStack() },
            )
        }
        composable("projects/{projectId}/cards/{cardId}") { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId").orEmpty()
            val cardId = backStackEntry.arguments?.getString("cardId").orEmpty()
            CardViewScreen(
                cardId = cardId,
                projectId = projectId,
                cardTitle = selectedCardTitle,
                refreshKey = cardViewRefreshKey,
                onEdit = { content ->
                    selectedCardContent = content
                    saveError = null
                    navController.navigate("cards/$cardId/edit")
                },
                onNavigateToCard = { targetCardId, title ->
                    selectedCardTitle = title
                    cardListRefreshKey++
                    navController.navigate("projects/$projectId/cards/$targetCardId")
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable("cards/{cardId}/edit") { backStackEntry ->
            val cardId = backStackEntry.arguments?.getString("cardId").orEmpty()
            CardEditScreen(
                screenTitle = "Edit card",
                initialTitle = selectedCardTitle,
                initialContent = selectedCardContent,
                saving = saving,
                errorMessage = saveError,
                onSave = { title, content ->
                    // See the comment in the "new card" route above.
                    if (!saving) {
                        saving = true
                        saveError = null
                        scope.launch {
                            val result = runCatching {
                                withContext(Dispatchers.IO) { HolderNative.updateCard(cardId, title, content) }
                            }
                            saving = false
                            result.fold(
                                onSuccess = {
                                    selectedCardTitle = title
                                    cardViewRefreshKey++
                                    cardListRefreshKey++
                                    navController.popBackStack()
                                },
                                onFailure = { saveError = it.message ?: it::class.java.simpleName },
                            )
                        }
                    }
                },
                onCancel = { navController.popBackStack() },
            )
        }
    }
}
