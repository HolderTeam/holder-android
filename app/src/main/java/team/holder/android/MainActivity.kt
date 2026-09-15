package team.holder.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.provider.OpenableColumns
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
import team.holder.android.diagnostics.ACTION_OPEN_DIAGNOSTICS
import team.holder.android.git.backup.RestoreOffer
import team.holder.android.git.backup.SnapshotProtection
import team.holder.android.git.backup.SnapshotScheduler
import team.holder.android.git.backup.snapshotFile
import team.holder.android.resource.attachPickedFile
import team.holder.android.sync.GitSyncScheduler
import team.holder.android.ui.CenteredMessage
import team.holder.android.ui.ProjectPickerDialog
import team.holder.android.ui.screens.AboutSettingsScreen
import team.holder.android.ui.screens.AddConnectionScreen
import team.holder.android.ui.screens.AddMilestoneScreen
import team.holder.android.ui.screens.AppearanceSettingsScreen
import team.holder.android.ui.screens.BackupSettingsScreen
import team.holder.android.ui.screens.CalendarScreen
import team.holder.android.ui.screens.CardEditScreen
import team.holder.android.ui.screens.CardHistoryScreen
import team.holder.android.ui.screens.ConnectionsScreen
import team.holder.android.ui.screens.CardListScreen
import team.holder.android.ui.screens.CardViewPagerScreen
import team.holder.android.ui.screens.CardViewScreen
import team.holder.android.ui.screens.DiagnosticsSettingsScreen
import team.holder.android.ui.screens.EditorSettingsScreen
import team.holder.android.ui.screens.GitSyncScreen
import team.holder.android.ui.screens.ProjectListScreen
import team.holder.android.ui.screens.ResourcesScreen
import team.holder.android.resource.drive.GoogleDriveConnection
import team.holder.android.resource.s3.S3Connection
import team.holder.android.ui.screens.RecoverProjectScreen
import team.holder.android.ui.screens.RestoreBackupScreen
import team.holder.android.ui.screens.SettingsScreen
import team.holder.android.ui.screens.StorageSettingsScreen
import team.holder.android.ui.screens.SyncSettingsScreen
import team.holder.android.ui.screens.TagResultsScreen
import team.holder.android.ui.screens.ToolsScreen
import team.holder.android.ui.screens.TrashScreen
import team.holder.android.ui.theme.HolderTheme
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID
import androidx.browser.auth.AuthTabIntent
import team.holder.android.git.github.GitHubActivityBrowserLauncher
import team.holder.android.git.github.GitHubConnection
import team.holder.android.git.github.GitHubConnectionCoordinator

private const val KEY_AUTH_TAB_OUTSTANDING_ATTEMPT_ID = "authTabOutstandingAttemptId"

/** What another app's Share action handed off (see the SEND/SEND_MULTIPLE intent-filters in
 * AndroidManifest.xml), read by [MainActivity.sharedContentFromIntent]. Kept as two cases
 * rather than a single "text or uris" shape so a future ACTION_PROCESS_TEXT branch (selected
 * text -> "Holder" in the text-selection toolbar) can fold into [Text] without a third case. */
sealed class PendingSharedContent {
    data class Text(val text: String) : PendingSharedContent()
    data class Files(val uris: List<Uri>) : PendingSharedContent()
}

class MainActivity : ComponentActivity() {
    // Set from a .hrk file opened directly (Files app, email attachment, etc. -- see the
    // ACTION_VIEW intent-filter in AndroidManifest.xml) rather than a token pasted by hand.
    // A regular property with Compose's `by` delegate, not composable state: it needs to be
    // writable from onNewIntent, which runs outside the setContent{} composition entirely.
    private var pendingRecoveryToken by mutableStateOf<String?>(null)

    // Set from another app's Share action (see the SEND/SEND_MULTIPLE intent-filters in
    // AndroidManifest.xml) -- mirrors pendingRecoveryToken exactly, same onCreate/onNewIntent
    // shape, same reason it's a plain mutableStateOf field rather than composable state.
    private var pendingSharedContent by mutableStateOf<PendingSharedContent?>(null)

