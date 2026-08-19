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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import team.holder.android.ui.CenteredMessage
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
    var selectedProjectName by remember { mutableStateOf("") }
    var selectedCardTitle by remember { mutableStateOf("") }

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
                onCardClick = { card ->
                    selectedCardTitle = card.title
                    navController.navigate("cards/${card.cardId}")
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable("cards/{cardId}") { backStackEntry ->
            val cardId = backStackEntry.arguments?.getString("cardId").orEmpty()
            CardViewScreen(
                cardId = cardId,
                cardTitle = selectedCardTitle,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
