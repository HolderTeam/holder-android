package team.holder.android.resource.s3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Same worked example holder-daemon's own `S3SigV4_test.cpp` checks against its C++
 * implementation -- from AWS's SigV4 reference docs (the canonical "GET Object" example).
 * Matching signatures here is the strongest evidence this port is byte-for-byte correct.
 */
class S3SigV4Test {
    @Test
    fun sign_matchesTheAwsGetObjectExample() {
        val result = S3SigV4.sign(
            S3SigningInput(
                method = "GET",
                canonicalUri = "/test.txt",
                canonicalQuery = "",
                headers = mapOf(
                    "host" to "examplebucket.s3.amazonaws.com",
                    "range" to "bytes=0-9",
                    "x-amz-content-sha256" to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                    "x-amz-date" to "20130524T000000Z",
                ),
                payloadSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                region = "us-east-1",
                accessKeyId = "AKIAIOSFODNN7EXAMPLE",
                secretAccessKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                amzDate = "20130524T000000Z",
                date = "20130524",
            ),
        )

        assertEquals("host;range;x-amz-content-sha256;x-amz-date", result.signedHeaders)
        assertTrue(result.canonicalRequest.startsWith("GET\n/test.txt\n\n"))
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, " +
                "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, " +
                "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
            result.authorization,
        )
    }

    @Test
    fun sign_normalizesHeaderNamesAndWhitespace() {
        val result = S3SigV4.sign(
            S3SigningInput(
                method = "HEAD",
                canonicalUri = "/bucket/object",
                canonicalQuery = "",
                headers = mapOf(
                    "Host" to " example.test  ",
                    "X-Amz-Content-Sha256" to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                    "X-Amz-Date" to "20260824T120000Z",
                ),
                payloadSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                region = "eu-west-2",
                accessKeyId = "KEY",
                secretAccessKey = "SECRET",
                amzDate = "20260824T120000Z",
                date = "20260824",
            ),
        )

        assertTrue(result.canonicalRequest.contains("host:example.test\n"))
        assertTrue(result.authorization.contains("Signature="))
    }

    @Test(expected = IllegalArgumentException::class)
    fun sign_rejectsIncompleteInput() {
        S3SigV4.sign(
            S3SigningInput(
                method = "",
                canonicalUri = "/x",
                canonicalQuery = "",
                headers = emptyMap(),
                payloadSha256 = "",
                region = "us-east-1",
                accessKeyId = "KEY",
                secretAccessKey = "SECRET",
                amzDate = "20260824T120000Z",
                date = "20260824",
            ),
        )
    }
}