    // Set from the "New card" static app shortcut (see res/xml/shortcuts.xml) -- same
    // onCreate/onNewIntent shape as pendingRecoveryToken/pendingSharedContent above, but a
    // plain Boolean rather than a payload type since the shortcut carries no data of its own.
    private var pendingNewCardShortcut by mutableStateOf(false)

    // Set when opened via a background-reliability notification's tap target (see
    // team.holder.android.diagnostics.ReliabilityNotifier) -- same onCreate/onNewIntent shape as
    // pendingNewCardShortcut above.
    private var pendingOpenDiagnostics by mutableStateOf(false)

    // A non-secret SavedState mirror of the coordinator-owned unresolved Auth Tab marker. It
    // survives process death only so a restored stale result is discarded and a fresh Auth Tab
    // is not launched through the same registration first; it is never OAuth authority. A
    // plain field, not Compose state -- restored/saved at the ActivityResult lifecycle edge.
    @Volatile
    private var authTabOutstandingAttemptId: String? = null

    // Registered unconditionally, as a field initializer -- must happen before this Activity
    // reaches STARTED, and must never be created conditionally inside a Composable (see
    // GitHubActivityBrowserLauncher's doc comment for why: "at most one unresolved AuthTabIntent
    // launch per registration, ever" is the policy GitHubConnectionCoordinator's own design
    // relies on to disambiguate a late result).
    private val authTabLauncher = AuthTabIntent.registerActivityResultLauncher(this) { result ->
        GitHubConnection.handleAuthTabResult(result) {
            authTabOutstandingAttemptId = null
        }
    }

