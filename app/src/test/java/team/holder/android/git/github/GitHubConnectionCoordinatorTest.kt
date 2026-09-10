package team.holder.android.git.github

import android.content.Context
import android.content.ContextWrapper
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A minimal Context that only ever needs to answer [getApplicationContext] -- everything this
 * test suite exercises goes through [FakeCredentialStore]/[FakeBrowserLauncher], neither of
 * which touches any other Context method. See app/build.gradle.kts's `unitTests
 * .isReturnDefaultValues = true` for why constructing this at all is safe under the plain
 * (non-Robolectric) unit-test stub android.jar this codebase uses everywhere else. */
private class FakeContext : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this
}

private class FakeDurableCredentialState(
    @Volatile var state: DurableGitHubCredentialState = DurableGitHubCredentialState.Absent,
)

/** Models DataStore, not SharedPreferences: a failed update never changes durable state. */
private class FakeCredentialStore(
    private val durable: FakeDurableCredentialState = FakeDurableCredentialState(),
) : GitHubCredentialStore {
    var credential: StoredGitHubCredential?
        get() = (durable.state as? DurableGitHubCredentialState.Ready)?.credential
        set(value) {
            durable.state = value?.let(DurableGitHubCredentialState::Ready)
                ?: DurableGitHubCredentialState.Absent
        }
    val durableState: DurableGitHubCredentialState get() = durable.state
    var failNextMutation = false
    var failNextReplacement: DurableGitHubCredentialState? = null
    var beforeMutation: (suspend (DurableGitHubCredentialState, DurableGitHubCredentialState) -> Unit)? = null
    val transitions = CopyOnWriteArrayList<Pair<DurableGitHubCredentialState, DurableGitHubCredentialState>>()
    var clearStartedLatch: CountDownLatch? = null
    var releaseClearLatch: CountDownLatch? = null

    override suspend fun read(context: Context): DurableGitHubCredentialState = durable.state

    override suspend fun compareAndSet(
        context: Context,
        expected: DurableGitHubCredentialState,
        replacement: DurableGitHubCredentialState,
    ): Boolean {
        if (replacement is DurableGitHubCredentialState.Absent) {
            clearStartedLatch?.countDown()
            releaseClearLatch?.await(5, TimeUnit.SECONDS)
        }
        beforeMutation?.invoke(expected, replacement)
        synchronized(durable) {
            if (durable.state != expected) return false
            if (failNextMutation || failNextReplacement == replacement) {
                failNextMutation = false
                failNextReplacement = null
                throw IOException("injected durable update failure")
            }
            durable.state = replacement
            transitions += expected to replacement
            return true
        }
    }
}

/** Never resolves a browser -- reaches `Failure(BrowserUnavailable)` without ever creating a
 * `pendingOAuth`, which is what makes it safe to use in a plain JVM unit test at all: nothing
 * on this path touches `SystemClock` (only PendingOAuth/AccessTokenCache creation does). Counts
 * invocations and can optionally hold [holdLatch] before returning, to deliberately widen the
 * window a concurrent second `connect()` call needs to observe `connectInFlight` non-null. */
private class UnavailableBrowserLauncher(
    private val startedLatch: CountDownLatch? = null,
    private val releaseLatch: CountDownLatch? = null,
) : GitHubConnectionCoordinator.GitHubBrowserLauncher {
    val resolveCallCount = AtomicInteger(0)
    @Volatile var lastAuthorizationUrl: String? = null

    override fun resolveBrowser(
        context: Context,
        authorizationUrl: String,
    ): GitHubConnectionCoordinator.BrowserLaunch? {
        resolveCallCount.incrementAndGet()
        lastAuthorizationUrl = authorizationUrl
        startedLatch?.countDown()
        releaseLatch?.await(5, TimeUnit.SECONDS)
        return null
    }

    override fun launch(context: Context, browser: GitHubConnectionCoordinator.BrowserLaunch, uri: android.net.Uri, attemptId: UUID) {
        error("never reached -- resolveBrowser always returns null in this fake")
    }
}

class GitHubConnectionCoordinatorTest {
    private val durableBackend = FakeDurableCredentialState()
    private val fakeStore = FakeCredentialStore(durableBackend)
    private val fakeContext = FakeContext()

    init {
        GitHubConnectionCoordinator.credentialStore = fakeStore
    }

    @After
    fun resetCoordinator() {
        // Public completion deliberately precedes worker shutdown. Drain the last test's
        // detached worker before replacing the singleton's store/hooks for another test.
        runBlocking {
            val coordinator = GitHubConnectionCoordinator
            val mutex = coordinator.javaClass.getDeclaredField("connectStateMutex").apply { isAccessible = true }
                .get(coordinator) as Mutex
            val shutdown = mutex.withLock {
                coordinator.javaClass.getDeclaredField("connectShutdown").apply { isAccessible = true }
                    .get(coordinator) as Deferred<*>?
            }
            kotlinx.coroutines.withTimeout(5_000) { shutdown?.await() }
        }
        GitHubConnectionCoordinator.credentialStore = RealGitHubCredentialStore
        GitHubConnectionCoordinator.storedCredentialStatusOverride = null
        GitHubConnectionCoordinator.relayRefreshOverride = null
        GitHubConnectionCoordinator.standaloneStatusOverride = null
        GitHubConnectionCoordinator.statusCredentialSnapshotOverride = null
        GitHubConnectionCoordinator.statusQueryOverride = null
        GitHubConnectionCoordinator.statusAuthenticatedUserOverride = null
        GitHubConnectionCoordinator.statusBetweenRequestsOverride = null
        GitHubConnectionCoordinator.statusInstallationsOverride = null
        GitHubConnectionCoordinator.accessTokenCache = null
        fakeStore.clearStartedLatch = null
        fakeStore.releaseClearLatch = null
        fakeStore.failNextMutation = false
        fakeStore.failNextReplacement = null
        fakeStore.beforeMutation = null
    }

    @Test
    fun connect_returnsBrowserUnavailable_whenNoBrowserResolves() = runBlocking {
        val launcher = UnavailableBrowserLauncher()
        val result = GitHubConnectionCoordinator.connect(fakeContext, launcher)
        assertEquals(GitHubResult.Failure(GitHubError.BrowserUnavailable), result)
        assertTrue(
            launcher.lastAuthorizationUrl
                ?.startsWith("https://github.com/login/oauth/authorize?") == true,
        )
    }

    @Test
    fun oauthTimeoutHasTheOrdinaryAuthorizationRequiredOutcome() {
        assertEquals(
            GitHubResult.Success(GitHubStatus.AuthorizationRequired(null)),
            GitHubConnectionCoordinator.timeoutConnectOutcome(),
        )
    }

    @Test
    fun connect_secondConcurrentCall_joinsRatherThanStartingAnIndependentOperation() = runBlocking(Dispatchers.IO) {
        // Dispatchers.IO, not the default confined runBlocking dispatcher: this test body's own
        // blocking CountDownLatch.await() calls would otherwise starve the coroutine they're
        // waiting on -- a single-threaded dispatcher has no other thread free to run it on.
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val launcher = UnavailableBrowserLauncher(startedLatch = started, releaseLatch = release)

        val first = async { GitHubConnectionCoordinator.connect(fakeContext, launcher) }
        assertTrue("first connect() never reached resolveBrowser", started.await(5, TimeUnit.SECONDS))

        // The first operation is now deliberately held open (blocked inside resolveBrowser,
        // on a real IO-dispatcher thread) -- connectInFlight must be non-null right now.
        // Run the join decision through its first suspension before releasing the worker.
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            GitHubConnectionCoordinator.connect(fakeContext, launcher)
        }

        release.countDown() // let the first (and only, if joining worked) operation finish
        val firstResult = first.await()
        val secondResult = second.await()

        assertEquals(firstResult, secondResult)
        assertEquals(
            "a joining second call must not trigger a second, independent ceremony",
            1,
            launcher.resolveCallCount.get(),
        )
    }

    @Test
    fun disconnectCancelsTheRealCallWhileTheConnectWorkerIsStillExecutingExchange() = runBlocking {
        val transport = BlockedRelayCall()
        val worker = exchangeInConnectWorker(transport)
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            GitHubConnectionCoordinator.connect(fakeContext, UnavailableBrowserLauncher())
        }
        try {
            assertTrue(transport.entered.await(5, TimeUnit.SECONDS))
            GitHubConnectionCoordinator.disconnect(fakeContext)

            assertEquals(GitHubResult.Success(GitHubStatus.NotConnected), first.await())
            assertFalse(worker.await().isCompleted)
            assertTrue("disconnect must cancel the live OkHttp Call", transport.call!!.isCanceled())
            assertEquals(1, transport.cancellations.get())
        } finally {
            transport.release.countDown()
            GitHubConnectionCoordinator.disconnect(fakeContext)
            worker.await().join()
            first.await()
        }
    }

    @Test
    fun replacementAndItsJoinersWaitForDetachedExchangeShutdownWithoutHoldingStateMutex() = runBlocking {
        val transport = BlockedRelayCall()
        val worker = exchangeInConnectWorker(transport)
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            GitHubConnectionCoordinator.connect(fakeContext, UnavailableBrowserLauncher())
        }
        val replacementStarted = CountDownLatch(1)
        val releaseReplacement = CountDownLatch(1)
        val browser = UnavailableBrowserLauncher(replacementStarted, releaseReplacement)
        try {
            assertTrue(transport.entered.await(5, TimeUnit.SECONDS))
            val oldAttempt = currentAttempt()!!
            val firstJoiner = async(start = CoroutineStart.UNDISPATCHED) {
                GitHubConnectionCoordinator.connect(fakeContext, browser)
            }
            GitHubConnectionCoordinator.disconnect(fakeContext)
            assertEquals(GitHubResult.Success(GitHubStatus.NotConnected), first.await())
            assertEquals(first.await(), firstJoiner.await())

            val second = async(start = CoroutineStart.UNDISPATCHED) {
                GitHubConnectionCoordinator.connect(fakeContext, browser)
            }
            val secondJoiner = async(start = CoroutineStart.UNDISPATCHED) {
                GitHubConnectionCoordinator.connect(fakeContext, browser)
            }
            val cancelledWaiter = async(start = CoroutineStart.UNDISPATCHED) {
                GitHubConnectionCoordinator.connect(fakeContext, browser)
            }
            cancelledWaiter.cancel()
            cancelledWaiter.join()

            // These transitions acquire connectStateMutex while the old HTTP execution is
            // still held. Neither the mutex nor a new operation may span the shutdown wait.
            assertFalse(GitHubConnectionCoordinator.cancelPendingBrowserAuthorization())
            GitHubConnectionCoordinator.beginInstallationReturn()
            assertNull("B was admitted before A's synchronous exchange stopped", currentAttempt())
            assertEquals(0, browser.resolveCallCount.get())
            assertFalse(worker.await().isCompleted)
            assertFalse(second.isCompleted)
            assertEquals(1L, transport.exited.count)

            transport.release.countDown()
            worker.await().join()
            // Let the callers waiting on the shutdown signal run their admission/join step.
            kotlinx.coroutines.yield()
            assertTrue(replacementStarted.await(5, TimeUnit.SECONDS))
            val newAttempt = currentAttempt()!!
            assertFalse(oldAttempt == newAttempt)
            assertFalse(finishStaleAttempt(oldAttempt))
            assertEquals(newAttempt, currentAttempt())
            assertFalse(second.isCompleted)

            releaseReplacement.countDown()
            assertEquals(GitHubResult.Failure(GitHubError.BrowserUnavailable), second.await())
            assertEquals(second.await(), secondJoiner.await())
            assertEquals(1, browser.resolveCallCount.get())
            assertEquals(1, transport.requests.get())
        } finally {
            transport.release.countDown()
            releaseReplacement.countDown()
            GitHubConnectionCoordinator.disconnect(fakeContext)
            worker.await().join()
        }
    }

    @Test
    fun publicCompletionCannotAdmitReplacementBeforeWorkerCancellationIsRequested() = runBlocking {
        val transport = BlockedRelayCall()
        val worker = exchangeInConnectWorker(transport)
        val browser = UnavailableBrowserLauncher()
        val reentered = CompletableDeferred<Unit>()
        // An unconfined caller resumes inside result.complete(), before the finalizer's
        // next statement can cancel A. This deterministically opens the audited tiny gap.
        val caller = async(Dispatchers.Unconfined) {
            val outcome = GitHubConnectionCoordinator.connect(fakeContext, browser)
            assertEquals(GitHubResult.Success(GitHubStatus.NotConnected), outcome)
            assertFalse(transport.call!!.isCanceled())
            assertFalse(worker.await().isCancelled)
            val replacement = async(start = CoroutineStart.UNDISPATCHED) {
                GitHubConnectionCoordinator.connect(fakeContext, browser)
            }
            assertNull(currentAttempt())
            assertEquals(0, browser.resolveCallCount.get())
            reentered.complete(Unit)
            replacement.await()
        }
        try {
            assertTrue(transport.entered.await(5, TimeUnit.SECONDS))
            GitHubConnectionCoordinator.disconnect(fakeContext)
            reentered.await()
            assertTrue(transport.call!!.isCanceled())
            assertFalse(worker.await().isCompleted)
            transport.release.countDown()
            assertEquals(GitHubResult.Failure(GitHubError.BrowserUnavailable), caller.await())
            assertEquals(1, browser.resolveCallCount.get())
        } finally {
            transport.release.countDown()
            GitHubConnectionCoordinator.disconnect(fakeContext)
            worker.await().join()
        }
    }

    @Test
    fun replacementWaitsForBlockedBrowserResolutionToReturn() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            GitHubConnectionCoordinator.connect(fakeContext, UnavailableBrowserLauncher(started, release))
        }
        val nextBrowser = UnavailableBrowserLauncher()
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            GitHubConnectionCoordinator.disconnect(fakeContext)
            assertEquals(GitHubResult.Success(GitHubStatus.NotConnected), first.await())
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                GitHubConnectionCoordinator.connect(fakeContext, nextBrowser)
            }
            assertNull(currentAttempt())
            assertEquals(0, nextBrowser.resolveCallCount.get())
            release.countDown()
            assertEquals(GitHubResult.Failure(GitHubError.BrowserUnavailable), second.await())
            assertEquals(1, nextBrowser.resolveCallCount.get())
        } finally {
            release.countDown()
            GitHubConnectionCoordinator.disconnect(fakeContext)
        }
    }

    @Test
    fun cancellingAnOrdinaryCallerDoesNotCancelTheSharedOperation() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val browser = UnavailableBrowserLauncher(started, release)
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            GitHubConnectionCoordinator.connect(fakeContext, browser)
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val attempt = currentAttempt()
            val joiner = async(start = CoroutineStart.UNDISPATCHED) {
                GitHubConnectionCoordinator.connect(fakeContext, browser)
            }
            first.cancel()
            first.join()
            assertEquals(attempt, currentAttempt())
            assertFalse(joiner.isCompleted)
            release.countDown()
            assertEquals(GitHubResult.Failure(GitHubError.BrowserUnavailable), joiner.await())
            assertEquals(1, browser.resolveCallCount.get())
        } finally {
            release.countDown()
            GitHubConnectionCoordinator.disconnect(fakeContext)
        }
    }

    /** The existing stored-status seam places a real OAuth exchange inside the actual
     * coordinator-owned worker without requiring Android's Uri/browser runtime on the JVM.
     * The HTTP execution, public result, finalizer, disconnect, and admission are production. */
    private fun exchangeInConnectWorker(transport: BlockedRelayCall): CompletableDeferred<Job> {
        fakeStore.credential = StoredGitHubCredential("refresh", "cap", Long.MAX_VALUE)
        val worker = CompletableDeferred<Job>()
        GitHubConnectionCoordinator.storedCredentialStatusOverride = {
            worker.complete(coroutineContext[Job]!!)
            GitHubOAuth.exchangeCode(transport.client, "code", "verifier")
            GitHubResult.Success(GitHubStatus.NotConnected)
        }
        return worker
    }

    // Inspect ownership under its real mutex; do not add production lifecycle test hooks.
    private suspend fun currentAttempt(): UUID? {
        val coordinator = GitHubConnectionCoordinator
        val mutex = coordinator.javaClass.getDeclaredField("connectStateMutex").apply { isAccessible = true }
            .get(coordinator) as Mutex
        return mutex.withLock {
            val operation = coordinator.javaClass.getDeclaredField("connectInFlight").apply { isAccessible = true }
                .get(coordinator) ?: return@withLock null
            operation.javaClass.getDeclaredField("attemptId").apply { isAccessible = true }.get(operation) as UUID
        }
    }

    /** Installs a complete authoritative credential generation under the production mutex.
     * This is deliberately reflection-only test machinery: production replacement remains
     * confined to OAuth commit, refresh rotation, and disconnect. */
    @Suppress("UNCHECKED_CAST")
    private suspend fun replaceCredentialStateForTest(
        credential: StoredGitHubCredential,
        cache: GitHubConnectionCoordinator.AccessTokenCache,
        status: GitHubStatus,
    ) {
        val coordinator = GitHubConnectionCoordinator
        val mutex = coordinator.javaClass.getDeclaredField("credentialMutationMutex").apply { isAccessible = true }
            .get(coordinator) as Mutex
        val epoch = coordinator.javaClass.getDeclaredField("credentialEpoch").apply { isAccessible = true }
            .get(coordinator) as AtomicLong
        val statusFlow = coordinator.javaClass.getDeclaredField("mutableStatusFlow").apply { isAccessible = true }
            .get(coordinator) as MutableStateFlow<GitHubStatus>
        mutex.withLock {
            fakeStore.credential = credential
            coordinator.accessTokenCache = cache
            epoch.incrementAndGet()
            statusFlow.value = status
        }
    }

    /** Reconstructs the store-facing process state from the shared durable representation.
     * This intentionally creates a new store instance and drops cache/status/epoch state. */
    @Suppress("UNCHECKED_CAST")
    private suspend fun restartCredentialProcess(
        durable: FakeDurableCredentialState = durableBackend,
    ): FakeCredentialStore {
        val coordinator = GitHubConnectionCoordinator
        val mutex = coordinator.javaClass.getDeclaredField("credentialMutationMutex").apply { isAccessible = true }
            .get(coordinator) as Mutex
        val epoch = coordinator.javaClass.getDeclaredField("credentialEpoch").apply { isAccessible = true }
            .get(coordinator) as AtomicLong
        val statusFlow = coordinator.javaClass.getDeclaredField("mutableStatusFlow").apply { isAccessible = true }
            .get(coordinator) as MutableStateFlow<GitHubStatus>
        val freshStore = FakeCredentialStore(durable)
        mutex.withLock {
            coordinator.credentialStore = freshStore
            coordinator.accessTokenCache = null
            epoch.set(0L)
            statusFlow.value = GitHubStatus.NotConnected
        }
        return freshStore
    }

    private fun credentialEpochForTest(): Long {
        val epoch = GitHubConnectionCoordinator.javaClass
            .getDeclaredField("credentialEpoch")
            .apply { isAccessible = true }
            .get(GitHubConnectionCoordinator) as AtomicLong
        return epoch.get()
    }

    private fun rotatedTokens(
        accessToken: String = "gho_rotated",
        refreshToken: String = "ghr_rotated",
        refreshCap: String = "cap_rotated",
    ) = GitHubTokens(
        accessToken = accessToken,
        expiresInSeconds = 3600,
        refreshToken = refreshToken,
        refreshCap = refreshCap,
        refreshTokenExpiresInSeconds = 3600,
    )

    private suspend fun finishStaleAttempt(attemptId: UUID): Boolean = suspendCoroutineUninterceptedOrReturn { continuation ->
        GitHubConnectionCoordinator.javaClass.declaredMethods.single { it.name == "finishConnectOperation" }
            .apply { isAccessible = true }
            .invoke(GitHubConnectionCoordinator, attemptId, GitHubResult.Success(GitHubStatus.NotConnected), false, true, continuation)
    }

    @Test
    fun connect_resumesAgainstAStoredCredential_ratherThanStartingANewCeremony_whenNoBrowserIsNeededForThat() = runBlocking {
        // No stored credential -> BrowserUnavailable, since the entry rule falls through to a
        // fresh ceremony. This documents that boundary at the type the entry rule actually
        // branches on, without needing a real GitHub network call (status() against a stored
        // credential is out of scope for a plain JVM test -- it calls GitHubApi over real
        // OkHttp, not seamed here).
        assertNull(fakeStore.credential)
        val result = GitHubConnectionCoordinator.connect(fakeContext, UnavailableBrowserLauncher())
        assertEquals(GitHubResult.Failure(GitHubError.BrowserUnavailable), result)
    }

    @Test
    fun connect_replacesADefinitelyRejectedStoredCredentialWithinTheSameOperation() = runBlocking {
        fakeStore.credential = StoredGitHubCredential("ghr_expired", "cap_expired", 1L)
        GitHubConnectionCoordinator.storedCredentialStatusOverride = {
            GitHubResult.Failure(GitHubError.AuthorizationRequired)
        }
        val browser = UnavailableBrowserLauncher()

        val result = GitHubConnectionCoordinator.connect(fakeContext, browser)

        // The browser is reached in this one connect() call (rather than returning a bare
        // AuthorizationRequired), and the known-dead credential is gone before it starts.
        assertEquals(GitHubResult.Failure(GitHubError.BrowserUnavailable), result)
        assertEquals(1, browser.resolveCallCount.get())
        assertNull(fakeStore.credential)
    }

    @Test
    fun connect_retainsAStoredCredentialForAnOperationalResumeFailure() = runBlocking {
        val credential = StoredGitHubCredential("ghr_current", "cap_current", 1L)
        fakeStore.credential = credential
        val operationalFailure = GitHubResult.Failure(GitHubError.NetworkError(IOException("offline")))
        GitHubConnectionCoordinator.storedCredentialStatusOverride = { operationalFailure }
        val browser = UnavailableBrowserLauncher()

        val result = GitHubConnectionCoordinator.connect(fakeContext, browser)

        assertEquals(operationalFailure, result)
        assertEquals(credential, fakeStore.credential)
        assertEquals(0, browser.resolveCallCount.get())
    }

    @Test
    fun freshOAuthCredentialPersistenceFailureNeverPublishesOrSurvivesRestart() =
        runBlocking(Dispatchers.IO) {
            val previousCredential = StoredGitHubCredential("ghr_a_quarantined", "cap_a", Long.MAX_VALUE)
            durableBackend.state = DurableGitHubCredentialState.Refreshing(previousCredential)
            val processStore = restartCredentialProcess()
            val browserStarted = CountDownLatch(1)
            val releaseBrowser = CountDownLatch(1)
            val browser = UnavailableBrowserLauncher(browserStarted, releaseBrowser)
            val connect = async { GitHubConnectionCoordinator.connect(fakeContext, browser) }
            assertTrue(browserStarted.await(5, TimeUnit.SECONDS))

            val epochBefore = credentialEpochForTest()
            processStore.failNextMutation = true
            val tokens = rotatedTokens(
                accessToken = "gho_b_uncommitted",
                refreshToken = "ghr_b_uncommitted",
                refreshCap = "cap_b_uncommitted",
            )
            try {
                GitHubConnectionCoordinator.commitCredentialForConnectOperation(
                    fakeContext,
                    currentAttempt()!!,
                    tokens,
                )
                throw AssertionError("fresh credential persistence failure was not reported")
            } catch (_: IOException) {
                // Expected injected durable failure.
            } finally {
                releaseBrowser.countDown()
            }

            assertEquals(GitHubResult.Failure(GitHubError.BrowserUnavailable), connect.await())
            assertEquals(DurableGitHubCredentialState.Refreshing(previousCredential), processStore.durableState)
            assertNull(GitHubConnectionCoordinator.accessTokenCache)
            assertEquals(epochBefore, credentialEpochForTest())
            assertEquals(GitHubStatus.NotConnected, GitHubConnectionCoordinator.statusFlow.value)

            val restartedStore = restartCredentialProcess()
            val restarted = GitHubConnectionCoordinator.snapshotCredentialState(fakeContext)
            assertEquals(DurableGitHubCredentialState.Refreshing(previousCredential), restartedStore.durableState)
            assertNull(restarted.credential)
            assertNull(restarted.accessTokenCache)
        }

    @Test
    fun failedPreRefreshQuarantineNeverSendsReadyCredential() = runBlocking {
        val credential = StoredGitHubCredential("ghr_a", "cap_a", Long.MAX_VALUE)
        fakeStore.credential = credential
        fakeStore.failNextMutation = true
        val relayCalls = AtomicInteger(0)
        GitHubConnectionCoordinator.relayRefreshOverride = { _, _, _ ->
            relayCalls.incrementAndGet()
            error("refresh must not start before the quarantine commits")
        }

        val result = GitHubConnectionCoordinator.withAccessToken<String>(fakeContext) {
            error("a failed refresh admission must not supply an access token")
        }

        assertTrue((result as GitHubResult.Failure).error is GitHubError.Unexpected)
        assertEquals(0, relayCalls.get())
        assertEquals(DurableGitHubCredentialState.Ready(credential), fakeStore.durableState)
        assertNull(GitHubConnectionCoordinator.accessTokenCache)

        val tokens = rotatedTokens()
        GitHubConnectionCoordinator.relayRefreshOverride = { _, refreshToken, refreshCap ->
            assertEquals("ghr_a" to "cap_a", refreshToken to refreshCap)
            RelayResult.Success(tokens)
        }
        assertEquals(
            GitHubResult.Success(tokens.accessToken),
            GitHubConnectionCoordinator.withAccessToken(fakeContext) { GitHubResult.Success(it) },
        )
    }

    @Test
    fun processRestartKeepsAdmittedRefreshCredentialQuarantinedAndNeverResubmitsIt() =
        runBlocking {
            val credential = StoredGitHubCredential("ghr_a", "cap_a", Long.MAX_VALUE)
            fakeStore.credential = credential
            val refreshStarted = CompletableDeferred<Unit>()
            val holdRefresh = CompletableDeferred<Unit>()
            GitHubConnectionCoordinator.relayRefreshOverride = { _, _, _ ->
                refreshStarted.complete(Unit)
                holdRefresh.await()
                error("cancelled simulated process must not complete its relay call")
            }

            val oldProcess = async {
                GitHubConnectionCoordinator.withAccessToken<String>(fakeContext) {
                    error("held refresh must not yield a token")
                }
            }
            refreshStarted.await()
            assertEquals(DurableGitHubCredentialState.Refreshing(credential), fakeStore.durableState)
            oldProcess.cancelAndJoin()

            val restartedStore = restartCredentialProcess()
            val restartRelayCalls = AtomicInteger(0)
            GitHubConnectionCoordinator.relayRefreshOverride = { _, _, _ ->
                restartRelayCalls.incrementAndGet()
                error("a restarted process must not resubmit quarantined A")
            }
            val result = GitHubConnectionCoordinator.withAccessToken<String>(fakeContext) {
                error("Refreshing(A) must not expose an access token")
            }

            assertEquals(GitHubResult.Failure(GitHubError.AuthorizationRequired), result)
            assertEquals(0, restartRelayCalls.get())
            assertEquals(DurableGitHubCredentialState.Refreshing(credential), restartedStore.durableState)
            assertNull(GitHubConnectionCoordinator.accessTokenCache)
        }

    @Test
    fun successfulRefreshRotatesThroughQuarantineBeforePublishingNewAuthority() = runBlocking {
        val credential = StoredGitHubCredential("ghr_a", "cap_a", Long.MAX_VALUE)
        fakeStore.credential = credential
        val tokens = rotatedTokens()
        val relayCalls = AtomicInteger(0)
        val rotatedCommitStarted = CompletableDeferred<Unit>()
        val releaseRotatedCommit = CompletableDeferred<Unit>()
        fakeStore.beforeMutation = { _, replacement ->
            val replacementCredential = (replacement as? DurableGitHubCredentialState.Ready)?.credential
            if (replacementCredential?.refreshToken == tokens.refreshToken) {
                rotatedCommitStarted.complete(Unit)
                releaseRotatedCommit.await()
            }
        }
        GitHubConnectionCoordinator.relayRefreshOverride = { _, refreshToken, refreshCap ->
            relayCalls.incrementAndGet()
            assertEquals("ghr_a" to "cap_a", refreshToken to refreshCap)
            assertEquals(DurableGitHubCredentialState.Refreshing(credential), fakeStore.durableState)
            assertNull(GitHubConnectionCoordinator.accessTokenCache)
            RelayResult.Success(tokens)
        }

        val request = async {
            GitHubConnectionCoordinator.withAccessToken(fakeContext) { accessToken ->
                GitHubResult.Success(accessToken)
            }
        }
        rotatedCommitStarted.await()
        assertEquals(DurableGitHubCredentialState.Refreshing(credential), fakeStore.durableState)
        assertNull(GitHubConnectionCoordinator.accessTokenCache)
        assertFalse(request.isCompleted)
        releaseRotatedCommit.complete(Unit)
        val result = request.await()

        assertEquals(GitHubResult.Success(tokens.accessToken), result)
        assertEquals(1, relayCalls.get())
        val rotated = (fakeStore.durableState as DurableGitHubCredentialState.Ready).credential
        assertEquals(tokens.refreshToken, rotated.refreshToken)
        assertEquals(tokens.refreshCap, rotated.refreshCap)
        assertEquals(tokens.accessToken, GitHubConnectionCoordinator.accessTokenCache?.accessToken)
        assertEquals(2, fakeStore.transitions.size)
        assertEquals(
            DurableGitHubCredentialState.Ready(credential) to DurableGitHubCredentialState.Refreshing(credential),
            fakeStore.transitions[0],
        )
        assertTrue(fakeStore.transitions[1].first is DurableGitHubCredentialState.Refreshing)
        assertTrue(fakeStore.transitions[1].second is DurableGitHubCredentialState.Ready)
    }

    @Test
    fun rotatedCredentialPersistenceFailureLeavesOnlyQuarantineAcrossRestart() = runBlocking {
        val credential = StoredGitHubCredential("ghr_a", "cap_a", Long.MAX_VALUE)
        fakeStore.credential = credential
        val relayCalls = AtomicInteger(0)
        GitHubConnectionCoordinator.relayRefreshOverride = { _, _, _ ->
            relayCalls.incrementAndGet()
            fakeStore.failNextMutation = true
            RelayResult.Success(rotatedTokens(accessToken = "gho_a_prime"))
        }

        val result = GitHubConnectionCoordinator.withAccessToken<String>(fakeContext) {
            error("uncommitted A' must not become usable")
        }

        assertEquals(GitHubResult.Failure(GitHubError.AuthorizationRequired), result)
        assertEquals(1, relayCalls.get())
        assertEquals(DurableGitHubCredentialState.Refreshing(credential), fakeStore.durableState)
        assertNull(GitHubConnectionCoordinator.accessTokenCache)

        val restartedStore = restartCredentialProcess()
        val restartRelayCalls = AtomicInteger(0)
        GitHubConnectionCoordinator.relayRefreshOverride = { _, _, _ ->
            restartRelayCalls.incrementAndGet()
            error("restart must use neither A nor uncommitted A'")
        }
        val restartedResult = GitHubConnectionCoordinator.withAccessToken<String>(fakeContext) {
            error("restart must expose no token")
        }
        assertEquals(GitHubResult.Failure(GitHubError.AuthorizationRequired), restartedResult)
        assertEquals(0, restartRelayCalls.get())
        assertEquals(DurableGitHubCredentialState.Refreshing(credential), restartedStore.durableState)
    }

    @Test
    fun ambiguousRefreshOutcomesClearTheCredentialBeforeTheNextAccessCanRetryIt() = runBlocking {
        val unsafeErrors = listOf(
            RelayError.OutcomeUnknown,
            RelayError.AmbiguousTransportFailure,
        )

        unsafeErrors.forEach { error ->
            fakeStore.credential = StoredGitHubCredential("ghr_spent", "cap_spent", Long.MAX_VALUE)
            GitHubConnectionCoordinator.accessTokenCache = null
            GitHubConnectionCoordinator.relayRefreshOverride = { _, _, _ -> RelayResult.Failure(error) }

            val result = GitHubConnectionCoordinator.withAccessToken<String>(fakeContext) { _ ->
                throw AssertionError("an unsafe refresh result must never supply an access token")
            }

            assertEquals(GitHubResult.Failure(GitHubError.AuthorizationRequired), result)
            assertNull(fakeStore.credential)
            assertNull(GitHubConnectionCoordinator.accessTokenCache)
        }
    }

    @Test
    fun definiteRefreshAuthorizationRejectionClearsTheCredentialBeforeAnotherDirectOperation() = runBlocking {
        fakeStore.credential = StoredGitHubCredential("ghr_rejected", "cap_rejected", Long.MAX_VALUE)
        val relayCalls = AtomicInteger(0)
        GitHubConnectionCoordinator.relayRefreshOverride = { _, _, _ ->
            relayCalls.incrementAndGet()
            // Invalid refresh capability and GitHub's bad_refresh_token both map to this
            // reason-less definite rejection at the Android relay boundary.
            RelayResult.Failure(RelayError.AuthorizationRequired(null))
        }

        val first = GitHubConnectionCoordinator.withAccessToken<String>(fakeContext) {
            error("a rejected refresh credential must not supply an access token")
        }
        val second = GitHubConnectionCoordinator.withAccessToken<String>(fakeContext) {
            error("a disposed refresh credential must not be reused")
        }

        assertEquals(GitHubResult.Failure(GitHubError.AuthorizationRequired), first)
        assertEquals(GitHubResult.Failure(GitHubError.AuthorizationRequired), second)
        assertEquals("the rejected refresh credential was submitted more than once", 1, relayCalls.get())
        assertNull(fakeStore.credential)
        assertNull(GitHubConnectionCoordinator.accessTokenCache)
    }

    @Test
    fun operationalUnexpectedRefreshFailureRetainsTheCommittedCredential() = runBlocking {
        val credential = StoredGitHubCredential("ghr_current", "cap_current", Long.MAX_VALUE)
        fakeStore.credential = credential
        val unexpected = RelayError.Unexpected(503, "malformed relay response")
        GitHubConnectionCoordinator.relayRefreshOverride = { _, _, _ -> RelayResult.Failure(unexpected) }

        val result = GitHubConnectionCoordinator.withAccessToken<String>(fakeContext) {
            error("an operational refresh failure must not supply an access token")
        }

        assertEquals(GitHubResult.Failure(GitHubError.Unexpected(unexpected.httpStatus, unexpected.body)), result)
        assertEquals(credential, fakeStore.credential)
        assertEquals(DurableGitHubCredentialState.Ready(credential), fakeStore.durableState)
        assertEquals(2, fakeStore.transitions.size)
        assertNull(GitHubConnectionCoordinator.accessTokenCache)
    }

    @Test
    fun ambiguousRefreshClearFailureLeavesQuarantineAndRestartCannotReuseIt() = runBlocking {
        val credential = StoredGitHubCredential("ghr_ambiguous", "cap_ambiguous", Long.MAX_VALUE)
        fakeStore.credential = credential
        val relayCalls = AtomicInteger(0)
        GitHubConnectionCoordinator.relayRefreshOverride = { _, _, _ ->
            relayCalls.incrementAndGet()
            fakeStore.failNextMutation = true
            RelayResult.Failure(RelayError.OutcomeUnknown)
        }

        val result = GitHubConnectionCoordinator.withAccessToken<String>(fakeContext) {
            error("ambiguous refresh must expose no access token")
        }
        assertEquals(GitHubResult.Failure(GitHubError.AuthorizationRequired), result)
        assertEquals(1, relayCalls.get())
        assertEquals(DurableGitHubCredentialState.Refreshing(credential), fakeStore.durableState)
        assertEquals(GitHubStatus.NotConnected, GitHubConnectionCoordinator.statusFlow.value)

        val restartedStore = restartCredentialProcess()
        GitHubConnectionCoordinator.relayRefreshOverride = { _, _, _ ->
            throw AssertionError("restart retried ambiguous A")
        }
        assertEquals(
            GitHubResult.Failure(GitHubError.AuthorizationRequired),
            GitHubConnectionCoordinator.withAccessToken<String>(fakeContext) { error("no token expected") },
        )
        assertEquals(DurableGitHubCredentialState.Refreshing(credential), restartedStore.durableState)
    }

    @Test
    fun definiteRefreshRejectionClearFailureLeavesQuarantineAcrossRestart() = runBlocking {
        val credential = StoredGitHubCredential("ghr_rejected", "cap_rejected", Long.MAX_VALUE)
        fakeStore.credential = credential
        val relayCalls = AtomicInteger(0)
        GitHubConnectionCoordinator.relayRefreshOverride = { _, _, _ ->
            relayCalls.incrementAndGet()
            fakeStore.failNextMutation = true
            RelayResult.Failure(RelayError.AuthorizationRequired(null))
        }

        val result = GitHubConnectionCoordinator.withAccessToken<String>(fakeContext) {
            error("rejected refresh must expose no access token")
        }
        assertEquals(GitHubResult.Failure(GitHubError.AuthorizationRequired), result)
        assertEquals(1, relayCalls.get())
        assertEquals(DurableGitHubCredentialState.Refreshing(credential), fakeStore.durableState)

        val restartedStore = restartCredentialProcess()
        GitHubConnectionCoordinator.relayRefreshOverride = { _, _, _ ->
            throw AssertionError("restart retried definitely rejected A")
        }
        assertEquals(
            GitHubResult.Failure(GitHubError.AuthorizationRequired),
            GitHubConnectionCoordinator.withAccessToken<String>(fakeContext) { error("no token expected") },
        )
        assertEquals(DurableGitHubCredentialState.Refreshing(credential), restartedStore.durableState)
    }

    @Test
    fun operationalUnexpectedRestorationFailureLeavesQuarantineAndFailsClosed() = runBlocking {
        val credential = StoredGitHubCredential("ghr_operational", "cap_operational", Long.MAX_VALUE)
        fakeStore.credential = credential
        GitHubConnectionCoordinator.relayRefreshOverride = { _, _, _ ->
            fakeStore.failNextMutation = true
            RelayResult.Failure(RelayError.Unexpected(500, "relay configuration failure"))
        }

        val result = GitHubConnectionCoordinator.withAccessToken<String>(fakeContext) {
            error("failed restoration must expose no access token")
        }

        assertEquals(GitHubResult.Failure(GitHubError.AuthorizationRequired), result)
        assertEquals(DurableGitHubCredentialState.Refreshing(credential), fakeStore.durableState)
        assertEquals(GitHubStatus.NotConnected, GitHubConnectionCoordinator.statusFlow.value)
        val restartedStore = restartCredentialProcess()
        assertNull(GitHubConnectionCoordinator.snapshotCredentialState(fakeContext).credential)
        assertEquals(DurableGitHubCredentialState.Refreshing(credential), restartedStore.durableState)
    }

    @Test
    fun staleAmbiguousRefreshCannotDeleteNewerCredential() = runBlocking(Dispatchers.IO) {
        val credentialA = StoredGitHubCredential("ghr_a", "cap_a", Long.MAX_VALUE)
        fakeStore.credential = credentialA
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        GitHubConnectionCoordinator.relayRefreshOverride = { _, _, _ ->
            refreshStarted.complete(Unit)
            releaseRefresh.await()
            RelayResult.Failure(RelayError.OutcomeUnknown)
        }

        val oldRefresh = async {
            GitHubConnectionCoordinator.withAccessToken<String>(fakeContext) {
                error("stale refresh must not supply a token")
            }
        }
        refreshStarted.await()
        assertEquals(DurableGitHubCredentialState.Refreshing(credentialA), fakeStore.durableState)
        val credentialB = StoredGitHubCredential("ghr_b", "cap_b", Long.MAX_VALUE)
        val cacheB = GitHubConnectionCoordinator.AccessTokenCache("gho_b", 60_000L)
        val statusB = GitHubStatus.Connected("bob", "https://github.com/settings/installations/2")
        replaceCredentialStateForTest(credentialB, cacheB, statusB)
        releaseRefresh.complete(Unit)

        assertEquals(GitHubResult.Failure(GitHubError.AuthorizationRequired), oldRefresh.await())
        assertEquals(DurableGitHubCredentialState.Ready(credentialB), fakeStore.durableState)
        assertEquals(cacheB, GitHubConnectionCoordinator.accessTokenCache)
        assertEquals(statusB, GitHubConnectionCoordinator.statusFlow.value)
    }

    @Test
    fun advisoryExpiredRefreshCredentialSkipsTheRelayCall() = runBlocking {
        fakeStore.credential = StoredGitHubCredential("ghr_expired", "cap_expired", 1L)
        val relayCalls = AtomicInteger(0)
        GitHubConnectionCoordinator.relayRefreshOverride = { _, _, _ ->
            relayCalls.incrementAndGet()
            error("an advisory-expired credential must not spend a relay refresh call")
        }

        val result = GitHubConnectionCoordinator.withAccessToken<String>(fakeContext) {
            error("an expired credential must not yield an access token")
        }

        assertEquals(GitHubResult.Failure(GitHubError.AuthorizationRequired), result)
        assertEquals(0, relayCalls.get())
    }

    @Test
    fun credentialSnapshotReadsEpochRecordAndCacheAsOneState() = runBlocking {
        val credential = StoredGitHubCredential("ghr_current", "cap_current", Long.MAX_VALUE)
        val cache = GitHubConnectionCoordinator.AccessTokenCache("gho_current", 60_000L)
        fakeStore.credential = credential
        GitHubConnectionCoordinator.accessTokenCache = cache

        val snapshot = GitHubConnectionCoordinator.snapshotCredentialState(fakeContext)

        assertEquals(credential, snapshot.credential)
        assertEquals(cache, snapshot.accessTokenCache)
    }

    @Test
    fun standaloneStatusStopsBeforeUsingAnyCredentialWhenItsSnapshotWasReplaced() = runBlocking(Dispatchers.IO) {
        val credentialA = StoredGitHubCredential("ghr_a", "cap_a", Long.MAX_VALUE)
        val cacheA = GitHubConnectionCoordinator.AccessTokenCache("gho_a", 60_000L)
        fakeStore.credential = credentialA
        GitHubConnectionCoordinator.accessTokenCache = cacheA

        val snapshotCaptured = CompletableDeferred<GitHubConnectionCoordinator.CredentialStateSnapshot>()
        val releaseSnapshot = CompletableDeferred<Unit>()
        val usedTokens = mutableListOf<String>()
        val staleResult = GitHubStatus.Connected("alice", "https://github.com/settings/installations/1")
        val newerStatus = GitHubStatus.Connected("bob", "https://github.com/settings/installations/2")
        GitHubConnectionCoordinator.statusCredentialSnapshotOverride = { snapshot ->
            snapshotCaptured.complete(snapshot)
            releaseSnapshot.await()
        }
        GitHubConnectionCoordinator.statusQueryOverride = { accessToken ->
            usedTokens += accessToken
            GitHubResult.Success(staleResult)
        }

        val query = async { GitHubConnectionCoordinator.status(fakeContext) }
        val snapshot = snapshotCaptured.await()
        assertEquals(credentialA, snapshot.credential)
        assertEquals(cacheA, snapshot.accessTokenCache)

        replaceCredentialStateForTest(
            credential = StoredGitHubCredential("ghr_b", "cap_b", Long.MAX_VALUE),
            cache = GitHubConnectionCoordinator.AccessTokenCache("gho_b", 60_000L),
            status = newerStatus,
        )
        releaseSnapshot.complete(Unit)

        assertEquals(newerStatus, query.await())
        assertEquals(newerStatus, GitHubConnectionCoordinator.statusFlow.value)
        assertTrue("a stale status must use neither its old token nor the replacement token", usedTokens.isEmpty())
    }

    @Test
    fun standaloneStatusUsesExpectedCredentialWhenAuthorityIsUnchanged() = runBlocking {
        val credential = StoredGitHubCredential("ghr_current", "cap_current", Long.MAX_VALUE)
        val cache = GitHubConnectionCoordinator.AccessTokenCache("gho_current", 60_000L)
        val connected = GitHubStatus.Connected("alice", "https://github.com/settings/installations/1")
        fakeStore.credential = credential
        GitHubConnectionCoordinator.accessTokenCache = cache
        val requests = mutableListOf<Pair<String, String>>()
        GitHubConnectionCoordinator.statusAuthenticatedUserOverride = { accessToken ->
            requests += "/user" to accessToken
            GitHubResult.Success(GitHubAuthenticatedUser(id = 1L, login = "alice"))
        }
        GitHubConnectionCoordinator.statusInstallationsOverride = { accessToken ->
            requests += "/user/installations" to accessToken
            GitHubResult.Success(listOf(GitHubInstallation(1L, 1L, "alice", "User")))
        }

        assertEquals(connected, GitHubConnectionCoordinator.status(fakeContext))
        assertEquals(connected, GitHubConnectionCoordinator.statusFlow.value)
        assertEquals(
            listOf("/user" to "gho_current", "/user/installations" to "gho_current"),
            requests,
        )
        assertEquals(credential, fakeStore.credential)
        assertEquals(cache, GitHubConnectionCoordinator.accessTokenCache)
    }

    @Test
    fun standaloneStatusCarriesItsOwnRefreshAuthorityAcrossBothRequests() = runBlocking {
        fakeStore.credential = StoredGitHubCredential("ghr_a", "cap_a", Long.MAX_VALUE)
        GitHubConnectionCoordinator.accessTokenCache = null
        val refreshedTokens = GitHubTokens(
            accessToken = "gho_a_rotated",
            expiresInSeconds = 3600,
            refreshToken = "ghr_a_rotated",
            refreshCap = "cap_a_rotated",
            refreshTokenExpiresInSeconds = 3600,
        )
        val refreshAuthorities = mutableListOf<Pair<String, String>>()
        val requests = mutableListOf<Pair<String, String>>()
        GitHubConnectionCoordinator.relayRefreshOverride = { _, refreshToken, refreshCap ->
            refreshAuthorities += refreshToken to refreshCap
            RelayResult.Success(refreshedTokens)
        }
        GitHubConnectionCoordinator.statusAuthenticatedUserOverride = { accessToken ->
            requests += "/user" to accessToken
            GitHubResult.Success(GitHubAuthenticatedUser(id = 1L, login = "alice"))
        }
        GitHubConnectionCoordinator.statusInstallationsOverride = { accessToken ->
            requests += "/user/installations" to accessToken
            GitHubResult.Success(listOf(GitHubInstallation(1L, 1L, "alice", "User")))
        }
        val connected = GitHubStatus.Connected("alice", "https://github.com/settings/installations/1")

        assertEquals(connected, GitHubConnectionCoordinator.status(fakeContext))
        assertEquals(listOf("ghr_a" to "cap_a"), refreshAuthorities)
        assertEquals(
            listOf("/user" to "gho_a_rotated", "/user/installations" to "gho_a_rotated"),
            requests,
        )
        assertEquals("ghr_a_rotated", fakeStore.credential?.refreshToken)
        assertEquals("cap_a_rotated", fakeStore.credential?.refreshCap)
        assertEquals("gho_a_rotated", GitHubConnectionCoordinator.accessTokenCache?.accessToken)
    }

    @Test
    fun standaloneStatusDoesNotStartASecondAuthenticatedRequestAfterDisconnectReturns() =
        runBlocking(Dispatchers.IO) {
            fakeStore.credential = StoredGitHubCredential("ghr_a", "cap_a", Long.MAX_VALUE)
            GitHubConnectionCoordinator.accessTokenCache =
                GitHubConnectionCoordinator.AccessTokenCache("gho_a", 60_000L)
            val requests = mutableListOf<Pair<String, String>>()
            val firstRequestCompleted = CompletableDeferred<Unit>()
            val resumeStatus = CompletableDeferred<Unit>()
            GitHubConnectionCoordinator.statusAuthenticatedUserOverride = { accessToken ->
                requests += "/user" to accessToken
                GitHubResult.Success(GitHubAuthenticatedUser(id = 1L, login = "alice"))
            }
            GitHubConnectionCoordinator.statusBetweenRequestsOverride = {
                firstRequestCompleted.complete(Unit)
                resumeStatus.await()
            }
            GitHubConnectionCoordinator.statusInstallationsOverride = { accessToken ->
                requests += "/user/installations" to accessToken
                GitHubResult.Success(
                    listOf(GitHubInstallation(1L, 1L, "alice", "User")),
                )
            }

            val status = async { GitHubConnectionCoordinator.status(fakeContext) }
            firstRequestCompleted.await()
            GitHubConnectionCoordinator.disconnect(fakeContext)
            resumeStatus.complete(Unit)

            assertEquals(GitHubStatus.NotConnected, status.await())
            assertEquals(GitHubStatus.NotConnected, GitHubConnectionCoordinator.statusFlow.value)
            assertEquals(listOf("/user" to "gho_a"), requests)
        }

    @Test
    fun standaloneStatusDoesNotStartASecondRequestAfterAuthorityReplacement() =
        runBlocking(Dispatchers.IO) {
            fakeStore.credential = StoredGitHubCredential("ghr_a", "cap_a", Long.MAX_VALUE)
            GitHubConnectionCoordinator.accessTokenCache =
                GitHubConnectionCoordinator.AccessTokenCache("gho_a", 60_000L)
            val requests = mutableListOf<Pair<String, String>>()
            val firstRequestCompleted = CompletableDeferred<Unit>()
            val resumeStatus = CompletableDeferred<Unit>()
            val newerCredential = StoredGitHubCredential("ghr_b", "cap_b", Long.MAX_VALUE)
            val newerCache = GitHubConnectionCoordinator.AccessTokenCache("gho_b", 60_000L)
            val newerStatus = GitHubStatus.Connected("bob", "https://github.com/settings/installations/2")
            GitHubConnectionCoordinator.statusAuthenticatedUserOverride = { accessToken ->
                requests += "/user" to accessToken
                GitHubResult.Success(GitHubAuthenticatedUser(id = 1L, login = "alice"))
            }
            GitHubConnectionCoordinator.statusBetweenRequestsOverride = {
                firstRequestCompleted.complete(Unit)
                resumeStatus.await()
            }
            GitHubConnectionCoordinator.statusInstallationsOverride = { accessToken ->
                requests += "/user/installations" to accessToken
                GitHubResult.Success(
                    listOf(GitHubInstallation(1L, 1L, "alice", "User")),
                )
            }

            val status = async { GitHubConnectionCoordinator.status(fakeContext) }
            firstRequestCompleted.await()
            replaceCredentialStateForTest(newerCredential, newerCache, newerStatus)
            resumeStatus.complete(Unit)

            assertEquals(newerStatus, status.await())
            assertEquals(newerStatus, GitHubConnectionCoordinator.statusFlow.value)
            assertEquals(newerCredential, fakeStore.credential)
            assertEquals(newerCache, GitHubConnectionCoordinator.accessTokenCache)
            assertEquals(listOf("/user" to "gho_a"), requests)
        }

    @Test
    fun storedCredentialConnectStopsBeforeUsingAnyReplacedSnapshotCredential() = runBlocking(Dispatchers.IO) {
        val credentialA = StoredGitHubCredential("ghr_a", "cap_a", Long.MAX_VALUE)
        val cacheA = GitHubConnectionCoordinator.AccessTokenCache("gho_a", 60_000L)
        fakeStore.credential = credentialA
        GitHubConnectionCoordinator.accessTokenCache = cacheA

        val snapshotCaptured = CompletableDeferred<Unit>()
        val releaseSnapshot = CompletableDeferred<Unit>()
        val usedTokens = mutableListOf<String>()
        val staleResult = GitHubStatus.Connected("alice", "https://github.com/settings/installations/1")
        val newerStatus = GitHubStatus.Connected("bob", "https://github.com/settings/installations/2")
        GitHubConnectionCoordinator.statusCredentialSnapshotOverride = {
            snapshotCaptured.complete(Unit)
            releaseSnapshot.await()
        }
        GitHubConnectionCoordinator.statusQueryOverride = { accessToken ->
            usedTokens += accessToken
            GitHubResult.Success(staleResult)
        }
        val browser = UnavailableBrowserLauncher()

        val connect = async { GitHubConnectionCoordinator.connect(fakeContext, browser) }
        snapshotCaptured.await()
        replaceCredentialStateForTest(
            credential = StoredGitHubCredential("ghr_b", "cap_b", Long.MAX_VALUE),
            cache = GitHubConnectionCoordinator.AccessTokenCache("gho_b", 60_000L),
            status = newerStatus,
        )
        releaseSnapshot.complete(Unit)

        assertEquals(GitHubResult.Success(newerStatus), connect.await())
        assertEquals(newerStatus, GitHubConnectionCoordinator.statusFlow.value)
        assertTrue("a stale connect query must use neither its old token nor the replacement token", usedTokens.isEmpty())
        assertEquals(0, browser.resolveCallCount.get())
    }

    @Test
    fun statusRefreshCannotCommitAfterItsCredentialSnapshotIsReplaced() = runBlocking(Dispatchers.IO) {
        val credentialA = StoredGitHubCredential("ghr_a", "cap_a", Long.MAX_VALUE)
        fakeStore.credential = credentialA
        GitHubConnectionCoordinator.accessTokenCache = null

        val refreshStarted = CompletableDeferred<Pair<String, String>>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val usedTokens = mutableListOf<String>()
        val newerCredential = StoredGitHubCredential("ghr_b", "cap_b", Long.MAX_VALUE)
        val newerCache = GitHubConnectionCoordinator.AccessTokenCache("gho_b", 60_000L)
        val newerStatus = GitHubStatus.Connected("bob", "https://github.com/settings/installations/2")
        GitHubConnectionCoordinator.relayRefreshOverride = { _, refreshToken, refreshCap ->
            refreshStarted.complete(refreshToken to refreshCap)
            releaseRefresh.await()
            RelayResult.Success(
                GitHubTokens(
                    accessToken = "gho_a_rotated",
                    expiresInSeconds = 3600,
                    refreshToken = "ghr_a_rotated",
                    refreshCap = "cap_a_rotated",
                    refreshTokenExpiresInSeconds = 3600,
                ),
            )
        }
        GitHubConnectionCoordinator.statusQueryOverride = { accessToken ->
            usedTokens += accessToken
            GitHubResult.Success(GitHubStatus.Connected("alice", "unused"))
        }

        val query = async { GitHubConnectionCoordinator.status(fakeContext) }
        assertEquals("ghr_a" to "cap_a", refreshStarted.await())
        replaceCredentialStateForTest(newerCredential, newerCache, newerStatus)
        releaseRefresh.complete(Unit)

        assertEquals(newerStatus, query.await())
        assertEquals(newerStatus, GitHubConnectionCoordinator.statusFlow.value)
        assertEquals(newerCredential, fakeStore.credential)
        assertEquals(newerCache, GitHubConnectionCoordinator.accessTokenCache)
        assertTrue("a stale refresh must not yield an access token to the status query", usedTokens.isEmpty())
    }

    @Test
    fun refreshCommitRejectsASnapshotFromAnOlderEpochEvenWhenTheTokenMatches() {
        val credential = StoredGitHubCredential("ghr_current", "cap_current", Long.MAX_VALUE)
        val snapshot = GitHubConnectionCoordinator.CredentialStateSnapshot(
            epoch = 12L,
            durableState = DurableGitHubCredentialState.Ready(credential),
            accessTokenCache = null,
        )

        assertTrue(
            GitHubConnectionCoordinator.refreshSnapshotStillCurrent(
                snapshot = snapshot,
                currentEpoch = 12L,
                currentState = DurableGitHubCredentialState.Ready(credential),
            ),
        )
        assertTrue(
            !GitHubConnectionCoordinator.refreshSnapshotStillCurrent(
                snapshot = snapshot,
                currentEpoch = 13L,
                currentState = DurableGitHubCredentialState.Ready(credential),
            ),
        )
    }

    @Test
    fun unmatchedInstallationReturnDoesNotBlockASubsequentManualAuthenticatedCheck() = runBlocking {
        val connected = GitHubStatus.Connected("alice", "https://github.com/settings/installations/1")
        GitHubConnectionCoordinator.standaloneStatusOverride = { GitHubResult.Success(connected) }
        GitHubConnectionCoordinator.beginInstallationReturn()

        // A stale/mismatched browser return is ignored and consumes neither trust nor the
        // pending hint. The user can still explicitly ask the authenticated status path.
        assertNull(GitHubConnectionCoordinator.handleInstallationReturn(fakeContext, "wrong-state"))
        assertEquals(connected, GitHubConnectionCoordinator.status(fakeContext))
        assertEquals(connected, GitHubConnectionCoordinator.statusFlow.value)
    }

    @Test
    fun disconnect_clearsTheStoredCredential() = runBlocking {
        fakeStore.credential = StoredGitHubCredential("ghr_sometoken", "somecap", 1L)

        GitHubConnectionCoordinator.disconnect(fakeContext)

        assertNull(fakeStore.credential)
    }

    @Test
    fun disconnect_publishesNotConnected() = runBlocking {
        fakeStore.credential = StoredGitHubCredential("ghr_sometoken", "somecap", 1L)

        GitHubConnectionCoordinator.disconnect(fakeContext)

        assertEquals(GitHubStatus.NotConnected, GitHubConnectionCoordinator.statusFlow.value)
    }

    @Test
    fun failedDisconnectClearKeepsLiveStateAndRestartRecoversTheSameReadyCredential() = runBlocking {
        val credential = StoredGitHubCredential("ghr_a", "cap_a", Long.MAX_VALUE)
        val cache = GitHubConnectionCoordinator.AccessTokenCache("gho_a", 60_000L)
        val connected = GitHubStatus.Connected("alice", "https://github.com/settings/installations/1")
        fakeStore.credential = credential
        GitHubConnectionCoordinator.accessTokenCache = cache
        GitHubConnectionCoordinator.standaloneStatusOverride = { GitHubResult.Success(connected) }
        assertEquals(connected, GitHubConnectionCoordinator.status(fakeContext))
        val epochBefore = credentialEpochForTest()
        fakeStore.failNextMutation = true

        try {
            GitHubConnectionCoordinator.disconnect(fakeContext)
            throw AssertionError("disconnect reported success after its durable clear failed")
        } catch (_: IOException) {
            // Expected injected durable failure.
        }

        assertEquals(DurableGitHubCredentialState.Ready(credential), fakeStore.durableState)
        assertEquals(cache, GitHubConnectionCoordinator.accessTokenCache)
        assertEquals(connected, GitHubConnectionCoordinator.statusFlow.value)
        assertEquals(epochBefore, credentialEpochForTest())

        val restartedStore = restartCredentialProcess()
        val restarted = GitHubConnectionCoordinator.snapshotCredentialState(fakeContext)
        assertEquals(DurableGitHubCredentialState.Ready(credential), restartedStore.durableState)
        assertEquals(credential, restarted.credential)
        assertNull(restarted.accessTokenCache)
        assertEquals(GitHubStatus.NotConnected, GitHubConnectionCoordinator.statusFlow.value)
    }

    @Test
    fun normalRestartLoadsReadyCredentialAndUsesItOnce() = runBlocking {
        val credential = StoredGitHubCredential("ghr_a", "cap_a", Long.MAX_VALUE)
        val durable = FakeDurableCredentialState(DurableGitHubCredentialState.Ready(credential))
        val restartedStore = restartCredentialProcess(durable)
        val relayAuthorities = mutableListOf<Pair<String, String>>()
        val tokens = rotatedTokens()
        GitHubConnectionCoordinator.relayRefreshOverride = { _, refreshToken, refreshCap ->
            relayAuthorities += refreshToken to refreshCap
            RelayResult.Success(tokens)
        }

        val result = GitHubConnectionCoordinator.withAccessToken(fakeContext) { accessToken ->
            GitHubResult.Success(accessToken)
        }

        assertEquals(GitHubResult.Success(tokens.accessToken), result)
        assertEquals(listOf("ghr_a" to "cap_a"), relayAuthorities)
        assertTrue(restartedStore.durableState is DurableGitHubCredentialState.Ready)
        assertEquals(tokens.accessToken, GitHubConnectionCoordinator.accessTokenCache?.accessToken)
    }

    @Test
    fun disconnectPublishesNotConnectedOnlyWithTheCredentialClearTransaction() = runBlocking(Dispatchers.IO) {
        fakeStore.credential = StoredGitHubCredential("ghr_current", "cap_current", Long.MAX_VALUE)
        GitHubConnectionCoordinator.accessTokenCache =
            GitHubConnectionCoordinator.AccessTokenCache("gho_current", expiresAtMonotonic = 60_000L)
        val connected = GitHubStatus.Connected("alice", "https://github.com/settings/installations/1")
        GitHubConnectionCoordinator.standaloneStatusOverride = { GitHubResult.Success(connected) }
        assertEquals(connected, GitHubConnectionCoordinator.status(fakeContext))

        val statusStarted = CountDownLatch(1)
        val releaseStatus = CountDownLatch(1)
        GitHubConnectionCoordinator.storedCredentialStatusOverride = {
            statusStarted.countDown()
            releaseStatus.await(5, TimeUnit.SECONDS)
            GitHubResult.Success(connected)
        }
        val connect = async { GitHubConnectionCoordinator.connect(fakeContext, UnavailableBrowserLauncher()) }
        assertTrue("connect never reached its held stored-credential status", statusStarted.await(5, TimeUnit.SECONDS))

        val clearStarted = CountDownLatch(1)
        val releaseClear = CountDownLatch(1)
        fakeStore.clearStartedLatch = clearStarted
        fakeStore.releaseClearLatch = releaseClear
        val disconnect = async { GitHubConnectionCoordinator.disconnect(fakeContext) }
        assertTrue("disconnect never reached durable credential clear", clearStarted.await(5, TimeUnit.SECONDS))

        // The clear is still in progress under credentialMutationMutex. A connect finalizer
        // must not have independently repainted state before this one atomic transaction.
        assertEquals(connected, GitHubConnectionCoordinator.statusFlow.value)

        releaseClear.countDown()
        disconnect.await()
        releaseStatus.countDown()

        assertEquals(GitHubResult.Success(GitHubStatus.NotConnected), connect.await())
        assertEquals(GitHubStatus.NotConnected, GitHubConnectionCoordinator.statusFlow.value)
        assertNull(fakeStore.credential)
        assertNull(GitHubConnectionCoordinator.accessTokenCache)
    }

    @Test
    fun staleStandaloneStatusCannotOverwriteDisconnectState() = runBlocking(Dispatchers.IO) {
        val queryStarted = CountDownLatch(1)
        val releaseQuery = CountDownLatch(1)
        val staleConnected = GitHubStatus.Connected("alice", "https://github.com/settings/installations/1")
        GitHubConnectionCoordinator.standaloneStatusOverride = {
            queryStarted.countDown()
            releaseQuery.await(5, TimeUnit.SECONDS)
            GitHubResult.Success(staleConnected)
        }

        val statusQuery = async { GitHubConnectionCoordinator.status(fakeContext) }
        assertTrue("status query never reached its held result", queryStarted.await(5, TimeUnit.SECONDS))

        GitHubConnectionCoordinator.disconnect(fakeContext)
        releaseQuery.countDown()

        assertEquals(GitHubStatus.NotConnected, statusQuery.await())
        assertEquals(GitHubStatus.NotConnected, GitHubConnectionCoordinator.statusFlow.value)
    }

    @Test
    fun staleStoredCredentialConnectCannotPublishOrReturnConnectedAfterDisconnect() = runBlocking(Dispatchers.IO) {
        fakeStore.credential = StoredGitHubCredential("ghr_current", "cap_current", Long.MAX_VALUE)
        val queryStarted = CountDownLatch(1)
        val releaseQuery = CountDownLatch(1)
        val staleConnected = GitHubStatus.Connected("alice", "https://github.com/settings/installations/1")
        GitHubConnectionCoordinator.storedCredentialStatusOverride = {
            queryStarted.countDown()
            releaseQuery.await(5, TimeUnit.SECONDS)
            GitHubResult.Success(staleConnected)
        }

        val connect = async { GitHubConnectionCoordinator.connect(fakeContext, UnavailableBrowserLauncher()) }
        assertTrue("stored-credential status never reached its held result", queryStarted.await(5, TimeUnit.SECONDS))

        GitHubConnectionCoordinator.disconnect(fakeContext)
        releaseQuery.countDown()

        // disconnect() completes the shared operation normally. The old query may return
        // afterward, but it belongs to the generation that disconnect just superseded.
        assertEquals(GitHubResult.Success(GitHubStatus.NotConnected), connect.await())
        assertEquals(GitHubStatus.NotConnected, GitHubConnectionCoordinator.statusFlow.value)
        assertNull(fakeStore.credential)
    }

    @Test
    fun staleStoredCredentialConnectCannotPublishAfterAnotherCredentialMutation() = runBlocking(Dispatchers.IO) {
        fakeStore.credential = StoredGitHubCredential("ghr_current", "cap_current", Long.MAX_VALUE)
        val queryStarted = CountDownLatch(1)
        val releaseQuery = CountDownLatch(1)
        val staleConnected = GitHubStatus.Connected("alice", "https://github.com/settings/installations/1")
        GitHubConnectionCoordinator.storedCredentialStatusOverride = {
            queryStarted.countDown()
            releaseQuery.await(5, TimeUnit.SECONDS)
            GitHubResult.Success(staleConnected)
        }

        val connect = async { GitHubConnectionCoordinator.connect(fakeContext, UnavailableBrowserLauncher()) }
        assertTrue("stored-credential status never reached its held result", queryStarted.await(5, TimeUnit.SECONDS))

        // This concurrent refresh outcome clears the credential and advances its epoch, but
        // deliberately does not terminate the held connect operation. Before the epoch guard,
        // releasing that operation would make it publish its historical Connected result.
        GitHubConnectionCoordinator.relayRefreshOverride = { _, _, _ ->
            RelayResult.Failure(RelayError.OutcomeUnknown)
        }
        val refreshResult = GitHubConnectionCoordinator.withAccessToken<String>(fakeContext) {
            error("an unsafe refresh outcome must not supply an access token")
        }
        assertEquals(GitHubResult.Failure(GitHubError.AuthorizationRequired), refreshResult)
        assertNull(fakeStore.credential)

        releaseQuery.countDown()

        assertEquals(GitHubResult.Success(GitHubStatus.NotConnected), connect.await())
        assertEquals(GitHubStatus.NotConnected, GitHubConnectionCoordinator.statusFlow.value)
    }

    @Test
    fun repositoryCollisionUsesASeparatelyGatedVerificationRequest() = runBlocking(Dispatchers.IO) {
        val requests = CopyOnWriteArrayList<String>()
        val authority = controlPlaneAuthority()

        val result = GitHubConnection.createRepositoryWithAuthority(
            context = fakeContext,
            authority = authority,
            client = collisionClient(requests),
            login = "alice",
            name = "holder-project",
            description = "Project",
            private = true,
        )

        assertEquals(
            GitHubResult.Success(
                GitHubRepo("alice", "holder-project", "git@github.com:alice/holder-project.git"),
            ),
            result,
        )
        assertEquals(
            listOf(
                "POST /user/repos Bearer gho_a",
                "GET /repos/alice/holder-project Bearer gho_a",
            ),
            requests,
        )
    }

    @Test
    fun disconnectBetweenRepositoryPostAndVerificationPreventsTheGet() = runBlocking(Dispatchers.IO) {
        val requests = CopyOnWriteArrayList<String>()
        val authority = controlPlaneAuthority()
        val verificationReached = CountDownLatch(1)
        val resumeVerification = CountDownLatch(1)
        val operation = async {
            GitHubConnection.createRepositoryWithAuthority(
                context = fakeContext,
                authority = authority,
                client = collisionClient(requests),
                login = "alice",
                name = "holder-project",
                description = "Project",
                private = true,
                beforeVerification = {
                    verificationReached.countDown()
                    resumeVerification.await(5, TimeUnit.SECONDS)
                },
            )
        }
        try {
            assertTrue("repository POST never reached the verification boundary", verificationReached.await(5, TimeUnit.SECONDS))
            GitHubConnectionCoordinator.disconnect(fakeContext)
        } finally {
            resumeVerification.countDown()
        }

        assertEquals(GitHubResult.Failure(GitHubError.AuthorizationRequired), operation.await())
        assertEquals(listOf("POST /user/repos Bearer gho_a"), requests)
    }

    @Test
    fun replacementBetweenRepositoryPostAndVerificationDoesNotFallForward() = runBlocking(Dispatchers.IO) {
        val requests = CopyOnWriteArrayList<String>()
        val authority = controlPlaneAuthority()
        val verificationReached = CountDownLatch(1)
        val resumeVerification = CountDownLatch(1)
        val operation = async {
            GitHubConnection.createRepositoryWithAuthority(
                context = fakeContext,
                authority = authority,
                client = collisionClient(requests),
                login = "alice",
                name = "holder-project",
                description = "Project",
                private = true,
                beforeVerification = {
                    verificationReached.countDown()
                    resumeVerification.await(5, TimeUnit.SECONDS)
                },
            )
        }
        try {
            assertTrue("repository POST never reached the verification boundary", verificationReached.await(5, TimeUnit.SECONDS))
            replaceCredentialStateForTest(
                credential = StoredGitHubCredential("ghr_b", "cap_b", Long.MAX_VALUE),
                cache = GitHubConnectionCoordinator.AccessTokenCache("gho_b", 60_000L),
                status = GitHubStatus.Connected("bob", "https://github.com/settings/installations/2"),
            )
        } finally {
            resumeVerification.countDown()
        }

        assertEquals(GitHubResult.Failure(GitHubError.AuthorizationRequired), operation.await())
        assertEquals(listOf("POST /user/repos Bearer gho_a"), requests)
        assertEquals("gho_b", GitHubConnectionCoordinator.accessTokenCache?.accessToken)
        assertEquals("ghr_b", fakeStore.credential?.refreshToken)
        GitHubConnectionCoordinator.disconnect(fakeContext)
    }

    @Test
    fun deployKeyCollisionUsesASeparatelyGatedVerificationRequest() = runBlocking(Dispatchers.IO) {
        val requests = CopyOnWriteArrayList<String>()
        val authority = controlPlaneAuthority()

        val result = GitHubConnection.addDeployKeyWithAuthority(
            context = fakeContext,
            authority = authority,
            client = collisionClient(requests),
            installation = personalInstallation(),
            owner = "alice",
            repo = "holder-project",
            publicKeyLine = "ssh-ed25519 AAAATEST holder",
        )

        assertEquals(GitHubResult.Success(Unit), result)
        assertEquals(
            listOf(
                "POST /repos/alice/holder-project/keys Bearer gho_a",
                "GET /repos/alice/holder-project/keys Bearer gho_a",
            ),
            requests,
        )
    }

    @Test
    fun disconnectBetweenDeployKeyPostAndVerificationPreventsTheGet() = runBlocking(Dispatchers.IO) {
        val requests = CopyOnWriteArrayList<String>()
        val authority = controlPlaneAuthority()
        val verificationReached = CountDownLatch(1)
        val resumeVerification = CountDownLatch(1)
        val operation = async {
            GitHubConnection.addDeployKeyWithAuthority(
                context = fakeContext,
                authority = authority,
                client = collisionClient(requests),
                installation = personalInstallation(),
                owner = "alice",
                repo = "holder-project",
                publicKeyLine = "ssh-ed25519 AAAATEST holder",
                beforeVerification = {
                    verificationReached.countDown()
                    resumeVerification.await(5, TimeUnit.SECONDS)
                },
            )
        }
        try {
            assertTrue("deploy-key POST never reached the verification boundary", verificationReached.await(5, TimeUnit.SECONDS))
            GitHubConnectionCoordinator.disconnect(fakeContext)
        } finally {
            resumeVerification.countDown()
        }

        assertEquals(GitHubResult.Failure(GitHubError.AuthorizationRequired), operation.await())
        assertEquals(listOf("POST /repos/alice/holder-project/keys Bearer gho_a"), requests)
    }

    private suspend fun controlPlaneAuthority(): GitHubConnectionCoordinator.ControlPlaneRequestAuthority {
        fakeStore.credential = StoredGitHubCredential("ghr_a", "cap_a", Long.MAX_VALUE)
        GitHubConnectionCoordinator.accessTokenCache =
            GitHubConnectionCoordinator.AccessTokenCache("gho_a", expiresAtMonotonic = 60_000L)
        return GitHubConnectionCoordinator.captureControlPlaneRequestAuthority(fakeContext)
    }

    private fun personalInstallation() = GitHubInstallation(
        id = 1L,
        accountId = 42L,
        accountLogin = "alice",
        accountType = "User",
    )

    private fun collisionClient(requests: MutableList<String>): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += "${request.method} ${request.url.encodedPath} ${request.header("Authorization")}"
            val (code, body) = when (request.method to request.url.encodedPath) {
                "POST" to "/user/repos" -> 422 to
                    "{\"message\":\"Repository creation failed.\",\"errors\":[{\"message\":\"name already exists on this account\"}]}"
                "GET" to "/repos/alice/holder-project" -> 200 to
                    "{\"owner\":{\"login\":\"alice\"},\"name\":\"holder-project\",\"ssh_url\":\"git@github.com:alice/holder-project.git\"}"
                "POST" to "/repos/alice/holder-project/keys" -> 422 to
                    "{\"message\":\"Validation Failed\",\"errors\":[{\"message\":\"key is already in use\"}]}"
                "GET" to "/repos/alice/holder-project/keys" -> 200 to
                    "[{\"key\":\"ssh-ed25519 AAAATEST\"}]"
                else -> error("unexpected request: ${request.method} ${request.url.encodedPath}")
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test response")
                .body(body.toResponseBody())
                .build()
        }.build()

    @Test
    fun disconnect_waitsForAnInFlightDirectGitHubCall_ratherThanRacingIt() = runBlocking(Dispatchers.IO) {
        // Seeded directly (bypassing refreshAccessToken's real network call entirely) so
        // withAccessToken's controlPlaneMutex gate is the only thing this test is exercising.
        // expiresAtMonotonic is a large fixed value, not "now + margin" -- SystemClock.
        // elapsedRealtime() is stubbed to always return 0 under this module's plain-JVM
        // unitTests.isReturnDefaultValues setup (no Robolectric), so any positive value here
        // reads back as "still valid" for the whole test.
        GitHubConnectionCoordinator.accessTokenCache =
            GitHubConnectionCoordinator.AccessTokenCache("gho_cached", expiresAtMonotonic = 60_000L)
        fakeStore.credential = StoredGitHubCredential("ghr_sometoken", "somecap", 1L)

        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val call = async {
            GitHubConnectionCoordinator.withAccessToken(fakeContext) { token ->
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                GitHubResult.Success(token)
            }
        }
        assertTrue("the direct GitHub call never started", started.await(5, TimeUnit.SECONDS))

        val disconnectJob = async { GitHubConnectionCoordinator.disconnect(fakeContext) }
        // A real, if small, window for disconnect() to run to completion if it wrongly raced
        // the still-in-flight call instead of waiting on controlPlaneMutex behind it.
        delay(300)
        assertTrue(
            "disconnect() completed while a direct GitHub call was still in flight -- it must " +
                "wait on controlPlaneMutex behind that call, not race it",
            disconnectJob.isActive,
        )
        assertEquals("gho_cached", GitHubConnectionCoordinator.accessTokenCache?.accessToken)

        release.countDown() // let the in-flight call finish; disconnect() can now proceed
        call.await()
        disconnectJob.await()

        assertNull(GitHubConnectionCoordinator.accessTokenCache)
        assertNull(fakeStore.credential)
    }
}
