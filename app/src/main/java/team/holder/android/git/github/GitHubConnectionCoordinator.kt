package team.holder.android.git.github

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.browser.auth.AuthTabIntent
import java.util.concurrent.TimeUnit
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

/**
 * The single process-scoped owner of every piece of GitHub connection concurrency state --
 * see GITHUB_SETUP_SERVICE_PLAN.md's `GitHubConnectionCoordinator` section (20+ review rounds
 * closing races an earlier, much simpler draft of this file missed entirely; do not
 * re-simplify this without re-reading that section first). A plain Kotlin `object`, same as
 * [GitHubConnection]/[team.holder.android.resource.drive.GoogleDriveConnection] -- no DI
 * framework exists in this codebase, and an `object`'s lifetime already matches exactly what
 * this needs (process-scoped, survives any single caller's own scope being torn down).
 *
 * Two independent concerns, each with its own mutex, on purpose -- they answer different
 * questions and must not share a lock:
 *   - [connectStateMutex] guards *transaction bookkeeping* (which OAuth/installation ceremony
 *     is currently outstanding, and the one shared in-flight `connect()` operation).
 *   - [credentialMutationMutex] + [credentialEpoch] guard the *credential itself* (the stored
 *     refresh token/cap and the cached access token).
 *   - [controlPlaneMutex] is a third, orthogonal concern: serializing direct GitHub REST calls
 *     so a refresh can never invalidate a token another call is actively using.
 * Lock order, fixed here and never inverted anywhere in this file: `controlPlaneMutex` before
 * `credentialMutationMutex`; `connectStateMutex` before `credentialMutationMutex`.
 */
object GitHubConnectionCoordinator {
    private const val LOG_TAG = "GitHubConnection"
    private val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---- connectStateMutex-protected fields ----
    private val connectStateMutex = Mutex()
    private var pendingOAuth: PendingOAuth? = null
    private var connectInFlight: ConnectOperation? = null
    private var pendingInstallationReturn: PendingInstallationReturn? = null

    // ---- credential concern ----
    private val credentialMutationMutex = Mutex()
    private val credentialEpoch = AtomicLong(0L)
    /** Internal, not private, only so a test can seed a pre-valid cache entry directly --
     * exercising [withAccessToken]'s controlPlaneMutex barrier (e.g. against a concurrent
     * [disconnect]) without needing a real/faked GitHubOAuth.refresh network call to get there.
     * Never swapped/read from outside the coordinator in production. */
    internal var accessTokenCache: AccessTokenCache? = null
    private val refreshInFlight = Mutex()

    // ---- control-plane concern ----
    private val controlPlaneMutex = Mutex()

    /** Swapped only by tests (a plain JVM in-memory fake) -- see [GitHubCredentialStore]'s own
     * doc comment for why this seam exists. Never swapped at runtime in production. */
    internal var credentialStore: GitHubCredentialStore = RealGitHubCredentialStore

    /** Test seam for the stored-credential branch of [connect]. Production always uses
     * [statusAgainstStoredCredential]; plain JVM tests can exercise its terminal handling
     * without a real relay/GitHub call. */
    internal var storedCredentialStatusOverride: (suspend (Context) -> GitHubResult<GitHubStatus>)? = null

    /** Test seam for the refresh result. Production makes exactly one relay call through
     * [GitHubOAuth.refresh]; tests use this to prove unsafe outcomes never retain the spent
     * credential. */
    internal var relayRefreshOverride: (suspend (OkHttpClient, String, String) -> RelayResult)? = null

    /** Test seam for a standalone status query. Production always performs the normal managed
     * access-token/installation lookup; tests use it to hold a pre-disconnect result at the
     * epoch/publication boundary. */
    internal var standaloneStatusOverride: (suspend (Context) -> GitHubResult<GitHubStatus>)? = null

    /** The coordinator is the sole publisher of authoritative observable connection state -- a
     * bare per-call result is a result for that call, never an instruction to repaint global
     * UI state on its own. */
    private val mutableStatusFlow = MutableStateFlow<GitHubStatus>(GitHubStatus.NotConnected)
    val statusFlow: StateFlow<GitHubStatus> = mutableStatusFlow.asStateFlow()

    /** 10-15 minutes is plenty for an OAuth ceremony a user is actively watching. */
    private const val PENDING_OAUTH_TIMEOUT_MILLIS = 12 * 60 * 1000L

    /** Much shorter than the OAuth ceremony -- an installation round-trip through GitHub's own
     * UI is far faster, and this only guards against wasted status() calls, not a forgeable
     * security outcome. */
    private const val PENDING_INSTALLATION_TIMEOUT_MILLIS = 5 * 60 * 1000L

    /** Applied to a cached access token's `expires_in` before trusting it -- avoids a token
     * expiring mid-flight to GitHub. */
    private const val ACCESS_TOKEN_SAFETY_MARGIN_MILLIS = 60_000L

    /** The relay bounds its one-shot upstream GitHub request at fifteen seconds.  Give its
     * caller a real outer margin so Android does not declare a still-live relay operation lost
     * first; that would turn a usable response into an ambiguous OAuth outcome. */
    internal const val RELAY_CALL_TIMEOUT_MILLIS = 20_000L

