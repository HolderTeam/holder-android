package team.holder.android.git.github

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubOAuthTest {
    @Test
    fun cancellationReachesAnExecutingExchangeBeforeTheWorkerCanComplete() = assertActiveCallCancellation()

    @Test
    fun cancellationReachesAnExecutingRefreshBeforeTheWorkerCanComplete() = assertActiveCallCancellation(refresh = true)

    @Test
    fun cancellationRemainsConnectedDuringResponseBodyRead() = assertActiveCallCancellation(holdResponseBody = true)

    private fun assertActiveCallCancellation(refresh: Boolean = false, holdResponseBody: Boolean = false) = runBlocking {
        val transport = BlockedRelayCall(holdResponseBody)
        val worker = launch(Dispatchers.IO) {
            try {
                if (refresh) GitHubOAuth.refresh(transport.client, "refresh", "cap")
                else GitHubOAuth.exchangeCode(transport.client, "code", "verifier")
            } catch (_: java.io.IOException) {
                // The held response source throws when released, including during cleanup
                // of a failed cancellation assertion. Do not mask that assertion with I/O.
            }
        }
        try {
            assertTrue("worker never entered synchronous HTTP I/O", transport.entered.await(5, TimeUnit.SECONDS))
            assertTrue(transport.call!!.isExecuted())
            worker.cancel()

            assertFalse("worker must still be executing the held call", worker.isCompleted)
            assertTrue("cancellation must reach the real Call before execute/body read returns", transport.call!!.isCanceled())
            assertEquals(1, transport.cancellations.get())
            assertEquals(1, transport.requests.get())
        } finally {
            transport.release.countDown()
            worker.cancel()
            worker.join()
        }
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