    private val githubBrowserLauncher = GitHubActivityBrowserLauncher(
        authTabLauncher = authTabLauncher,
        setOutstandingAttemptId = { attemptId ->
            authTabOutstandingAttemptId = attemptId?.toString()
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authTabOutstandingAttemptId = savedInstanceState?.getString(KEY_AUTH_TAB_OUTSTANDING_ATTEMPT_ID)
        GitHubConnection.restoreAuthTabOutstandingAttemptId(
            authTabOutstandingAttemptId?.let { runCatching { UUID.fromString(it) }.getOrNull() },
        )
        pendingRecoveryToken = recoveryTokenFromIntent(intent)
        pendingSharedContent = sharedContentFromIntent(intent)
        pendingNewCardShortcut = isNewCardShortcutIntent(intent)
        pendingOpenDiagnostics = isOpenDiagnosticsIntent(intent)

        // Captured before initialize() below, which creates this directory if it's missing --
        // its absence right now is the exact, one-shot signal that this is the first launch
        // this install has ever done. See SnapshotProtection's doc comment for why that matters.
        val holderDataDir = File(filesDir, "holder")
        val dataDirExistedBeforeInit = holderDataDir.exists()

        val initError = runCatching {
            HolderNative.initialize(
                context = this,
                dataDir = holderDataDir,
                schemaSql = assets.open("schema.sql").bufferedReader().use { it.readText() },
                welcomeContent = assets.open("WELCOME.md").bufferedReader().use { it.readText() },
            )
        }.exceptionOrNull()

        // Only meaningful right after a successful initialize -- a failed one never gets far
        // enough to seed the fresh "Home" project SnapshotProtection is guarding against, and
        // there's nothing here worth doing on top of an already-broken launch.
        if (initError == null) {
            SnapshotProtection.armIfFreshInstallHasAnUnseenSnapshot(filesDir, snapshotFile(this), dataDirExistedBeforeInit)
        }

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

        // Always on, no settings to read first -- see SnapshotScheduler's doc comment for why
        // this doesn't need the same enabled/interval dance GitSyncScheduler's call above does.
        SnapshotScheduler.reconcile(applicationContext)

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
                    HolderNavHost(
                        pendingRecoveryToken = pendingRecoveryToken,
                        pendingSharedContent = pendingSharedContent,
                        onSharedContentHandled = { pendingSharedContent = null },
                        pendingNewCardShortcut = pendingNewCardShortcut,
                        onNewCardShortcutHandled = { pendingNewCardShortcut = false },
                        pendingOpenDiagnostics = pendingOpenDiagnostics,
                        onOpenDiagnosticsHandled = { pendingOpenDiagnostics = false },
                        githubBrowserLauncher = githubBrowserLauncher,
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_AUTH_TAB_OUTSTANDING_ATTEMPT_ID, authTabOutstandingAttemptId)
    }

    // Fires when a .hrk file is opened while this activity is already running (launchMode
    // "singleTop" in the manifest routes it here instead of spinning up a second instance).
    // GitHub App Link returns have their own narrow exported GitHubCallbackActivity.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recoveryTokenFromIntent(intent)?.let { pendingRecoveryToken = it }
        sharedContentFromIntent(intent)?.let { pendingSharedContent = it }
        if (isNewCardShortcutIntent(intent)) pendingNewCardShortcut = true
        if (isOpenDiagnosticsIntent(intent)) pendingOpenDiagnostics = true
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

    /** True when this activity was opened via the "New card" static app shortcut (see
     * res/xml/shortcuts.xml and the intent's own targetClass/no-targetPackage comment there). */
    private fun isNewCardShortcutIntent(intent: Intent?): Boolean =
        intent?.action == "team.holder.android.action.NEW_CARD"

    /** True when this activity was opened via a background-reliability notification's tap
     * target (see [team.holder.android.diagnostics.ReliabilityNotifier]). */
    private fun isOpenDiagnosticsIntent(intent: Intent?): Boolean =
        intent?.action == ACTION_OPEN_DIAGNOSTICS

    /** Reads what another app's Share action sent (Files app "Share" on a photo/PDF, a
     * browser's "Share" on a page URL, etc. -- see the SEND/SEND_MULTIPLE intent-filters in
     * AndroidManifest.xml). Null for any other launch, including a malformed share intent
     * missing the extra it should have -- never a fatal error, just nothing to share in.
     * A `when (intent?.action)` on purpose: `process_text.md`'s planned ACTION_PROCESS_TEXT
     * branch (reading EXTRA_PROCESS_TEXT into the same PendingSharedContent.Text case) slots
     * in here as a third arm. */
    private fun sharedContentFromIntent(intent: Intent?): PendingSharedContent? = runCatching {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    intent.getStringExtra(Intent.EXTRA_TEXT)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { PendingSharedContent.Text(it) }
                } else {
                    intentParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                        ?.let { PendingSharedContent.Files(listOfNotNull(it)) }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                intentParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { PendingSharedContent.Files(it) }
            }
            Intent.ACTION_PROCESS_TEXT -> {
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
                    ?.toString()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { PendingSharedContent.Text(it) }
            }
            else -> null
        }
    }.getOrNull()
}

/** [Intent.getParcelableExtra] across the API 33 split -- the typed overload only exists from
 * API 33 on, so anything older falls back to the deprecated untyped one. */
@Suppress("DEPRECATION")
private fun <T> intentParcelableExtra(intent: Intent, name: String, clazz: Class<T>): T? =
    if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(name, clazz)
    } else {
        val raw: Parcelable? = intent.getParcelableExtra(name)
        @Suppress("UNCHECKED_CAST")
        raw as? T
    }

/** [Intent.getParcelableArrayListExtra] across the same API 33 split as [intentParcelableExtra]. */
@Suppress("DEPRECATION")
private fun <T> intentParcelableArrayListExtra(intent: Intent, name: String, clazz: Class<T>): List<T>? =
    if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableArrayListExtra(name, clazz)
    } else {
        val raw: ArrayList<Parcelable>? = intent.getParcelableArrayListExtra(name)
        @Suppress("UNCHECKED_CAST")
        raw as? List<T>
    }

/** Same technique as AssetAttachment.kt's own private queryDisplayName (used by
 * [attachPickedFile]) -- duplicated here rather than exposed there for this one extra caller. */
private fun queryDisplayName(context: Context, uri: Uri): String? {
    val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
    return context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
}