    private data class PendingOAuth(
        val attemptId: UUID,
        val state: String,
        val codeVerifier: String,
        /** The exact endpoint placed in this attempt's authorization request.  Never rederive
         * it from global configuration while validating a callback. */
        val redirectUri: String,
        val startedAtMonotonic: Long,
        val launchKind: LaunchKind,
        /** Resolved by whichever entry point (App Link delivery, or an AuthTab
         * ActivityResultCallback) observes a matching callback first -- the *one* coroutine
         * running [runConnectOperation] awaits this and then does the actual exchange/commit
         * work itself, so `job` in [ConnectOperation] genuinely represents the one coroutine
         * doing all of it, not two coroutines splitting the work. */
        val callbackOutcome: CompletableDeferred<CallbackOutcome>,
    )

    private sealed interface CallbackOutcome {
        data class Code(val code: String) : CallbackOutcome
        data class RecognizedError(val errorCode: String) : CallbackOutcome
        data object AuthTabCancelled : CallbackOutcome
        data object AuthTabVerificationFailed : CallbackOutcome
    }

    internal data class ParsedOAuthCallback(
        val state: String,
        val code: String?,
        val error: String?,
    )

    private data class ConnectOperation(
        val attemptId: UUID,
        val result: CompletableDeferred<GitHubResult<GitHubStatus>>,
        val job: Job,
        /** Guarded by [connectStateMutex]. This is captured when the operation becomes
         * current and advances only for this operation's own credential replacement. */
        var expectedCredentialEpoch: Long,
    )

    private data class PendingInstallationReturn(val state: String, val startedAtMonotonic: Long)

    internal data class AccessTokenCache(val accessToken: String, val expiresAtMonotonic: Long)

    /** The credential state must be observed as one unit: reading an epoch separately from
     * its credential or cache can label one logical state as another during replacement. */
    internal data class CredentialStateSnapshot(
        val epoch: Long,
        val credential: StoredGitHubCredential?,
        val accessTokenCache: AccessTokenCache?,
    )

    private val exchangeHttpClient: OkHttpClient by lazy {
        newCredentialRelayHttpClient()
    }

