package team.holder.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.holder.android.git.backup.RestoreOffer
import team.holder.android.sync.GitSyncScheduler
import team.holder.android.ui.CenteredMessage
import team.holder.android.ui.screens.AddConnectionScreen
import team.holder.android.ui.screens.AddMilestoneScreen
import team.holder.android.ui.screens.CalendarScreen
import team.holder.android.ui.screens.CardEditScreen
import team.holder.android.ui.screens.ConnectionsScreen
import team.holder.android.ui.screens.CardListScreen
import team.holder.android.ui.screens.CardViewScreen
import team.holder.android.ui.screens.GitSyncScreen
import team.holder.android.ui.screens.ProjectListScreen
import team.holder.android.resource.drive.GoogleDriveConnection
import team.holder.android.resource.s3.S3Connection
import team.holder.android.ui.screens.RecoverProjectScreen
import team.holder.android.ui.screens.RestoreBackupScreen
import team.holder.android.ui.screens.SettingsScreen
import team.holder.android.ui.screens.TagResultsScreen
import team.holder.android.ui.screens.TrashScreen
import team.holder.android.ui.theme.HolderTheme
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

class MainActivity : ComponentActivity() {
    // Set from a .hrk file opened directly (Files app, email attachment, etc. -- see the
    // ACTION_VIEW intent-filter in AndroidManifest.xml) rather than a token pasted by hand.
    // A regular property with Compose's `by` delegate, not composable state: it needs to be
    // writable from onNewIntent, which runs outside the setContent{} composition entirely.
    private var pendingRecoveryToken by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingRecoveryToken = recoveryTokenFromIntent(intent)

        val initError = runCatching {
            HolderNative.initialize(
                context = this,
                dataDir = File(filesDir, "holder"),
                schemaSql = assets.open("schema.sql").bufferedReader().use { it.readText() },
                welcomeContent = assets.open("WELCOME.md").bufferedReader().use { it.readText() },
            )
        }.exceptionOrNull()

        // Safe and cheap regardless of initError or whether Drive/S3 is actually connected --
        // holder-core only ever calls into either for a project that has a matching Location,
        // including one synced in from desktop rather than created on this device (e.g. a
        // GTK-created s3_compatible Location becomes retrievable here the moment S3 is
        // connected in Settings, no attach required). See GoogleDriveConnection/S3Connection's
        // doc comments for why this is process-wide registration, not scoped to a project.
        GoogleDriveConnection.registerProvider(this)
        S3Connection.registerProvider(this)

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
                    HolderNavHost(pendingRecoveryToken = pendingRecoveryToken)
                }
            }
        }
    }

    // Fires when a .hrk file is opened while this activity is already running (launchMode
    // "singleTop" in the manifest routes it here instead of spinning up a second instance).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recoveryTokenFromIntent(intent)?.let { pendingRecoveryToken = it }
    }

    /** Reads a .hrk file's content when this activity was opened via ACTION_VIEW on one (Files
     * app, an email attachment, etc. -- see AndroidManifest.xml's intent-filter). Null for any
     * other launch (the normal case: tapping the launcher icon), including a VIEW intent whose
     * content can't be read for some reason -- never a fatal error, just nothing to pre-fill. */
    private fun recoveryTokenFromIntent(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val uri = intent.data ?: return null
        return runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}

