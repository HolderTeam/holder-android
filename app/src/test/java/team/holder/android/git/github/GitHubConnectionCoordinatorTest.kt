package team.holder.android.git.github

import android.content.Context
import android.content.ContextWrapper
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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

private class FakeCredentialStore : GitHubCredentialStore {
    var credential: StoredGitHubCredential? = null

    override fun get(context: Context): StoredGitHubCredential? = credential
    override fun store(context: Context, credential: StoredGitHubCredential) {
        this.credential = credential
    }
    override fun clear(context: Context) {
        credential = null
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

    override fun resolveBrowser(context: Context): GitHubConnectionCoordinator.BrowserLaunch? {
        resolveCallCount.incrementAndGet()
        startedLatch?.countDown()
        releaseLatch?.await(5, TimeUnit.SECONDS)
        return null
    }

    override fun launch(context: Context, browser: GitHubConnectionCoordinator.BrowserLaunch, uri: android.net.Uri, attemptId: UUID) {
        error("never reached -- resolveBrowser always returns null in this fake")
    }
}

class GitHubConnectionCoordinatorTest {
    private val fakeStore = FakeCredentialStore()
    private val fakeContext = FakeContext()

    init {
        GitHubConnectionCoordinator.credentialStore = fakeStore
    }

    @After
    fun resetCoordinator() {
        GitHubConnectionCoordinator.credentialStore = RealGitHubCredentialStore
        GitHubConnectionCoordinator.storedCredentialStatusOverride = null
        GitHubConnectionCoordinator.relayRefreshOverride = null
        GitHubConnectionCoordinator.standaloneStatusOverride = null
        GitHubConnectionCoordinator.accessTokenCache = null
    }

    @Test
    fun connect_returnsBrowserUnavailable_whenNoBrowserResolves() = runBlocking {
        val result = GitHubConnectionCoordinator.connect(fakeContext, UnavailableBrowserLauncher())
        assertEquals(GitHubResult.Failure(GitHubError.BrowserUnavailable), result)
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
        val second = async { GitHubConnectionCoordinator.connect(fakeContext, launcher) }
        // Give the dispatcher a real, if small, window to actually run second's own (fast,
        // non-blocking) join-or-create decision before releasing first -- otherwise first can
        // wake from its latch and clear connectInFlight before second ever checks it, which
        // would make this test racy against test-body scheduling, not against the coordinator.
        delay(200)

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
    fun unsafeRefreshOutcomesClearTheCredentialBeforeTheNextAccessCanRetryIt() = runBlocking {
        val unsafeErrors = listOf(
            RelayError.OutcomeUnknown,
            RelayError.AmbiguousTransportFailure,
            RelayError.Unexpected(503, "malformed relay response"),
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