    /**
     * Builds the client used for credential-spending relay requests (and shared control-plane
     * calls). The explicit read and full-call bounds are deliberately longer than the Worker's
     * fifteen-second upstream deadline; do not replace them with OkHttp's ten-second defaults.
     */
    internal fun newCredentialRelayHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .readTimeout(RELAY_CALL_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .callTimeout(RELAY_CALL_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
    }

    // ================= connect() =================

    /**
     * See GITHUB_SETUP_SERVICE_PLAN.md's `connect()` state machine. [browserLauncher] is the
     * UI layer's injected hook for anything Activity/Compose-specific (resolving a Custom
     * Tabs/Auth Tab provider, actually launching an Activity, and persisting
     * `authTabOutstandingAttemptId` via the launching Activity's own SavedState) -- kept out
     * of this coordinator entirely, same separation [GoogleDriveAuth] already draws around
     * its own `resolveConsent` callback.
     */
    suspend fun connect(context: Context, browserLauncher: GitHubBrowserLauncher): GitHubResult<GitHubStatus> {
        val appContext = context.applicationContext

        // Join-or-create must be the FIRST atomic step, before any credential/status work --
        // otherwise two simultaneous callers could both observe "no connectInFlight yet" and
        // both start independent work before either actually registers one. CoroutineStart
        // .LAZY plus the explicit job.start() below (after connectInFlight is published, still
        // inside the same critical section) closes a real, if narrow, race Dispatchers.IO's
        // multiple real threads can otherwise hit: without it, the launched coroutine can begin
        // running -- reaching a caller-supplied hook such as GitHubBrowserLauncher
        // .resolveBrowser -- concurrently with this thread still finishing the
        // connectInFlight assignment on the very next line, since nothing before that
        // assignment actually synchronizes the two. A caller-visible side effect must never be
        // able to run before connectInFlight is visible to any concurrent joiner.
        val deferred = CompletableDeferred<GitHubResult<GitHubStatus>>()
        val joined: Deferred<GitHubResult<GitHubStatus>>? = connectStateMutex.withLock {
            val existing = connectInFlight
            if (existing != null) {
                existing.result
            } else {
                val attemptId = GitHubPkce.generateAttemptId()
                // The operation owns a precise credential generation from the instant it is
                // registered. Any disconnect/replacement after this point makes its eventual
                // status/commit stale, even if a blocking network call ignores cancellation.
                val expectedCredentialEpoch = credentialMutationMutex.withLock { credentialEpoch.get() }
                val job = coordinatorScope.launch(start = CoroutineStart.LAZY) {
                    runConnectOperation(appContext, attemptId, browserLauncher, deferred)
                }
                connectInFlight = ConnectOperation(attemptId, deferred, job, expectedCredentialEpoch)
                job.start()
                null
            }
        }
        return (joined ?: deferred).await()
    }

    /** Runs exactly once per fresh `connect()` operation -- never re-entered by a joining
     * caller, which just awaits [result] instead. Wraps the real body in a catch-all: an
     * uncaught exception here must never do either of two things it can otherwise do --
     * crash the whole process (confirmed live: a JSONException from a malformed relay
     * response did exactly this before GitHubOAuth's own parsing was hardened) or leave
     * `connectInFlight` stuck forever with nothing left to complete its `result` (every
     * joining/original caller hangs). Either way, finishConnectOperation is still the sole
     * path that clears state and completes the result -- this is not a second such path,
     * just a guaranteed fallback into the same one. */
    private suspend fun runConnectOperation(
        appContext: Context,
        attemptId: UUID,
        browserLauncher: GitHubBrowserLauncher,
        result: CompletableDeferred<GitHubResult<GitHubStatus>>,
    ) {
        try {
            runConnectOperationBody(appContext, attemptId, browserLauncher)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // structured-concurrency cancellation, not a real failure -- never swallow this
        } catch (e: Exception) {
            Log.e(LOG_TAG, "runConnectOperation: uncaught exception, failing this attempt rather than hanging/crashing", e)
            finishConnectOperation(attemptId, GitHubResult.Failure(GitHubError.Unexpected(null, e.message ?: e::class.java.simpleName)))
        }
    }

    private suspend fun runConnectOperationBody(
        appContext: Context,
        attemptId: UUID,
        browserLauncher: GitHubBrowserLauncher,
    ) {
        val hasStoredCredential = snapshotCredentialState(appContext).credential != null
        if (hasStoredCredential) {
            val resumeResult = storedCredentialStatusOverride?.invoke(appContext)
                ?: statusAgainstStoredCredential(appContext)
            if (!requiresFreshAuthorization(resumeResult)) {
                // Connected/InstallationRequired, or an operational Failure downstream of a
                // credential that's still perfectly good (the committed/uncommitted boundary:
                // an operational failure here must never discard a good credential).
                finishConnectOperation(attemptId, resumeResult)
                return
            }
            // A stored credential that no longer works -- clear it and fall through into a
            // fresh ceremony IN THE SAME operation (connect() is already exclusively
            // user-initiated, so no second "tap Connect again" is warranted).
            if (!clearCredentialForConnectOperation(appContext, attemptId)) return
        }

        val browserLaunch = browserLauncher.resolveBrowser(appContext)
        if (browserLaunch == null) {
            finishConnectOperation(attemptId, GitHubResult.Failure(GitHubError.BrowserUnavailable))
            return
        }

        val state = GitHubPkce.generateState()
        val codeVerifier = GitHubPkce.generateCodeVerifier()
        val codeChallenge = GitHubPkce.codeChallengeFor(codeVerifier)
        val callbackOutcome = CompletableDeferred<CallbackOutcome>()
        connectStateMutex.withLock {
            pendingOAuth = PendingOAuth(
                attemptId = attemptId,
                state = state,
                codeVerifier = codeVerifier,
                redirectUri = GitHubEnvironment.OAUTH_CALLBACK_URL,
                startedAtMonotonic = SystemClock.elapsedRealtime(),
                launchKind = browserLaunch.kind,
                callbackOutcome = callbackOutcome,
            )
        }

        val authorizeUri = Uri.parse(GitHubOAuth.buildAuthorizationUrl(state, codeChallenge))
        // The coordinator's own scope is Dispatchers.IO-based (background orchestration), but
        // actually launching an Activity/ActivityResultLauncher is real UI work.
        withContext(Dispatchers.Main.immediate) {
            browserLauncher.launch(appContext, browserLaunch, authorizeUri, attemptId)
        }

        val outcome = withTimeoutOrNull(PENDING_OAUTH_TIMEOUT_MILLIS) { callbackOutcome.await() }
        if (outcome == null) {
            // The callback handler deliberately clears pendingOAuth before it completes the
            // deferred, so pendingOAuth is not an ownership test here. The operation's own
            // finalizer keys on connectInFlight.attemptId and conditionally cleans pending if
            // it still exists; therefore the timeout always reaches that one terminal path.
            finishConnectOperation(attemptId, timeoutConnectOutcome())
            return
        }

        when (outcome) {
            is CallbackOutcome.Code -> {
                val finalResult = performExchange(appContext, attemptId, outcome.code, codeVerifier)
                finishConnectOperation(attemptId, finalResult)
            }
            is CallbackOutcome.RecognizedError -> {
                val mapped = if (outcome.errorCode == "access_denied") {
                    GitHubResult.Success(GitHubStatus.NotConnected)
                } else {
                    GitHubResult.Failure(GitHubError.Unexpected(null, outcome.errorCode))
                }
                finishConnectOperation(attemptId, mapped)
            }
            CallbackOutcome.AuthTabCancelled -> finishConnectOperation(attemptId, GitHubResult.Success(GitHubStatus.NotConnected))
            CallbackOutcome.AuthTabVerificationFailed ->
                finishConnectOperation(attemptId, GitHubResult.Failure(GitHubError.AuthorizationVerificationFailed))
        }
    }

    /** `POST /v1/github/exchange`, attempted at most once ever for a given authorization code
     * -- see "Transport-failure ambiguity" for why a transport failure here is not
     * automatically `NetworkError`. */
    private suspend fun performExchange(
        appContext: Context,
        attemptId: UUID,
        code: String,
        codeVerifier: String,
    ): GitHubResult<GitHubStatus> {
        val exchangeResult = GitHubOAuth.exchangeCode(exchangeHttpClient, code, codeVerifier)
        // Never log a Success case's own body -- it carries live access/refresh tokens.
        Log.d(LOG_TAG, "performExchange: relay result = " + if (exchangeResult is RelayResult.Success) "Success" else exchangeResult.toString())
        return when (exchangeResult) {
            is RelayResult.Success -> {
                if (!commitCredentialForConnectOperation(appContext, attemptId, exchangeResult.tokens)) {
                    // A disconnect or newer operation detached this one while the one-shot
                    // exchange was in flight. Never install its freshly-issued credential.
                    return GitHubResult.Success(GitHubStatus.NotConnected)
                }
                // Post-commit: a failure here is operational, not authorization -- the
                // credential stays intact regardless of what status() reports next.
                statusAgainstStoredCredential(appContext).also { Log.d(LOG_TAG, "performExchange: post-commit status = $it") }
            }
            is RelayResult.Failure -> when (val error = exchangeResult.error) {
                is RelayError.AuthorizationRequired ->
                    GitHubResult.Success(GitHubStatus.AuthorizationRequired(error.reason))
                RelayError.InvalidRequest, RelayError.OutcomeUnknown, RelayError.AmbiguousTransportFailure ->
                    // A definite rejection, an ambiguous exchange outcome, or an ambiguous
                    // transport failure -- all "tried, needs to try again," not a network-blip
                    // retry prompt and not NotConnected's "chose not to."
                    GitHubResult.Success(GitHubStatus.AuthorizationRequired(null))
                is RelayError.RateLimited -> GitHubResult.Failure(GitHubError.RateLimited(error.retryAfterSeconds))
                is RelayError.Unexpected -> GitHubResult.Failure(GitHubError.Unexpected(error.httpStatus, error.body))
                is RelayError.NetworkError -> GitHubResult.Failure(GitHubError.NetworkError(error.cause))
            }
        }
    }

    /** Commits only while this exact connect operation still owns the generation it started
     * with. Locking connect state before credentials is the coordinator's fixed lock order. */
    private suspend fun commitCredentialForConnectOperation(
        appContext: Context,
        attemptId: UUID,
        tokens: GitHubTokens,
    ): Boolean = connectStateMutex.withLock {
        val operation = connectInFlight
        if (operation?.attemptId != attemptId) return@withLock false
        credentialMutationMutex.withLock {
            if (credentialEpoch.get() != operation.expectedCredentialEpoch) return@withLock false
            credentialStore.store(appContext, durableCredential(tokens))
            accessTokenCache = AccessTokenCache(
                tokens.accessToken,
                SystemClock.elapsedRealtime() + tokens.expiresInSeconds * 1000L - ACCESS_TOKEN_SAFETY_MARGIN_MILLIS,
            )
            operation.expectedCredentialEpoch = credentialEpoch.incrementAndGet()
            true
        }
    }

    /** Ends a Custom Tab/external-browser authorization attempt. This is intentionally not a
     * generic cancellation hook: Auth Tab has its own result lifecycle, while these browser
     * launches otherwise give the user no return signal until the long OAuth timeout. The
     * finalizer's guarded lock clears the exact pending record before any later App Link can
     * consume it. */
    suspend fun cancelPendingBrowserAuthorization(): Boolean {
        val attemptId = connectStateMutex.withLock {
            val pending = pendingOAuth
            val operation = connectInFlight
            if (
                pending == null ||
                operation?.attemptId != pending.attemptId ||
                !isCancellableBrowserLaunch(pending.launchKind)
            ) {
                null
            } else {
                pending.attemptId
            }
        } ?: return false
        return finishConnectOperation(
            attemptId = attemptId,
            outcome = GitHubResult.Success(GitHubStatus.NotConnected),
            requirePendingBrowserAuthorization = true,
        )
    }

    /** The sole finalizer of [connectInFlight], from any call site (the timeout branch above,
     * [runConnectOperation]'s own terminal branches, or an external Cancel/Start-over/
     * disconnect() action). This is NOT the only thing that ever clears [pendingOAuth] -- a
     * valid, matching callback already consumed-and-cleared it the moment it was verified,
     * *before* exchange ever ran (see [handleOAuthCallbackUri]); that one-shot consume is what
     * stops the same callback being replayed while exchange is still in flight. This
     * function's own clear of [pendingOAuth] is a conditional cleanup for every OTHER
     * termination path -- a no-op when a valid callback already cleared it. */
    private suspend fun finishConnectOperation(
        attemptId: UUID,
        outcome: GitHubResult<GitHubStatus>,
        requirePendingBrowserAuthorization: Boolean = false,
    ): Boolean {
        val detached = connectStateMutex.withLock {
            val current = connectInFlight
            if (current == null || current.attemptId != attemptId) return@withLock null  // not mine to finish
            if (
                requirePendingBrowserAuthorization &&
                (pendingOAuth?.let { it.attemptId == attemptId && isCancellableBrowserLaunch(it.launchKind) } != true)
            ) {
                return@withLock null
            }
            credentialMutationMutex.withLock {
                // A success may authoritatively describe the coordinator only while its
                // operation still owns the generation it queried. Returning the current
                // status on mismatch keeps joiners from receiving a historical answer too.
                val completedOutcome = if (outcome is GitHubResult.Success) {
                    if (credentialEpoch.get() == current.expectedCredentialEpoch) {
                        mutableStatusFlow.value = outcome.value
                        outcome
                    } else {
                        GitHubResult.Success(mutableStatusFlow.value)
                    }
                } else {
                    outcome
                }
                if (pendingOAuth?.attemptId == attemptId) pendingOAuth = null
                connectInFlight = null
                DetachedConnectOperation(current, completedOutcome)
            }
        }
        if (detached == null) return false
        // Completed AFTER the mutex is released -- a resumed caller's very next step
        // re-entering coordinator code (a fresh connect()) must never risk self-deadlock on a
        // mutex this function still held.
        detached.operation.result.complete(detached.outcome)
        detached.operation.job.cancel()
        return true
    }

    private data class DetachedConnectOperation(
        val operation: ConnectOperation,
        val outcome: GitHubResult<GitHubStatus>,
    )

    // ================= disconnect() / status() =================

    suspend fun disconnect(context: Context) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        controlPlaneMutex.withLock {
            val inFlightAttemptId = connectStateMutex.withLock { connectInFlight?.attemptId }
            if (inFlightAttemptId != null) {
                finishConnectOperation(inFlightAttemptId, GitHubResult.Success(GitHubStatus.NotConnected))
            }
            credentialMutationMutex.withLock {
                credentialStore.clear(appContext)
                accessTokenCache = null
                credentialEpoch.incrementAndGet()
                mutableStatusFlow.value = GitHubStatus.NotConnected
            }
        }
    }

