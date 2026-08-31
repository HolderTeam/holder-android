package team.holder.android.resource.s3

import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import team.holder.android.resource.AndroidStorageProvider
import team.holder.android.resource.StorageErrorCode
import team.holder.android.resource.encode

/** Non-secret S3 connection shape (endpoint/region/bucket) plus the credential pair --
 * assembled by [team.holder.android.resource.s3.S3Connection] from
 * [team.holder.android.HolderSettings]' generic connected-provider config and the local
 * secret store; never round-tripped through holder-core's own model. */
data class S3Config(
    val endpoint: String,
    val region: String,
    val bucket: String,
    val accessKeyId: String,
    val secretAccessKey: String,
)

private const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
private val OCTET_STREAM = "application/octet-stream".toMediaType()

/** Internal only -- carries a typed [StorageErrorCode] out to the one place per public method
 * that converts it into [AndroidStorageProvider]'s "code:message" string convention. Never
 * crosses out of this file. Mirrors [team.holder.android.resource.drive
 * .GoogleDriveStorageProvider]'s own private failure type of the same shape. */
private class StorageProviderFailure(val code: StorageErrorCode, message: String) : Exception(message)

/**
 * [AndroidStorageProvider] for any S3-compatible bucket (AWS S3, MinIO, ...), signed with
 * [S3SigV4] -- the same algorithm holder-daemon's `S3CompatibleProvider` signs its own
 * requests with (independently written in Kotlin here, not shared code; see that class's doc
 * comment and RESOURCE_STORAGE_ROADMAP.md's step 3). Path-style addressing only
 * (`https://endpoint/bucket/key`), matching desktop's default; `object_key` (already unique
 * per project+asset -- see `object_key_for` in holder-core) becomes the S3 key directly, with
 * no further namespacing.
 *
 * Registered under the "s3_compatible" provider name -- matching desktop's own naming (see
 * `resources_tool_view.vala`'s "Add S3-compatible Storage" dialog) -- via
 * [team.holder.android.resource.AndroidStorageProviderBridge]. Every method here blocks the
 * calling thread, matching the synchronous C callback contract it's registered against, same
 * as every other [AndroidStorageProvider] implementation.
 */
class AndroidS3Provider(
    private val config: () -> S3Config,
    private val client: OkHttpClient = OkHttpClient(),
) : AndroidStorageProvider {

    override fun put(objectKey: String, stagedFilePath: String, storedSize: Long, storedSha256: String): String? =
        reportFailure {
            // storedSha256 is already the SHA-256 holder-core computed while staging this
            // file -- reused directly as x-amz-content-sha256 rather than re-hashing, same as
            // S3CompatibleProvider::put does with its own stored_sha256 parameter.
            val request = signedRequestBuilder("PUT", objectKey, storedSha256)
                .put(File(stagedFilePath).asRequestBody(OCTET_STREAM))
                .build()
            execute(request).use { checkOk(it, "PUT") }
        }

    override fun get(objectKey: String, destinationFilePath: String): String? = reportFailure {
        val request = signedRequestBuilder("GET", objectKey, EMPTY_SHA256).get().build()
        execute(request).use { response ->
            checkOk(response, "GET")
            val body = response.body
                ?: throw StorageProviderFailure(StorageErrorCode.INTEGRITY, "empty S3 response body")
            File(destinationFilePath).outputStream().use { out -> body.byteStream().copyTo(out) }
        }
    }

    override fun exists(objectKey: String): Boolean {
        val request = signedRequestBuilder("HEAD", objectKey, EMPTY_SHA256).head().build()
        execute(request).use { response ->
            if (response.code == 404) return false
            if (!response.isSuccessful) {
                throw StorageProviderFailure(
                    httpStatusToStorageErrorCode(response.code),
                    "S3 HEAD failed: HTTP ${response.code}",
                )
            }
            return true
        }
    }

    override fun remove(objectKey: String): String? = reportFailure {
        val request = signedRequestBuilder("DELETE", objectKey, EMPTY_SHA256).delete().build()
        execute(request).use { response ->
            if (response.code == 404) return@reportFailure // already gone: not a failure
            checkOk(response, "DELETE")
        }
    }

    // -- internals --

    private fun signedRequestBuilder(method: String, objectKey: String, payloadSha256: String): Request.Builder {
        if (objectKey.isEmpty() || objectKey.startsWith("/")) {
            throw StorageProviderFailure(StorageErrorCode.INVALID_CONFIGURATION, "invalid S3 object key")
        }
        val cfg = config()
        val endpoint = parseEndpoint(cfg.endpoint)
        val target = endpoint.basePath + "/" + uriEncodePath(cfg.bucket) + "/" + uriEncodePath(objectKey)
        val amzDate = amzDateFormat().format(Date())
        val date = amzDate.substring(0, 8)

        val signing = S3SigV4.sign(
            S3SigningInput(
                method = method,
                canonicalUri = target,
                canonicalQuery = "",
                headers = mapOf(
                    "host" to endpoint.hostHeader,
                    "x-amz-content-sha256" to payloadSha256,
                    "x-amz-date" to amzDate,
                ),
                payloadSha256 = payloadSha256,
                region = cfg.region,
                accessKeyId = cfg.accessKeyId,
                secretAccessKey = cfg.secretAccessKey,
                amzDate = amzDate,
                date = date,
            ),
        )

        return Request.Builder()
            .url("${endpoint.scheme}://${endpoint.hostHeader}$target")
            .header("x-amz-content-sha256", payloadSha256)
            .header("x-amz-date", amzDate)
            .header("Authorization", signing.authorization)
    }

    private fun execute(request: Request): Response =
        try {
            client.newCall(request).execute()
        } catch (failure: IOException) {
            throw StorageProviderFailure(StorageErrorCode.UNAVAILABLE, failure.message ?: "network failure")
        }

    private fun checkOk(response: Response, operation: String) {
        if (response.isSuccessful) return
        throw StorageProviderFailure(
            httpStatusToStorageErrorCode(response.code),
            "S3 $operation failed: HTTP ${response.code}",
        )
    }

    private fun reportFailure(block: () -> Unit): String? =
        try {
            block()
            null
        } catch (failure: StorageProviderFailure) {
            failure.code.encode(failure.message ?: "storage failure")
        } catch (failure: Exception) {
            StorageErrorCode.UNAVAILABLE.encode(failure.message ?: failure::class.java.simpleName)
        }
}

