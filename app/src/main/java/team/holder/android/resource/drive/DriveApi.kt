package team.holder.android.resource.drive

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private const val DRIVE_FILES_URL = "https://www.googleapis.com/drive/v3/files"
private const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"

/** A Drive API call failed; [httpStatusCode] lets a caller map it onto
 * [team.holder.android.resource.StorageErrorCode] the way [GoogleDriveStorageProvider] does,
 * without every caller re-deriving that mapping itself. */
class DriveApiException(val httpStatusCode: Int, driveResponseBody: String) :
    Exception("Drive API request failed: HTTP $httpStatusCode: $driveResponseBody")

/**
 * Small shared helpers over the Drive v3 REST API for locating/creating an object by exact
 * name within a known parent -- the one lookup shape this whole integration needs, whether
 * for asset-storage object_keys ([GoogleDriveStorageProvider]) or the well-known
 * "Holder/Resources" folder path ([GoogleDriveConnection]). Not a general Drive client.
 */
internal object DriveApi {
    /** Finds an object by exact name (optionally also matching [mimeType], for narrowing to
     * folders) within [parentId] (or anywhere Drive-wide if null). Returns null if nothing
     * matches -- not finding something is not a failure. */
    fun findId(
        client: OkHttpClient,
        accessToken: String,
        name: String,
        parentId: String?,
        mimeType: String? = null,
    ): String? {
        val clauses = mutableListOf("name = '${escape(name)}'", "trashed = false")
        if (parentId != null) clauses += "'${escape(parentId)}' in parents"
        if (mimeType != null) clauses += "mimeType = '${escape(mimeType)}'"
        val url = DRIVE_FILES_URL.toHttpUrl().newBuilder()
            .addQueryParameter("q", clauses.joinToString(" and "))
            .addQueryParameter("fields", "files(id)")
            .addQueryParameter("spaces", "drive")
            .build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $accessToken").build()
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw DriveApiException(response.code, responseBody)
            val files = JSONObject(responseBody).optJSONArray("files") ?: JSONArray()
            return if (files.length() > 0) files.getJSONObject(0).getString("id") else null
        }
    }

    fun createFolder(client: OkHttpClient, accessToken: String, name: String, parentId: String?): String {
        val metadata = JSONObject().apply {
            put("name", name)
            put("mimeType", FOLDER_MIME_TYPE)
            if (parentId != null) put("parents", JSONArray().put(parentId))
        }
        val request = Request.Builder()
            .url(DRIVE_FILES_URL)
            .post(metadata.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .header("Authorization", "Bearer $accessToken")
            .build()
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw DriveApiException(response.code, responseBody)
            return JSONObject(responseBody).getString("id")
        }
    }

    fun findOrCreateFolder(client: OkHttpClient, accessToken: String, name: String, parentId: String?): String =
        findId(client, accessToken, name, parentId, FOLDER_MIME_TYPE)
            ?: createFolder(client, accessToken, name, parentId)

    fun escape(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")
}
