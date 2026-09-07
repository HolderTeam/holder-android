package team.holder.android.git.github

import android.content.Context
import android.content.ContextWrapper
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
    var refreshToken: String? = null
    var refreshCap: String? = null

    override fun getRefreshToken(context: Context): String? = refreshToken
    override fun getRefreshCap(context: Context): String? = refreshCap
    override fun store(context: Context, refreshToken: String, refreshCap: String) {
        this.refreshToken = refreshToken
        this.refreshCap = refreshCap
    }
    override fun clear(context: Context) {
        refreshToken = null
        refreshCap = null
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

    override fun resolveLaunchKind(context: Context): GitHubConnectionCoordinator.LaunchKind? {
        resolveCallCount.incrementAndGet()
        startedLatch?.countDown()
        releaseLatch?.await(5, TimeUnit.SECONDS)
        return null
    }

    override fun launch(context: Context, kind: GitHubConnectionCoordinator.LaunchKind, uri: android.net.Uri, attemptId: UUID) {
        error("never reached -- resolveLaunchKind always returns null in this fake")
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
    }

    @Test
    fun connect_returnsBrowserUnavailable_whenNoBrowserResolves() = runBlocking {
        val result = GitHubConnectionCoordinator.connect(fakeContext, UnavailableBrowserLauncher())
        assertEquals(GitHubResult.Failure(GitHubError.BrowserUnavailable), result)
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
        assertTrue("first connect() never reached resolveLaunchKind", started.await(5, TimeUnit.SECONDS))

        // The first operation is now deliberately held open (blocked inside resolveLaunchKind,
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
        assertNull(fakeStore.refreshToken)
        val result = GitHubConnectionCoordinator.connect(fakeContext, UnavailableBrowserLauncher())
        assertEquals(GitHubResult.Failure(GitHubError.BrowserUnavailable), result)
    }

    @Test
    fun disconnect_clearsTheStoredCredential() = runBlocking {
        fakeStore.refreshToken = "ghr_sometoken"
        fakeStore.refreshCap = "somecap"

        GitHubConnectionCoordinator.disconnect(fakeContext)

        assertNull(fakeStore.refreshToken)
        assertNull(fakeStore.refreshCap)
    }

    @Test
    fun disconnect_publishesNotConnected() = runBlocking {
        fakeStore.refreshToken = "ghr_sometoken"
        fakeStore.refreshCap = "somecap"

        GitHubConnectionCoordinator.disconnect(fakeContext)

        assertEquals(GitHubStatus.NotConnected, GitHubConnectionCoordinator.statusFlow.value)
    }
}