private fun httpStatusToStorageErrorCode(httpStatusCode: Int): StorageErrorCode = when (httpStatusCode) {
    401 -> StorageErrorCode.AUTHENTICATION
    403 -> StorageErrorCode.PERMISSION
    404 -> StorageErrorCode.INTEGRITY
    409 -> StorageErrorCode.CONFLICT
    429 -> StorageErrorCode.TRANSIENT
    in 500..599 -> StorageErrorCode.TRANSIENT
    else -> StorageErrorCode.UNAVAILABLE
}

private fun amzDateFormat(): SimpleDateFormat =
    SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

internal class ParsedS3Endpoint(val scheme: String, val hostHeader: String, val basePath: String)

/** Mirrors `parse_endpoint` in holder-daemon's `S3CompatibleProvider.cpp`: requires `https://`
 * except for an explicit local test server (localhost/127.0.0.1/10.0.2.2 -- the last being
 * the Android emulator's alias for the host machine's localhost, useful for testing against a
 * local MinIO). */
internal fun parseEndpoint(value: String): ParsedS3Endpoint {
    val (scheme, remainder, defaultPort) = when {
        value.startsWith("https://") -> Triple("https", value.removePrefix("https://"), 443)
        value.startsWith("http://") -> Triple("http", value.removePrefix("http://"), 80)
        else -> throw StorageProviderFailure(
            StorageErrorCode.INVALID_CONFIGURATION,
            "S3 endpoint must start with https:// (or http:// for a local test server)",
        )
    }
    val slash = remainder.indexOf('/')
    val authority = if (slash == -1) remainder else remainder.substring(0, slash)
    val basePath = (if (slash == -1) "" else remainder.substring(slash)).trimEnd('/')
    val colon = authority.lastIndexOf(':')
    val host = if (colon == -1) authority else authority.substring(0, colon)
    val port = if (colon == -1) {
        defaultPort
    } else {
        authority.substring(colon + 1).toIntOrNull()
            ?: throw StorageProviderFailure(StorageErrorCode.INVALID_CONFIGURATION, "invalid S3 endpoint port")
    }
    if (host.isEmpty()) {
        throw StorageProviderFailure(StorageErrorCode.INVALID_CONFIGURATION, "S3 endpoint host is empty")
    }
    val localhost = host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2"
    if (scheme == "http" && !localhost) {
        throw StorageProviderFailure(
            StorageErrorCode.INVALID_CONFIGURATION,
            "unverified HTTP is only allowed for a local test server (localhost/10.0.2.2)",
        )
    }
    val hostHeader = if (port == defaultPort) host else "$host:$port"
    return ParsedS3Endpoint(scheme, hostHeader, basePath)
}

/** Mirrors `uri_encode_path` in holder-daemon's `S3CompatibleProvider.cpp` exactly (unreserved
 * chars plus `/`, uppercase-hex percent-encoding for everything else) -- used both to build
 * the literal request path and as SigV4's canonical URI, so the two must always agree. */
internal fun uriEncodePath(value: String): String {
    val builder = StringBuilder()
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val ch = byte.toInt() and 0xFF
        val unreserved = ch in 'A'.code..'Z'.code || ch in 'a'.code..'z'.code || ch in '0'.code..'9'.code ||
            ch == '-'.code || ch == '_'.code || ch == '.'.code || ch == '~'.code || ch == '/'.code
        if (unreserved) {
            builder.append(ch.toChar())
        } else {
            builder.append('%').append("%02X".format(ch))
        }
    }
    return builder.toString()
}
