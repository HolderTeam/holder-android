package team.holder.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.holder.android.HolderNative
import team.holder.android.RecoveryTokenImportGlobalResult
import team.holder.android.git.github.DeviceAuthorization
import team.holder.android.git.github.GitHubConnection
import team.holder.android.git.github.GitHubError
import team.holder.android.git.github.GitHubResult
import team.holder.android.git.github.GitHubStatus
import team.holder.android.git.github.parseGitHubOwnerRepo
import team.holder.android.ui.GitHubDeviceFlowDialog
import team.holder.android.ui.githubErrorMessage
import team.holder.android.ui.openUrlExternally

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoverProjectScreen(onBack: () -> Unit, initialToken: String? = null) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    // initialToken comes from opening a .hrk file directly (see MainActivity's ACTION_VIEW
    // handling) rather than pasting one by hand -- seeded once, same as every other field here;
    // still just an editable starting value, not a locked-in source of truth.
    var token by remember { mutableStateOf(initialToken.orEmpty()) }
    var isBusy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<RecoveryTokenImportGlobalResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Mutable copies of result's own pullStatus/pullError -- updated in place after a
    // successful device-registration retry below, so the "Pull: ..." line reflects the
    // latest attempt without needing a second HolderNative.importRecoveryTokenGlobal call.
    var pullStatus by remember { mutableStateOf<String?>(null) }
    var pullError by remember { mutableStateOf<String?>(null) }

    // GITHUB_INTEGRATION_ANDROID_PLAN.md's wiring point 3: only meaningful once a pull has
    // actually failed against a github.com remote -- null otherwise, including the entire
    // happy path where the pull just succeeds. Deliberately does not gate on
    // GitHubConnection's connection status the way New Project's checkbox does (see the
    // plan's "Contrast with point 2" note): a device recovering a GitHub-backed project has
    // no alternative, so this section discovers and drives a GitHub connection from a cold
    // start rather than requiring one already exist.
    var githubOwnerRepo by remember { mutableStateOf<Pair<String, String>?>(null) }
    var githubStatus by remember { mutableStateOf<GitHubStatus?>(null) }
    var githubBusy by remember { mutableStateOf(false) }
    var githubError by remember { mutableStateOf<String?>(null) }
    // Set only for GitHubError.RepositoryNotAccessible -- the friendly recovery route the
    // plan's "Selective-repository installations" section designed: a direct link to where
    // the user grants access, rather than just a generic error message.
    var githubActionUrl by remember { mutableStateOf<String?>(null) }
    var pendingGithubAuth by remember { mutableStateOf<DeviceAuthorization?>(null) }
    var githubConnectJob by remember { mutableStateOf<Job?>(null) }

    val scope = rememberCoroutineScope()

    /** Registers this device's key against [owner]/[repo] and retries the pull -- the
     * "register device → resync" half of the plan's authorize → install → register →
     * resync chain. Only reached once [githubStatus] is already [GitHubStatus.Connected]. */
    suspend fun registerAndRetryPull(projectId: String, owner: String, repo: String) {
        when (val keyResult = GitHubConnection.registerDeployKey(context, owner, repo)) {
            is GitHubResult.Success -> {
                val retried = runCatching {
                    withContext(Dispatchers.IO) { HolderNative.pullGit(projectId) }
                }.getOrNull()
                if (retried != null) {
                    pullStatus = retried.status
                    pullError = retried.errorMessage
                } else {
                    githubError = "Registered this device, but the retry pull failed to run"
                }
            }
            is GitHubResult.Failure -> {
                val failure = keyResult.error
                githubError = githubErrorMessage(failure)
                githubActionUrl = (failure as? GitHubError.RepositoryNotAccessible)?.installationSettingsUrl
            }
        }
    }

    /** Re-checks [GitHubConnection.status] and, the moment it reads [GitHubStatus.Connected],
     * immediately continues into [registerAndRetryPull] -- see the plan's wiring point 3:
     * "authorize → install → register device → resync all happen on this one screen... no
     * detour through Settings." Called after Connect/Finish-setup succeeds and by the
     * explicit "check again" retry button alike. */
    suspend fun continueGithubRecovery(projectId: String, owner: String, repo: String) {
        githubError = null
        githubActionUrl = null
        githubBusy = true
        val newStatus = runCatching { GitHubConnection.status(context) }
            .onFailure { githubError = it.message ?: "Could not check GitHub status" }
            .getOrNull()
        githubStatus = newStatus
        if (newStatus is GitHubStatus.Connected) {
            registerAndRetryPull(projectId, owner, repo)
        }
        githubBusy = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recover Project") },
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
            Text(
                "Paste a recovery token exported from another device, along with its PIN, to " +
                    "recover that project here. If this device doesn't already have the " +
                    "project, it will be created.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it },
                label = { Text("PIN") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Recovery token") },
                textStyle = MaterialTheme.typography.bodySmall,
                // A real token is long enough (a few hundred bytes of JSON/base64) that
                // without a height cap this field grows to fit all of it, pushing Recover
                // -- and everything below it -- off screen. Capped and internally
                // scrollable instead; the field's own drag-to-scroll still works normally.
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(max = 160.dp),
            )
            Button(
                enabled = !isBusy && pin.isNotBlank() && token.isNotBlank(),
                modifier = Modifier.padding(top = 16.dp),
                onClick = {
                    if (!isBusy) {
                        isBusy = true
                        error = null
                        result = null
                        githubOwnerRepo = null
                        githubStatus = null
                        githubError = null
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    HolderNative.importRecoveryTokenGlobal(pin, token)
                                }
                            }.fold(
                                onSuccess = { imported ->
                                    result = imported
                                    pullStatus = imported.pullStatus
                                    pullError = imported.pullError
                                    // Eligible for GitHub device-registration only once a pull
                                    // was actually attempted against a real remote and failed
                                    // -- the happy path (pullStatus == "succeeded") and the
                                    // no-remote-hint path both skip this entirely.
                                    if (imported.remoteHintPresent && imported.remoteConfigured &&
                                        imported.pullStatus != "succeeded"
                                    ) {
                                        val remoteUrl = runCatching {
                                            withContext(Dispatchers.IO) { HolderNative.listProjects() }
                                        }.getOrNull()?.firstOrNull { it.projectId == imported.projectId }?.gitRemoteUrl
                                        val ownerRepo = remoteUrl?.let(::parseGitHubOwnerRepo)
                                        if (ownerRepo != null) {
                                            githubOwnerRepo = ownerRepo
                                            continueGithubRecovery(imported.projectId, ownerRepo.first, ownerRepo.second)
                                        }
                                    }
                                },
                                onFailure = { error = it.message ?: it::class.java.simpleName },
                            )
                            isBusy = false
                        }
                    }
                },
            ) { Text("Recover") }

            if (isBusy) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp))
            }
            result?.let { r ->
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    Text(
                        if (r.projectCreated) "Project recovered (newly created)." else "Project key re-imported.",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (r.remoteHintPresent) {
                        val remoteSummary = if (r.remoteConfigured) {
                            "configured"
                        } else {
                            "failed to configure" + (r.remoteError?.let { " -- $it" } ?: "")
                        }
                        Text("Remote: $remoteSummary")
                        Text("Pull: $pullStatus" + (pullError?.let { " -- $it" } ?: ""))
                    }
                }

                val ownerRepo = githubOwnerRepo
                if (ownerRepo != null && pullStatus != "succeeded") {
                    val (owner, repo) = ownerRepo
                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                    GitHubRecoverySection(
                        status = githubStatus,
                        busy = githubBusy,
                        error = githubError,
                        actionUrl = githubActionUrl,
                        onConnect = {
                            githubError = null
                            githubBusy = true
                            githubConnectJob = scope.launch {
                                runCatching {
                                    GitHubConnection.connect(context) { authorization -> pendingGithubAuth = authorization }
                                }.onSuccess {
                                    pendingGithubAuth = null
                                    continueGithubRecovery(r.projectId, owner, repo)
                                }.onFailure { failure ->
                                    pendingGithubAuth = null
                                    githubError = failure.message ?: "Could not connect to GitHub"
                                    githubBusy = false
                                }
                            }
                        },
                        onOpenUrl = { url -> openUrlExternally(context, url) },
                        onRetry = { scope.launch { continueGithubRecovery(r.projectId, owner, repo) } },
                    )

                    pendingGithubAuth?.let { authorization ->
                        GitHubDeviceFlowDialog(
                            authorization = authorization,
                            onCancel = {
                                githubConnectJob?.cancel()
                                pendingGithubAuth = null
                                githubBusy = false
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The device-registration half of GITHUB_INTEGRATION_ANDROID_PLAN.md's wiring point 3:
 * authorize (if needed) -> finish installing (if needed) -> register this device -> retry
 * the pull, all from this one section. [status] null means "not checked yet" (shown as a
 * brief loading state, not a fifth [GitHubStatus]); [RecoverProjectScreen] only shows this
 * section at all once it already knows the failed remote is a GitHub one.
 */
@Composable
private fun GitHubRecoverySection(
    status: GitHubStatus?,
    busy: Boolean,
    error: String?,
    actionUrl: String?,
    onConnect: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onRetry: () -> Unit,
) {
    Text("GitHub sync", style = MaterialTheme.typography.titleMedium)
    Text(
        when (status) {
            null -> "Checking GitHub..."
            GitHubStatus.NotConnected, GitHubStatus.AuthorizationRequired ->
                "This project syncs through GitHub. Connect your GitHub account to finish " +
                    "recovering it on this device."
            is GitHubStatus.InstallationRequired ->
                "Signed in to GitHub -- one more step is needed before this device can sync."
            is GitHubStatus.Connected -> "Registering this device with GitHub..."
        },
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp),
    )
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }

    when {
        busy -> CircularProgressIndicator(modifier = Modifier.padding(top = 12.dp))
        status == null -> {}
        status is GitHubStatus.InstallationRequired -> {
            Button(onClick = { onOpenUrl(status.installUrl) }, modifier = Modifier.padding(top = 8.dp)) {
                Text("Finish installing Holder Sync")
            }
            TextButton(onClick = onRetry) { Text("I've installed it -- continue") }
        }
        status is GitHubStatus.NotConnected || status is GitHubStatus.AuthorizationRequired ->
            Button(onClick = onConnect, modifier = Modifier.padding(top = 8.dp)) { Text("Connect to GitHub") }
        actionUrl != null -> {
            // Connected, but registration hit GitHubError.RepositoryNotAccessible -- the
            // friendly recovery route: a direct link to grant access, not just a retry.
            Button(onClick = { onOpenUrl(actionUrl) }, modifier = Modifier.padding(top = 8.dp)) {
                Text("Grant access on GitHub")
            }
            TextButton(onClick = onRetry) { Text("Retry") }
        }
        error != null ->
            // Connected, but the last registration/retry attempt itself failed for some
            // other reason (rate limited, network error, ...) -- offer a plain retry.
            TextButton(onClick = onRetry) { Text("Retry") }
        else -> {}
    }
}
