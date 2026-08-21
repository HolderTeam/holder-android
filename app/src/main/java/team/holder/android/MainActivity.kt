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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.holder.android.sync.GitSyncScheduler
import team.holder.android.ui.CenteredMessage
import team.holder.android.ui.screens.AddConnectionScreen
import team.holder.android.ui.screens.CardEditScreen
import team.holder.android.ui.screens.ConnectionsScreen
import team.holder.android.ui.screens.CardListScreen
import team.holder.android.ui.screens.CardViewScreen
import team.holder.android.ui.screens.GitSyncScreen
import team.holder.android.ui.screens.ProjectListScreen
import team.holder.android.ui.screens.RecoverProjectScreen
import team.holder.android.ui.screens.SettingsScreen
import team.holder.android.ui.screens.TrashScreen
import team.holder.android.ui.theme.HolderTheme
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val initError = runCatching {
            HolderNative.initialize(
                context = this,
                dataDir = File(filesDir, "holder"),
                schemaSql = assets.open("schema.sql").bufferedReader().use { it.readText() },
                welcomeContent = assets.open("WELCOME.md").bufferedReader().use { it.readText() },
            )
        }.exceptionOrNull()

        // Restores the background-sync schedule on every process start: a fresh process has
        // no memory of a periodic work request enqueued in a past one, only what WorkManager
        // itself persisted -- re-deriving it from settings here keeps the two in sync even if
        // e.g. the app was reinstalled or the setting changed while the process was dead.
        lifecycleScope.launch {
            val enabled = HolderSettings.gitBackgroundSyncEnabled(applicationContext).first()
            val intervalMinutes = HolderSettings.gitBackgroundSyncIntervalMinutes(applicationContext).first()
            GitSyncScheduler.reconcile(applicationContext, enabled, intervalMinutes)
        }

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

    // rememberSaveable: these back the app-bar titles on CardListScreen/CardViewScreen, which
    // otherwise fall back to a generic label ("Cards"/"Card") until the next navigation if the
    // process is recreated. selectedCardContent doesn't need this: it only ever seeds
    // CardEditScreen's initial state, and that screen's own fields already restore themselves.
    var selectedProjectName by rememberSaveable { mutableStateOf("") }
    var selectedProjectForSync by remember { mutableStateOf<HolderProject?>(null) }
    var selectedCardTitle by rememberSaveable { mutableStateOf("") }
    var selectedCardContent by remember { mutableStateOf("") }
    var cardListRefreshKey by remember { mutableIntStateOf(0) }
    var cardViewRefreshKey by remember { mutableIntStateOf(0) }
    var connectionsRefreshKey by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    NavHost(navController = navController, startDestination = "projects") {
        composable("projects") {
            ProjectListScreen(
                onProjectClick = { project ->
                    selectedProjectName = project.name
                    navController.navigate("projects/${project.projectId}/cards")
                },
                onGitSyncClick = { project ->
                    selectedProjectForSync = project
                    navController.navigate("projects/${project.projectId}/git-sync")
                },
                onRecoverProjectClick = { navController.navigate("recover-project") },
                onSettingsClick = { navController.navigate("settings") },
            )
        }
        composable("settings") {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable("recover-project") {
            RecoverProjectScreen(onBack = { navController.popBackStack() })
        }
        composable("projects/{projectId}/git-sync") {
            selectedProjectForSync?.let { project ->
                GitSyncScreen(project = project, onBack = { navController.popBackStack() })
            }
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
                onTrashClick = { navController.navigate("projects/$projectId/trash") },
                onBack = { navController.popBackStack() },
            )
        }
        composable("projects/{projectId}/trash") { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId").orEmpty()
            TrashScreen(
                projectId = projectId,
                onBack = {
                    // A restore in the trash screen doesn't refresh the card list behind it on
                    // its own -- bump this so the restored card reappears without a manual pull.
                    cardListRefreshKey++
                    navController.popBackStack()
                },
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
                            withContext(Dispatchers.Main.immediate) {
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
                onConnectionsClick = {
                    navController.navigate("projects/$projectId/cards/$cardId/connections")
                },
                onDeleted = {
                    cardListRefreshKey++
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable("projects/{projectId}/cards/{cardId}/connections") { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId").orEmpty()
            val cardId = backStackEntry.arguments?.getString("cardId").orEmpty()
            ConnectionsScreen(
                cardId = cardId,
                refreshKey = connectionsRefreshKey,
                onAddConnection = {
                    navController.navigate("projects/$projectId/cards/$cardId/connections/add")
                },
                onNavigateToCard = { targetCardId, title ->
                    selectedCardTitle = title
                    cardViewRefreshKey++
                    cardListRefreshKey++
                    navController.navigate("projects/$projectId/cards/$targetCardId")
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable("projects/{projectId}/cards/{cardId}/connections/add") { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId").orEmpty()
            val cardId = backStackEntry.arguments?.getString("cardId").orEmpty()
            AddConnectionScreen(
                fromCardId = cardId,
                projectId = projectId,
                onAdded = {
                    connectionsRefreshKey++
                    navController.popBackStack()
                },
                onCancel = { navController.popBackStack() },
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
                            withContext(Dispatchers.Main.immediate) {
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
                    }
                },
                onCancel = { navController.popBackStack() },
            )
        }
    }
}