@Composable
private fun HolderNavHost(
    pendingRecoveryToken: String? = null,
    pendingSharedContent: PendingSharedContent? = null,
    onSharedContentHandled: () -> Unit = {},
    pendingNewCardShortcut: Boolean = false,
    onNewCardShortcutHandled: () -> Unit = {},
    pendingOpenDiagnostics: Boolean = false,
    onOpenDiagnosticsHandled: () -> Unit = {},
    githubBrowserLauncher: GitHubConnectionCoordinator.GitHubBrowserLauncher,
) {
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
    // Non-null only while more than one project exists and pendingSharedContent needs one
    // picked before it can be turned into a card -- see the ProjectPickerDialog call below.
    var sharedContentProjectChoices by remember { mutableStateOf<List<HolderProject>?>(null) }
    // Same role as sharedContentProjectChoices above, but for the "New card" shortcut's own
    // project-choice step -- kept separate rather than merged since the shortcut carries no
    // PendingSharedContent payload to share the same state with.
    var newCardShortcutProjectChoices by remember { mutableStateOf<List<HolderProject>?>(null) }

    suspend fun handleSharedContent(shared: PendingSharedContent, projectId: String) {
        when (shared) {
            is PendingSharedContent.Text -> {
                val card = runCatching {
                    withContext(Dispatchers.IO) {
                        HolderNative.createCard(projectId, titleFromFirstLine(shared.text), shared.text)
                    }
                }.getOrNull() ?: return
                selectedCardTitle = card.title
                cardListRefreshKey++
                navController.navigate("projects/$projectId/cards/${card.cardId}")
            }
            is PendingSharedContent.Files -> {
                val uri = shared.uris.firstOrNull() ?: return
                val title = queryDisplayName(context, uri) ?: "Shared file"
                val card = runCatching {
                    withContext(Dispatchers.IO) { HolderNative.createCard(projectId, title, "") }
                }.getOrNull() ?: return
                // Sequential, not parallel -- attachPickedFile does its own file copy + native
                // import per URI, and updateCard below needs every reference collected first.
                val references = shared.uris.mapNotNull { fileUri ->
                    runCatching { attachPickedFile(context, projectId, card.cardId, fileUri) }.getOrNull()
                }
                if (references.isNotEmpty()) {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            HolderNative.updateCard(card.cardId, title, references.joinToString("\n"))
                        }
                    }
                }
                selectedCardTitle = title
                cardListRefreshKey++
                navController.navigate("projects/$projectId/cards/${card.cardId}")
            }
        }
    }

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

    // Shares text/URL/files straight into a new card, then jumps to it so the user can tidy
    // things up. One project: proceed immediately. More than one: defer to
    // sharedContentProjectChoices below and let ProjectPickerDialog decide. Keyed on
    // pendingSharedContent itself so a second share while already handling one re-fires just
    // like pendingRecoveryToken above.
    LaunchedEffect(pendingSharedContent) {
        val shared = pendingSharedContent ?: return@LaunchedEffect
        val projects = runCatching { withContext(Dispatchers.IO) { HolderNative.listProjects() } }.getOrNull()
        when {
            projects == null || projects.isEmpty() -> Unit
            projects.size == 1 -> {
                handleSharedContent(shared, projects.single().projectId)
                onSharedContentHandled()
            }
            else -> sharedContentProjectChoices = projects
        }
    }

    sharedContentProjectChoices?.let { projects ->
        val shared = pendingSharedContent
        if (shared == null) {
            sharedContentProjectChoices = null
        } else {
            ProjectPickerDialog(
                projects = projects,
                homeProjectName = HolderNative.DEFAULT_PROJECT_NAME,
                title = "Share to which project?",
                onSelect = { project ->
                    sharedContentProjectChoices = null
                    scope.launch {
                        handleSharedContent(shared, project.projectId)
                        onSharedContentHandled()
                    }
                },
                onDismiss = {
                    sharedContentProjectChoices = null
                    onSharedContentHandled()
                },
            )
        }
    }

    // The exact navigation the plain card-list "+" already uses (see onCreateCard on the
    // "projects/{projectId}/cards" route below) -- reused here rather than duplicated so
    // there's one canonical way this app navigates to "new card at project root."
    fun navigateToNewCard(projectId: String) {
        pendingParentCardId = null
        navController.navigate("projects/$projectId/cards/new")
    }

    // Jumps straight into a new card when launched via the "New card" static app shortcut
    // (see res/xml/shortcuts.xml). One project: proceed immediately. More than one: defer to
    // newCardShortcutProjectChoices below and let ProjectPickerDialog decide -- same shape as
    // pendingSharedContent's handling above, just without a payload to carry through.
    LaunchedEffect(pendingNewCardShortcut) {
        if (!pendingNewCardShortcut) return@LaunchedEffect
        val projects = runCatching { withContext(Dispatchers.IO) { HolderNative.listProjects() } }.getOrNull()
        when {
            projects == null || projects.isEmpty() -> onNewCardShortcutHandled()
            projects.size == 1 -> {
                navigateToNewCard(projects.single().projectId)
                onNewCardShortcutHandled()
            }
            else -> newCardShortcutProjectChoices = projects
        }
    }

    newCardShortcutProjectChoices?.let { projects ->
        ProjectPickerDialog(
            projects = projects,
            homeProjectName = HolderNative.DEFAULT_PROJECT_NAME,
            title = "Pick a project",
            onSelect = { project ->
                newCardShortcutProjectChoices = null
                navigateToNewCard(project.projectId)
                onNewCardShortcutHandled()
            },
            onDismiss = {
                newCardShortcutProjectChoices = null
                onNewCardShortcutHandled()
            },
        )
    }

    // Jumps straight to Settings > Diagnostics when opened via a background-reliability
    // notification's tap target (see team.holder.android.diagnostics.ReliabilityNotifier) --
    // same onCreate/onNewIntent shape as pendingNewCardShortcut above, just without a
    // project-choice step to defer to.
    LaunchedEffect(pendingOpenDiagnostics) {
        if (pendingOpenDiagnostics) {
            navController.navigate("settings/diagnostics")
            onOpenDiagnosticsHandled()
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
                onAppearanceClick = { navController.navigate("settings/appearance") },
                onEditorClick = { navController.navigate("settings/editor") },
                onBackupClick = { navController.navigate("settings/backup") },
                onSyncClick = { navController.navigate("settings/sync") },
                onStorageClick = { navController.navigate("settings/storage") },
                onDiagnosticsClick = { navController.navigate("settings/diagnostics") },
                onAboutClick = { navController.navigate("settings/about") },
                onRecoverProjectClick = { navController.navigate("recover-project") },
            )
        }
        composable("settings/appearance") {
            AppearanceSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable("settings/editor") {
            EditorSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable("settings/backup") {
            BackupSettingsScreen(
                onBack = { navController.popBackStack() },
                onRestoreBackupClick = { navController.navigate("restore-backup") },
            )
        }
        composable("settings/sync") {
            SyncSettingsScreen(onBack = { navController.popBackStack() }, browserLauncher = githubBrowserLauncher)
        }
        composable("settings/storage") {
            StorageSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable("settings/diagnostics") {
            DiagnosticsSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable("settings/about") {
            AboutSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable("restore-backup") {
            RestoreBackupScreen(onBack = { navController.popBackStack() })
        }
        composable("recover-project") {
            RecoverProjectScreen(
                onBack = { navController.popBackStack() },
                browserLauncher = githubBrowserLauncher,
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
            CardViewPagerScreen(
                cardId = cardId,
                projectId = projectId,
                cardTitle = selectedCardTitle,
                refreshKey = cardViewRefreshKey,
                onEdit = { activeCardId, title, content ->
                    selectedCardTitle = title
                    selectedCardContent = content
                    saveError = null
                    navController.navigate("projects/$projectId/cards/$activeCardId/edit")
                },
                onNavigateToCard = { targetCardId, title ->
                    selectedCardTitle = title
                    cardListRefreshKey++
                    navController.navigate("projects/$projectId/cards/$targetCardId")
                },
                onNavigateToTag = { tag ->
                    navController.navigate("projects/$projectId/tags/${URLEncoder.encode(tag, "UTF-8")}")
                },
                onConnectionsClick = { activeCardId ->
                    navController.navigate("projects/$projectId/cards/$activeCardId/connections")
                },
                onCreateChildCard = { activeCardId ->
                    saveError = null
                    pendingParentCardId = activeCardId
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
                // Swiping to a sibling never calls onNavigateToCard (see CardViewPagerScreen's
                // doc comment), so without this, selectedCardTitle would keep reporting whichever
                // card was last explicitly navigated to -- stale the moment Edit/Tools/Connections
                // is tapped on a swiped-to card instead.
                onPageChanged = { _, title -> selectedCardTitle = title },
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
            ToolsScreen(
                cardId = cardId,
                projectId = projectId,
                cardTitle = selectedCardTitle,
                refreshKey = connectionsRefreshKey,
                onNavigateToCard = { targetCardId, title ->
                    selectedCardTitle = title
                    cardViewRefreshKey++
                    cardListRefreshKey++
                    navController.navigate("projects/$projectId/cards/$targetCardId")
                },
                onConnectionsClick = {
                    navController.navigate("projects/$projectId/cards/$cardId/connections/detail")
                },
                onResourcesClick = {
                    navController.navigate("projects/$projectId/cards/$cardId/resources")
                },
                onMilestonesClick = {
                    navController.navigate("projects/$projectId/cards/$cardId/calendar")
                },
                onHistoryClick = {
                    navController.navigate("projects/$projectId/cards/$cardId/history")
                },
                onTagClick = { tag ->
                    navController.navigate("projects/$projectId/tags/${URLEncoder.encode(tag, "UTF-8")}")
                },
                onBack = {
                    // Refreshes CardViewScreen's connections summary in case a connection,
                    // resource, or milestone was added or removed somewhere under Tools -- it
                    // doesn't otherwise notice since it stays on the back stack rather than
                    // recomposing from scratch.
                    cardViewRefreshKey++
                    navController.popBackStack()
                },
            )
        }
        composable("projects/{projectId}/cards/{cardId}/connections/detail") { backStackEntry ->
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
                onNavigateToCard = { targetCardId, title ->
                    selectedCardTitle = title
                    cardViewRefreshKey++
                    cardListRefreshKey++
                    navController.navigate("projects/$projectId/cards/$targetCardId")
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable("projects/{projectId}/cards/{cardId}/resources") { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId").orEmpty()
            val cardId = backStackEntry.arguments?.getString("cardId").orEmpty()
            ResourcesScreen(
                projectId = projectId,
                cardId = cardId,
                cardTitle = selectedCardTitle,
                refreshKey = connectionsRefreshKey,
                onBack = {
                    // Same reasoning as ToolsScreen's onBack: an attach here wouldn't otherwise
                    // be noticed by the Tools dashboard or CardViewScreen sitting below on the
                    // back stack.
                    connectionsRefreshKey++
                    navController.popBackStack()
                },
            )
        }
        composable("projects/{projectId}/cards/{cardId}/calendar") { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId").orEmpty()
            val cardId = backStackEntry.arguments?.getString("cardId").orEmpty()
            CalendarScreen(
                projectId = projectId,
                // Bumped whenever AddMilestoneScreen saves -- the main way this screen's data
                // goes stale from elsewhere; its own remove flow refreshes itself directly.
                refreshKey = connectionsRefreshKey,
                onNavigateToCard = { targetCardId, title ->
                    selectedCardTitle = title
                    cardListRefreshKey++
                    navController.navigate("projects/$projectId/cards/$targetCardId")
                },
                onBack = { navController.popBackStack() },
                onAddMilestone = {
                    navController.navigate("projects/$projectId/cards/$cardId/milestones/add")
                },
            )
        }
        composable("projects/{projectId}/cards/{cardId}/history") { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId").orEmpty()
            val cardId = backStackEntry.arguments?.getString("cardId").orEmpty()
            CardHistoryScreen(
                cardId = cardId,
                projectId = projectId,
                cardTitle = selectedCardTitle,
                onRestored = {
                    // A restore rewrites this card's content (and possibly its title), so
                    // CardViewScreen must reload when it's revealed again -- same rationale as
                    // Connections' onBack bump above. The list's summary/title can change too.
                    cardViewRefreshKey++
                    cardListRefreshKey++
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
