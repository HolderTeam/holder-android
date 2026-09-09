package team.holder.android.git.github

import kotlinx.coroutines.Job
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.Timeout
import kotlin.reflect.KClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubOAuthTest {
    private class RecordingCall : Call {
        var cancelled = false

        override fun request(): Request = error("not used")
        override fun execute(): Response = error("not used")
        override fun enqueue(responseCallback: Callback) = error("not used")
        override fun cancel() { cancelled = true }
        override fun isExecuted(): Boolean = false
        override fun isCanceled(): Boolean = cancelled
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = this
        override fun addEventListener(eventListener: EventListener) = Unit
        override fun <T : Any> tag(type: KClass<T>): T? = null
        override fun <T> tag(type: Class<out T>): T? = null
        override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
    }

    @Test
    fun cancellingAWorkerJobCancelsItsInFlightRelayCall() {
        val call = RecordingCall()
        val job = Job()
        val hook = GitHubOAuth.cancelCallWhenCoroutineIsCancelled(call, job)

        job.cancel()

        assertTrue(call.cancelled)
        hook?.dispose()
    }

    @Test
    fun relayCredentialRequestBody_isOneShot() {
        // exchangeCode() and refresh() both reach postRelayBlocking(), which creates its body
        // through this shared helper.  A one-shot body prevents OkHttp from retransmitting
        // either an authorization code or a rotating refresh credential on connection recovery
        // or an HTTP follow-up.
        assertTrue(GitHubOAuth.oneShotJsonBody("{\"credential\":\"secret\"}").isOneShot())
    }

    @Test
    fun relayCredentialRequestBody_preservesTheJsonPayload() {
        val payload = "{\"code\":\"one-time-code\"}"
        val sink = Buffer()

        GitHubOAuth.oneShotJsonBody(payload).writeTo(sink)

        assertEquals(payload, sink.readUtf8())
    }

    @Test
    fun credentialRelayClient_outlivesTheWorkersUpstreamDeadline() {
        val client = GitHubConnectionCoordinator.newCredentialRelayHttpClient()

        // The Worker has a 15-second complete upstream deadline.  Both bounds matter: a read
        // limit must not cut off a slow but live response, and the overall call must still
        // leave time for Android↔relay transport around that upstream operation.
        assertEquals(20_000, client.readTimeoutMillis)
        assertEquals(20_000, client.callTimeoutMillis)
    }

    @Test
    fun relayErrorEnvelope_requiresItsPinnedHttpStatusBeforeItCanDriveAnAuthorizationAction() {
        val body = "{\"error\":\"authorization_required\"}"

        assertEquals(RelayError.AuthorizationRequired(null), GitHubOAuth.mapRelayErrorBody(400, body))
        assertEquals(RelayError.Unexpected(503, body), GitHubOAuth.mapRelayErrorBody(503, body))
    }

    @Test
    fun relayRateLimitAndAmbiguityEnvelopes_requireTheirOwnPinnedStatuses() {
        val rateBody = "{\"error\":\"rate_limited\",\"retry_after_seconds\":30}"
        val unknownBody = "{\"error\":\"outcome_unknown\"}"

        assertEquals(RelayError.RateLimited(30), GitHubOAuth.mapRelayErrorBody(429, rateBody))
        assertEquals(RelayError.Unexpected(503, rateBody), GitHubOAuth.mapRelayErrorBody(503, rateBody))
        assertEquals(RelayError.OutcomeUnknown, GitHubOAuth.mapRelayErrorBody(503, unknownBody))
        assertEquals(RelayError.Unexpected(400, unknownBody), GitHubOAuth.mapRelayErrorBody(400, unknownBody))
    }
}