@Composable
private fun HolderNavHost(pendingRecoveryToken: String? = null) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

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
    // Non-null only while navigating from a card's own "+" to create a child of it; the
    // plain card-list "+" leaves this null so the new card lands at the project root.
    var pendingParentCardId by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    // Jumps straight to Recover Project when launched (or resumed) via a .hrk file, rather
    // than making the user find the recovery icon themselves after already handing over the
    // file's content. Keyed on the token itself: re-fires on a genuinely new file (a second
    // ACTION_VIEW while already on this screen re-triggers just like the first), but not on
    // every recomposition once here.
    LaunchedEffect(pendingRecoveryToken) {
        if (pendingRecoveryToken != null) {
            navController.navigate("recover-project")
        }
    }

    // The automatic half of BACKUP_RESTORE_IMPLEMENTATION_PLAN.md step 9: once per device,
    // ever (see RestoreOffer), jump straight to Restore Backup if Auto Backup restored a
    // snapshot before this install's first launch. Skipped entirely if a .hrk file already
    // claimed the same first-launch moment above -- opening one implies she already has a
    // real recovery path in hand, so this doesn't also compete for it. Unkeyed (LaunchedEffect
    // (Unit)): this must run exactly once per process, not re-check on every recomposition --
    // checkAndMarkOfferedOnce's own flag is what makes that safe across process restarts too.
    LaunchedEffect(Unit) {
        if (pendingRecoveryToken == null && RestoreOffer.checkAndMarkOfferedOnce(context)) {
            navController.navigate("restore-backup")
        }
    }

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
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onRestoreBackupClick = { navController.navigate("restore-backup") },
            )
        }
        composable("restore-backup") {
            RestoreBackupScreen(onBack = { navController.popBackStack() })
        }
        composable("recover-project") {
            RecoverProjectScreen(
                onBack = { navController.popBackStack() },
                initialToken = pendingRecoveryToken,
            )
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
                    pendingParentCardId = null
                    navController.navigate("projects/$projectId/cards/new")
                },
                onTrashClick = { navController.navigate("projects/$projectId/trash") },
                onCalendarClick = { navController.navigate("projects/$projectId/calendar") },
                onBack = { navController.popBackStack() },
            )
        }
        composable("projects/{projectId}/calendar") { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId").orEmpty()
            CalendarScreen(
                projectId = projectId,
                // Bumped whenever AddMilestoneScreen saves -- the main way this screen's data
                // goes stale from elsewhere; its own remove flow refreshes itself directly.
                refreshKey = connectionsRefreshKey,
                onNavigateToCard = { cardId, title ->
                    selectedCardTitle = title
                    cardListRefreshKey++
                    navController.navigate("projects/$projectId/cards/$cardId")
                },
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
                projectId = projectId,
                cardId = null,
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
                                withContext(Dispatchers.IO) {
                                    HolderNative.createCard(projectId, title, content, pendingParentCardId)
                                }
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
                    navController.navigate("projects/$projectId/cards/$cardId/edit")
                },
                onNavigateToCard = { targetCardId, title ->
                    selectedCardTitle = title
                    cardListRefreshKey++
                    navController.navigate("projects/$projectId/cards/$targetCardId")
                },
                onNavigateToTag = { tag ->
                    navController.navigate("projects/$projectId/tags/${URLEncoder.encode(tag, "UTF-8")}")
                },
                onConnectionsClick = {
                    navController.navigate("projects/$projectId/cards/$cardId/connections")
                },
                onCreateChildCard = {
                    saveError = null
                    pendingParentCardId = cardId
                    navController.navigate("projects/$projectId/cards/new")
                },
                onDeleted = {
                    cardListRefreshKey++
                    navController.popBackStack()
                },
                // "Up" to the project's card list in one tap, however many connection/tag hops
                // got here -- CardViewScreen is the only screen that chains into itself, so it's
                // the only back arrow that needs to jump rather than pop one level. The system
                // back gesture still retraces those hops one at a time on its own.
                onBack = { navController.popBackStack("projects/$projectId/cards", inclusive = false) },
            )
        }
        composable("projects/{projectId}/tags/{tag}") { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId").orEmpty()
            val tag = URLDecoder.decode(backStackEntry.arguments?.getString("tag").orEmpty(), "UTF-8")
            TagResultsScreen(
                projectId = projectId,
                tag = tag,
                onNavigateToCard = { targetCardId, title ->
                    selectedCardTitle = title
                    cardListRefreshKey++
                    navController.navigate("projects/$projectId/cards/$targetCardId")
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable("projects/{projectId}/cards/{cardId}/connections") { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId").orEmpty()
            val cardId = backStackEntry.arguments?.getString("cardId").orEmpty()
            ConnectionsScreen(
                cardId = cardId,
                projectId = projectId,
                cardTitle = selectedCardTitle,
                refreshKey = connectionsRefreshKey,
                onAddConnection = {
                    navController.navigate("projects/$projectId/cards/$cardId/connections/add")
                },
                onAddMilestone = {
                    navController.navigate("projects/$projectId/cards/$cardId/milestones/add")
                },
                onNavigateToCard = { targetCardId, title ->
                    selectedCardTitle = title
                    cardViewRefreshKey++
                    cardListRefreshKey++
                    navController.navigate("projects/$projectId/cards/$targetCardId")
                },
                onBack = {
                    // Refreshes CardViewScreen's connections summary in case a connection was
                    // added or removed here -- it doesn't otherwise notice since it stays on
                    // the back stack rather than recomposing from scratch.
                    cardViewRefreshKey++
                    navController.popBackStack()
                },
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
        composable("projects/{projectId}/cards/{cardId}/milestones/add") { backStackEntry ->
            val cardId = backStackEntry.arguments?.getString("cardId").orEmpty()
            AddMilestoneScreen(
                cardId = cardId,
                onAdded = {
                    connectionsRefreshKey++
                    navController.popBackStack()
                },
                onCancel = { navController.popBackStack() },
            )
        }
        composable("projects/{projectId}/cards/{cardId}/edit") { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId").orEmpty()
            val cardId = backStackEntry.arguments?.getString("cardId").orEmpty()
            CardEditScreen(
                screenTitle = "Edit card",
                initialTitle = selectedCardTitle,
                initialContent = selectedCardContent,
                projectId = projectId,
                cardId = cardId,
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
