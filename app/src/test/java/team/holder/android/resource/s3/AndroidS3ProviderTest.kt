package team.holder.android.resource.s3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Endpoint parsing and path encoding are meant to mirror holder-daemon's
 * `S3CompatibleProvider.cpp` exactly (`parse_endpoint`/`uri_encode_path`) -- these are the
 * same cases that file's behavior implies, ported here since the two aren't shared code. */
class AndroidS3ProviderTest {
    @Test
    fun parseEndpoint_splitsHostPortAndBasePath() {
        val endpoint = parseEndpoint("https://s3.example.com:9000/base")
        assertEquals("https", endpoint.scheme)
        assertEquals("s3.example.com:9000", endpoint.hostHeader)
        assertEquals("/base", endpoint.basePath)
    }

    @Test
    fun parseEndpoint_omitsDefaultPortFromTheHostHeader() {
        val endpoint = parseEndpoint("https://s3.amazonaws.com")
        assertEquals("s3.amazonaws.com", endpoint.hostHeader)
        assertEquals("", endpoint.basePath)
    }

    @Test
    fun parseEndpoint_stripsTrailingSlashesFromTheBasePath() {
        assertEquals("/base", parseEndpoint("https://host/base///").basePath)
    }

    @Test
    fun parseEndpoint_allowsInsecureHttpOnlyForALocalTestServer() {
        assertEquals("http", parseEndpoint("http://localhost:9000").scheme)
        assertEquals("http", parseEndpoint("http://10.0.2.2:9000").scheme)
        assertThrows(Exception::class.java) { parseEndpoint("http://s3.example.com") }
    }

    @Test
    fun parseEndpoint_rejectsAMissingScheme() {
        assertThrows(Exception::class.java) { parseEndpoint("s3.example.com") }
    }

    @Test
    fun uriEncodePath_leavesUnreservedCharactersAndSlashesAlone() {
        assertEquals("bucket/a-file_name.txt~1", uriEncodePath("bucket/a-file_name.txt~1"))
    }

    @Test
    fun uriEncodePath_percentEncodesEverythingElseAsUppercaseHex() {
        assertEquals("a%20file%2Bname", uriEncodePath("a file+name"))
    }
}