    /** A cheap-ish probe -- callers with no reason to run a full `connect()` ceremony. Returns
     * the coordinator's own published state if this call's result would otherwise be stale by
     * completion time (a `disconnect()`/credential replacement raced ahead of it), rather than
     * this call's own now-outdated answer. */
    suspend fun status(context: Context): GitHubStatus = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val queriedEpoch = snapshotCredentialState(appContext).epoch
        val result = standaloneStatusOverride?.invoke(appContext) ?: statusAgainstStoredCredential(appContext)
        val status = (result as? GitHubResult.Success)?.value ?: return@withContext mutableStatusFlow.value
        publishStatusIfCurrentEpoch(queriedEpoch, status)
    }

    /** The check and publication are one credential-mutation transaction. A query from an old
     * epoch returns the already-published current state instead of overwriting it. */
    private suspend fun publishStatusIfCurrentEpoch(expectedEpoch: Long, status: GitHubStatus): GitHubStatus =
        credentialMutationMutex.withLock {
            if (credentialEpoch.get() == expectedEpoch) {
                mutableStatusFlow.value = status
                status
            } else {
                mutableStatusFlow.value
            }
        }

    private suspend fun statusAgainstStoredCredential(appContext: Context): GitHubResult<GitHubStatus> {
        // Keep each HTTP call independently gated: obtaining the authenticated identity and
        // looking up installations are distinct direct GitHub requests, not one compound
        // control-plane critical section.
        val user = when (val result = withAccessToken(appContext) { accessToken ->
            GitHubApi.authenticatedUser(exchangeHttpClient, accessToken)
        }) {
            is GitHubResult.Success -> result.value
            is GitHubResult.Failure -> return GitHubResult.Failure(result.error)
        }
        val installations = when (val result = withAccessToken(appContext) { accessToken ->
            GitHubApi.listInstallations(exchangeHttpClient, accessToken)
        }) {
            is GitHubResult.Success -> result.value
            is GitHubResult.Failure -> return GitHubResult.Failure(result.error)
        }
        val personal = GitHubApi.personalInstallationFor(user, installations)
        return if (personal != null) {
            // Never take account identity/owner text from the installation object: its login
            // was accepted only after immutable-ID matching above.
            GitHubResult.Success(GitHubStatus.Connected(user.login, personal.settingsUrl))
        } else {
            GitHubResult.Success(GitHubStatus.InstallationRequired(GitHubEnvironment.APP_URL))
        }
    }

    private suspend fun clearStoredCredential(appContext: Context) {
        credentialMutationMutex.withLock {
            credentialStore.clear(appContext)
            accessTokenCache = null
            credentialEpoch.incrementAndGet()
        }
    }

    /** A rejected stored credential is this operation's own replacement, so it advances the
     * operation's expected generation together with the clear. If superseded already, stop
     * rather than opening a browser or mutating state for a dead operation. */
    private suspend fun clearCredentialForConnectOperation(appContext: Context, attemptId: UUID): Boolean =
        connectStateMutex.withLock {
            val operation = connectInFlight
            if (operation?.attemptId != attemptId) return@withLock false
            credentialMutationMutex.withLock {
                credentialStore.clear(appContext)
                accessTokenCache = null
                operation.expectedCredentialEpoch = credentialEpoch.incrementAndGet()
                mutableStatusFlow.value = GitHubStatus.NotConnected
                true
            }
        }

    internal suspend fun snapshotCredentialState(context: Context): CredentialStateSnapshot =
        credentialMutationMutex.withLock {
            CredentialStateSnapshot(
                epoch = credentialEpoch.get(),
                credential = credentialStore.get(context),
                accessTokenCache = accessTokenCache,
            )
        }

    // ================= refresh / single-flight / access-token cache =================

    /** The gate every direct GitHub REST call (createRepository/registerDeployKey/
     * listInstallations/etc.) goes through -- never called with a bare, unmanaged access
     * token. If there is no stored credential at all, returns [GitHubError.AuthorizationRequired]
     * without ever touching the network. */
    suspend fun <T> withAccessToken(context: Context, block: suspend (accessToken: String) -> GitHubResult<T>): GitHubResult<T> =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            controlPlaneMutex.withLock {
                val cached = snapshotCredentialState(appContext).accessTokenCache
                val accessToken = if (cached != null && cached.expiresAtMonotonic > SystemClock.elapsedRealtime()) {
                    cached.accessToken
                } else {
                    when (val refreshed = refreshAccessToken(appContext)) {
                        is GitHubResult.Success -> refreshed.value
                        is GitHubResult.Failure -> return@withContext GitHubResult.Failure(refreshed.error)
                    }
                }
                block(accessToken)
            }
        }

    private suspend fun refreshAccessToken(appContext: Context): GitHubResult<String> {
        refreshInFlight.withLock {
            // Re-check: a waiter might now see a fresh token another caller just refreshed.
            val snapshot = snapshotCredentialState(appContext)
            val cached = snapshot.accessTokenCache
            if (cached != null && cached.expiresAtMonotonic > SystemClock.elapsedRealtime()) {
                return GitHubResult.Success(cached.accessToken)
            }
            val credential = snapshot.credential
                ?: return GitHubResult.Failure(GitHubError.AuthorizationRequired)
            if (isRefreshCredentialAdvisoryExpired(credential)) {
                // This is only an optimization for the clearly-expired case. A clock that says
                // the credential is still current never grants anything locally: GitHub's
                // refresh response remains the authority for every attempted refresh.
                Log.d(LOG_TAG, "refreshAccessToken: locally expired refresh credential; skipping relay call")
                return GitHubResult.Failure(GitHubError.AuthorizationRequired)
            }
            val refreshToken = credential.refreshToken
            val refreshCap = credential.refreshCap

            val refreshResult = relayRefreshOverride?.invoke(exchangeHttpClient, refreshToken, refreshCap)
                ?: GitHubOAuth.refresh(exchangeHttpClient, refreshToken, refreshCap)
            return when (refreshResult) {
                is RelayResult.Success -> {
                    val committed = credentialMutationMutex.withLock {
                        // The refresh snapshot's generation AND credential must still be
                        // current. Token equality alone can accept a pre-supersession refresh
                        // when the same token happens to be present in a later generation.
                        if (!refreshSnapshotStillCurrent(
                                snapshot = snapshot,
                                currentEpoch = credentialEpoch.get(),
                                currentCredential = credentialStore.get(appContext),
                            )
                        ) {
                            false
                        } else {
                            val tokens = refreshResult.tokens
                            credentialStore.store(appContext, durableCredential(tokens))
                            accessTokenCache = AccessTokenCache(
                                tokens.accessToken,
                                SystemClock.elapsedRealtime() + tokens.expiresInSeconds * 1000L - ACCESS_TOKEN_SAFETY_MARGIN_MILLIS,
                            )
                            true
                        }
                    }
                    if (committed) {
                        GitHubResult.Success(refreshResult.tokens.accessToken)
                    } else {
                        GitHubResult.Failure(GitHubError.AuthorizationRequired)
                    }
                }
                is RelayResult.Failure -> when (val error = refreshResult.error) {
                    is RelayError.AuthorizationRequired -> GitHubResult.Failure(GitHubError.AuthorizationRequired)
                    RelayError.OutcomeUnknown, RelayError.AmbiguousTransportFailure,
                    is RelayError.Unexpected -> abandonUnsafeRefreshCredential(appContext)
                    // The relay rejected this request before it could reach GitHub, so its
                    // stored credential remains safe to retain for diagnostics/recovery.
                    RelayError.InvalidRequest -> GitHubResult.Failure(GitHubError.Unexpected(null, "invalid refresh request"))
                    is RelayError.RateLimited -> GitHubResult.Failure(GitHubError.RateLimited(error.retryAfterSeconds))
                    is RelayError.NetworkError -> GitHubResult.Failure(GitHubError.NetworkError(error.cause))
                }
            }
        }
    }

    /** GitHub may already have rotated the refresh token, so retaining it would invite a
     * second spend on the next acquisition. Clearing under the normal mutation boundary also
     * drops any old access-token cache before exposing reconnect as the only recovery path. */
    private suspend fun abandonUnsafeRefreshCredential(appContext: Context): GitHubResult.Failure {
        clearStoredCredential(appContext)
        return GitHubResult.Failure(GitHubError.AuthorizationRequired)
    }

    /** The refresh commit boundary is deliberately stricter than refresh-token equality. An
     * epoch transition is a supersession boundary even if a later record happens to carry the
     * same token text. Kept internal so the exact stale-snapshot rule has a JVM regression. */
    internal fun refreshSnapshotStillCurrent(
        snapshot: CredentialStateSnapshot,
        currentEpoch: Long,
        currentCredential: StoredGitHubCredential?,
    ): Boolean =
        snapshot.epoch == currentEpoch &&
            snapshot.credential == currentCredential

    /** A definite authorization rejection is represented either as the status result reached
     * after a cached token, or directly as refresh's typed error before status can run. Both
     * mean the same durable credential is known bad and a user-initiated connect may replace it;
     * all operational failures must retain it. */
    internal fun requiresFreshAuthorization(result: GitHubResult<GitHubStatus>): Boolean =
        (result as? GitHubResult.Success)?.value is GitHubStatus.AuthorizationRequired ||
            (result as? GitHubResult.Failure)?.error is GitHubError.AuthorizationRequired

    /** The persisted refresh expiry has to survive process restart, unlike the access-token
     * cache's monotonic deadline.  It is advisory only; GitHub remains authoritative when a
     * refresh is actually attempted. */
    internal fun durableCredential(
        tokens: GitHubTokens,
        wallClockMillis: Long = System.currentTimeMillis(),
    ): StoredGitHubCredential {
        check(tokens.refreshTokenExpiresInSeconds > 0) { "Relay returned an invalid refresh-token lifetime" }
        val expiresAt = Math.addExact(
            wallClockMillis,
            TimeUnit.SECONDS.toMillis(tokens.refreshTokenExpiresInSeconds.toLong()),
        )
        return StoredGitHubCredential(tokens.refreshToken, tokens.refreshCap, expiresAt)
    }

    /** A restart-safe, wall-clock optimization only. Returning false does not establish that a
     * credential is usable; callers must still rely on GitHub's refresh response. */
    internal fun isRefreshCredentialAdvisoryExpired(
        credential: StoredGitHubCredential,
        wallClockMillis: Long = System.currentTimeMillis(),
    ): Boolean = credential.refreshTokenExpiresAtMillis <= wallClockMillis

    // ================= OAuth callback delivery (App Link + AuthTab) =================

    /** Called from either delivery path -- `MainActivity.onNewIntent`'s App Link handling, or
     * forwarded from [handleAuthTabResult]'s own `RESULT_OK` case. Both funnel into this same
     * validation/consumption logic; there is no separate "AuthTab never reaches here" path. */
    suspend fun handleOAuthCallbackUri(uri: Uri) {
        // Snapshot only: it lets endpoint comparison happen before structural parsing without
        // consuming or otherwise disturbing the active attempt. The final state/consume step
        // below rechecks against the current record under the same mutex.
        val pendingAtStart = connectStateMutex.withLock { pendingOAuth }
        if (pendingAtStart == null) {
            Log.d(LOG_TAG, "handleOAuthCallbackUri: no pending OAuth attempt, ignoring")
            return
        }
        if (!matchesOAuthCallbackEndpoint(uri, pendingAtStart.redirectUri)) {
            // Do not include the callback URI in logs: it can contain a one-time code.
            Log.d(LOG_TAG, "handleOAuthCallbackUri: endpoint did not match pending attempt, ignoring")
            return
        }
        val callback = parseOAuthCallback(uri)
        if (callback == null) {
            Log.w(LOG_TAG, "handleOAuthCallbackUri: malformed callback structure, ignoring")
            return
        }

        val pending = connectStateMutex.withLock {
            val current = pendingOAuth
            if (
                current != null &&
                matchesOAuthCallbackEndpoint(uri, current.redirectUri) &&
                current.state == callback.state
            ) {
                pendingOAuth = null // one-shot consume, before exchange ever runs
                current
            } else {
                null
            }
        }
        if (pending == null) {
            Log.w(LOG_TAG, "handleOAuthCallbackUri: no matching pendingOAuth for this state, discarding")
            return
        }

        Log.d(LOG_TAG, "handleOAuthCallbackUri: matched pendingOAuth, code=${callback.code != null} error=${callback.error}")
        val outcome = if (callback.code != null) {
            CallbackOutcome.Code(callback.code)
        } else {
            CallbackOutcome.RecognizedError(callback.error!!)
        }
        pending.callbackOutcome.complete(outcome)
    }

    /** Exact endpoint comparison intentionally ignores only the query string. */
    private fun matchesOAuthCallbackEndpoint(uri: Uri, expectedRedirectUri: String): Boolean =
        matchesOAuthCallbackEndpointParts(uri.scheme, uri.host, uri.port, uri.path, expectedRedirectUri)

    internal fun matchesOAuthCallbackEndpointParts(
        scheme: String?,
        host: String?,
        port: Int,
        path: String?,
        expectedRedirectUri: String,
    ): Boolean {
        val expected = java.net.URI(expectedRedirectUri)
        return scheme == expected.scheme && host == expected.host && port == expected.port && path == expected.path
    }

    /** Parses only security-relevant parameters. Optional GitHub diagnostics are intentionally
     * ignored, but duplicates of `state`, `code`, or `error` make the callback malformed. */
    internal fun parseOAuthCallback(uri: Uri): ParsedOAuthCallback? {
        return parseOAuthCallbackParameters(
            states = uri.getQueryParameters("state"),
            codes = uri.getQueryParameters("code"),
            errors = uri.getQueryParameters("error"),
        )
    }

    internal fun parseOAuthCallbackParameters(
        states: List<String>,
        codes: List<String>,
        errors: List<String>,
    ): ParsedOAuthCallback? {
        if (states.size != 1 || codes.size > 1 || errors.size > 1 || codes.size + errors.size != 1) return null
        if (states.single().isEmpty() || codes.singleOrNull()?.isEmpty() == true || errors.singleOrNull()?.isEmpty() == true) return null
        return ParsedOAuthCallback(states.single(), codes.singleOrNull(), errors.singleOrNull())
    }

    /** [consumeOutstandingAttemptId] reads-and-clears the SavedState-persisted
     * `authTabOutstandingAttemptId` -- called for *every* AuthTab result, matching or not (see
     * the plan's "atomically reads and clears... for *any* result"); only the non-OK branch
     * actually uses the returned id as a matching key, since a non-OK result carries no URI/
     * state to check against `pendingOAuth` directly. */
    fun handleAuthTabResult(result: AuthTabIntent.AuthResult, consumeOutstandingAttemptId: () -> UUID?) {
        val resolvedId = consumeOutstandingAttemptId()
        if (result.resultCode == AuthTabIntent.RESULT_OK) {
            val resultUri = result.resultUri ?: return
            coordinatorScope.launch { handleOAuthCallbackUri(resultUri) }
            return
        }
        if (resolvedId == null) return
        val outcome = if (result.resultCode == AuthTabIntent.RESULT_CANCELED) {
            CallbackOutcome.AuthTabCancelled
        } else {
            CallbackOutcome.AuthTabVerificationFailed
        }
        coordinatorScope.launch {
            connectStateMutex.withLock {
                val current = pendingOAuth
                if (current != null && current.attemptId == resolvedId) {
                    current.callbackOutcome.complete(outcome)
                }
                // else: discard -- current pendingOAuth (whatever it is) left untouched.
            }
        }
    }

    // ================= install_state / Setup URL return =================

    suspend fun beginInstallationReturn(): String {
        val state = GitHubPkce.generateInstallState()
        connectStateMutex.withLock {
            pendingInstallationReturn = PendingInstallationReturn(state, SystemClock.elapsedRealtime())
        }
        return state
    }

    /** `installation_id` is deliberately never read here -- GitHub's own docs call it
     * spoofable; `status()`'s authenticated `GET /user/installations` is the only real
     * authority. A missing/non-matching `state` makes NO automatic API call at all. */
    suspend fun handleInstallationReturn(context: Context, returnedState: String?): GitHubStatus? {
        val consumed = connectStateMutex.withLock {
            val current = pendingInstallationReturn
            val expired = current != null &&
                SystemClock.elapsedRealtime() - current.startedAtMonotonic > PENDING_INSTALLATION_TIMEOUT_MILLIS
            if (current != null && !expired && returnedState != null && current.state == returnedState) {
                pendingInstallationReturn = null
                true
            } else {
                if (expired) pendingInstallationReturn = null
                false
            }
        }
        if (!consumed) return null
        return status(context)
    }

    // ================= Browser launch abstraction (UI-layer injected) =================

    enum class LaunchKind { AuthTab, CustomTab, ExternalBrowser }

    internal fun isCancellableBrowserLaunch(kind: LaunchKind): Boolean =
        kind == LaunchKind.CustomTab || kind == LaunchKind.ExternalBrowser

    /** Kept pure so the timeout's domain outcome remains covered without waiting twelve
     * minutes in a JVM test. Ownership is still enforced by [finishConnectOperation]. */
    internal fun timeoutConnectOutcome(): GitHubResult<GitHubStatus> =
        GitHubResult.Success(GitHubStatus.AuthorizationRequired(null))

    /** A browser capability decision together with the exact package that made it. Keeping
     * the package makes the capability check and the subsequent launch one decision rather
     * than two resolver lookups which could disagree. */
    data class BrowserLaunch(val kind: LaunchKind, val packageName: String)

    /** Everything Activity/Compose-specific that `connect()` needs, kept out of this
     * coordinator entirely -- implemented by the UI layer (see `MainActivity`/the screens that
     * call [connect]). */
    interface GitHubBrowserLauncher {
        /** Resolves a Custom Tabs provider first, then checks *that specific* provider's Auth
         * Tab support. The returned package must be the package actually launched. Returns
         * null when no ordinary external browser can handle the authorization URL. */
        fun resolveBrowser(context: Context): BrowserLaunch?

        /** For [LaunchKind.AuthTab]: must persist [attemptId] via the launching Activity's own
         * SavedState *before* actually launching. Runs on the main thread (see `connect()`'s
         * own `Dispatchers.Main.immediate` hop before calling this). */
        fun launch(context: Context, browser: BrowserLaunch, uri: Uri, attemptId: UUID)
    }
}
