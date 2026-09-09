package team.holder.android.git.github

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer

/** Uses a real OkHttp Call.execute(), held either in an interceptor or in response-body I/O.
 * Cancellation is deliberately separate from returning: admission must wait for shutdown,
 * even after the real Call has received cancel(). Latch timeouts only guard test deadlocks. */
internal class BlockedRelayCall(private val holdResponseBody: Boolean = false) {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val exited = CountDownLatch(1)
    val cancellations = AtomicInteger()
    val requests = AtomicInteger()
    @Volatile var call: Call? = null

    val client: OkHttpClient = GitHubConnectionCoordinator.newCredentialRelayHttpClient()
        .newBuilder()
        .eventListener(object : EventListener() {
            override fun canceled(call: Call) { cancellations.incrementAndGet() }
        })
        .addInterceptor { chain ->
            call = chain.call()
            requests.incrementAndGet()
            if (!holdResponseBody) blockUntilReleased()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(object : ResponseBody() {
                    private val source = object : Source {
                        override fun read(sink: Buffer, byteCount: Long): Long = blockUntilReleased()
                        override fun timeout() = Timeout.NONE
                        override fun close() = Unit
                    }.buffer()
                    override fun contentType() = null
                    override fun contentLength() = -1L
                    override fun source() = source
                })
                .build()
        }
        .build()

    private fun blockUntilReleased(): Nothing {
        entered.countDown()
        try {
            check(release.await(10, TimeUnit.SECONDS)) { "test did not release synchronous HTTP execution" }
            throw IOException("controlled transport shutdown")
        } finally {
            exited.countDown()
        }
    }
}
