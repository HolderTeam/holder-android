package team.holder.android.resource.s3

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4, for S3-compatible object storage requests -- a direct Kotlin port
 * of holder-daemon's `storage/S3SigV4.cpp` (byte-for-byte the same algorithm, deliberately
 * not shared code; see RESOURCE_STORAGE_ROADMAP.md's step 3 for why every Android storage
 * provider is independently written, same as [team.holder.android.resource.drive
 * .GoogleDriveStorageProvider] shares nothing with holder-core's Drive-adjacent code either).
 * Verified against the worked example from AWS's own SigV4 reference docs in
 * `S3SigV4Test.kt` -- the same vector `S3SigV4_test.cpp` checks on the desktop/daemon side.
 */
data class S3SigningInput(
    val method: String,
    val canonicalUri: String,
    val canonicalQuery: String,
    val headers: Map<String, String>,
    val payloadSha256: String,
    val region: String,
    val accessKeyId: String,
    val secretAccessKey: String,
    val amzDate: String,
    val date: String,
)

data class S3SigningResult(
    val authorization: String,
    val canonicalRequest: String,
    val stringToSign: String,
    val signedHeaders: String,
)

object S3SigV4 {
    fun sign(input: S3SigningInput): S3SigningResult {
        require(
            input.method.isNotEmpty() && input.canonicalUri.isNotEmpty() && input.region.isNotEmpty() &&
                input.accessKeyId.isNotEmpty() && input.secretAccessKey.isNotEmpty() && input.amzDate.isNotEmpty() &&
                input.date.length == 8 && input.payloadSha256.length == 64,
        ) { "incomplete S3 signing input" }

        val normalizedHeaders = input.headers.entries
            .associate { (name, value) -> name.lowercase() to trimAndCollapse(value) }
            .toSortedMap()
        require(
            normalizedHeaders.containsKey("host") && normalizedHeaders.containsKey("x-amz-date") &&
                normalizedHeaders.containsKey("x-amz-content-sha256"),
        ) { "S3 signing headers are incomplete" }

        val canonicalHeaders = normalizedHeaders.entries.joinToString("") { (name, value) -> "$name:$value\n" }
        val signedHeaders = normalizedHeaders.keys.joinToString(";")
        val canonicalRequest = listOf(
            input.method,
            input.canonicalUri,
            input.canonicalQuery,
            canonicalHeaders,
            signedHeaders,
            input.payloadSha256,
        ).joinToString("\n")
        val scope = "${input.date}/${input.region}/s3/aws4_request"
        val stringToSign = "AWS4-HMAC-SHA256\n${input.amzDate}\n$scope\n${sha256Hex(canonicalRequest)}"

        val dateKey = hmacSha256("AWS4${input.secretAccessKey}".toByteArray(Charsets.UTF_8), input.date)
        val regionKey = hmacSha256(dateKey, input.region)
        val serviceKey = hmacSha256(regionKey, "s3")
        val signingKey = hmacSha256(serviceKey, "aws4_request")
        val signature = hmacSha256(signingKey, stringToSign)

        val authorization = "AWS4-HMAC-SHA256 Credential=${input.accessKeyId}/$scope, " +
            "SignedHeaders=$signedHeaders, Signature=${hex(signature)}"
        return S3SigningResult(authorization, canonicalRequest, stringToSign, signedHeaders)
    }

    fun sha256Hex(value: String): String =
        hex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

    private fun hmacSha256(key: ByteArray, value: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(value.toByteArray(Charsets.UTF_8))
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun trimAndCollapse(value: String): String = value.trim().replace(Regex("\\s+"), " ")
}
