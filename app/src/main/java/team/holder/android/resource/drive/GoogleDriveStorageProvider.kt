package team.holder.android.resource.drive

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import team.holder.android.resource.AndroidStorageProvider
import team.holder.android.resource.StorageErrorCode
import team.holder.android.resource.encode

private const val DRIVE_FILES_URL = "https://www.googleapis.com/drive/v3/files"
private const val DRIVE_UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
private val OCTET_STREAM = "application/octet-stream".toMediaType()

/** Internal only -- carries a typed [StorageErrorCode] out to the one place per public method
 * that converts it into [AndroidStorageProvider]'s "code:message" string convention. Never
 * crosses out of this file. */
private class StorageProviderFailure(val code: StorageErrorCode, message: String) : Exception(message)

/** Shared by every HTTP failure path in this file, whether from [GoogleDriveStorageProvider]'s
 * own put/get/delete calls or from a [DriveApiException] surfaced by [DriveApi]'s search. */
private fun httpStatusToStorageErrorCode(httpStatusCode: Int, quotaHint: Boolean): StorageErrorCode = when (httpStatusCode) {
    401 -> StorageErrorCode.AUTHENTICATION
    403 -> if (quotaHint) StorageErrorCode.CAPACITY else StorageErrorCode.PERMISSION
    404 -> StorageErrorCode.INTEGRITY
    in 500..599 -> StorageErrorCode.TRANSIENT
    else -> StorageErrorCode.UNAVAILABLE
}

/**
 * [AndroidStorageProvider] backed by a single, well-known Google Drive folder. `object_key`
 * (already unique per project+asset -- see `object_key_for` in holder-core) becomes the
 * file's `name` within that folder; every lookup is by an exact-name search scoped to the
 * folder's `parents`, never by Drive's own file id, so the portable Location record (see
 * [team.holder.android.HolderLocation]) never needs to carry a Drive-specific identifier --
 * see GOOGLE_DRIVE.md's "object_key and the Drive folder" section for why. Drive allows
 * duplicate names within a folder, but nothing here relies on that: dedup-by-content-hash
 * already happens upstream in holder-core before an object_key is ever handed to a provider.
 *
 * Registered under the "google-drive" provider name via
 * [team.holder.android.resource.AndroidStorageProviderBridge]. Every method here blocks the
 * calling thread -- matching the synchronous C callback contract it's registered against --
 * and is only ever reached from a background thread already, the same as every other
 * HolderNative call in this app.
 */
class GoogleDriveStorageProvider(
    private val context: Context,
    private val folderId: () -> String,
    private val client: OkHttpClient = OkHttpClient(),
) : AndroidStorageProvider {

    override fun put(objectKey: String, stagedFilePath: String, storedSize: Long, storedSha256: String): String? =
        reportFailure {
            val token = accessTokenOrThrow()
            val existingFileId = findFileId(token, objectKey)
            val body = File(stagedFilePath).asRequestBody(OCTET_STREAM)
            if (existingFileId != null) {
                // A retry after a previous attempt's git commit failed partway -- overwrite
                // rather than create a second file with the same name.
                val request = Request.Builder()
                    .url("$DRIVE_UPLOAD_URL/$existingFileId?uploadType=media")
                    .patch(body)
                    .header("Authorization", "Bearer $token")
                    .build()
                execute(request).use { checkOk(it, "upload") }
            } else {
                val metadata = JSONObject().apply {
                    put("name", objectKey)
                    put("parents", JSONArray().put(folderId()))
                }
                val multipart = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addPart(
                        MultipartBody.Part.create(
                            null,
                            metadata.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()),
                        ),
                    )
                    .addPart(MultipartBody.Part.create(null, body))
                    .build()
                val request = Request.Builder()
                    .url("$DRIVE_UPLOAD_URL?uploadType=multipart")
                    .post(multipart)
                    .header("Authorization", "Bearer $token")
                    .build()
                execute(request).use { checkOk(it, "upload") }
            }
        }

    override fun get(objectKey: String, destinationFilePath: String): String? = reportFailure {
        val token = accessTokenOrThrow()
        val fileId = findFileId(token, objectKey)
            ?: throw StorageProviderFailure(StorageErrorCode.INTEGRITY, "no Drive file named $objectKey")
        val request = Request.Builder()
            .url("$DRIVE_FILES_URL/$fileId?alt=media")
            .header("Authorization", "Bearer $token")
            .build()
        execute(request).use { response ->
            checkOk(response, "download")
            val body = response.body ?: throw StorageProviderFailure(StorageErrorCode.INTEGRITY, "empty download body")
            File(destinationFilePath).outputStream().use { out -> body.byteStream().copyTo(out) }
        }
    }

    override fun exists(objectKey: String): Boolean {
        val token = accessTokenOrThrow()
        return findFileId(token, objectKey) != null
    }

    override fun remove(objectKey: String): String? = reportFailure {
        val token = accessTokenOrThrow()
        val fileId = findFileId(token, objectKey) ?: return@reportFailure // already gone: not a failure
        val request = Request.Builder()
            .url("$DRIVE_FILES_URL/$fileId")
            .delete()
            .header("Authorization", "Bearer $token")
            .build()
        execute(request).use { checkOk(it, "delete") }
    }

    // -- internals --

    private fun accessTokenOrThrow(): String {
        // GoogleDriveConnection.folderId, not connectedAccountEmail, is the real "connected"
        // signal -- see its doc comment and the same reasoning in SettingsScreen. Email is
        // passed to authorize() as a hint when available (helps Play Services target the
        // right account silently on a multi-account device) but its absence must never block
        // an otherwise-working connection.
        runBlocking { GoogleDriveConnection.folderId(context).first() }
            ?: throw StorageProviderFailure(StorageErrorCode.AUTHENTICATION, "Google Drive is not connected")
        val email = runBlocking { GoogleDriveConnection.connectedAccountEmail(context).first() }
        val authorization = runBlocking {
            GoogleDriveAuth.authorize(context, email) {
                // Uploads/downloads run on a background thread with no Activity available to
                // show a consent screen -- reconnecting after revocation is a deliberate,
                // explicit action back in Settings, not something to prompt for mid-attach.
                throw StorageProviderFailure(
                    StorageErrorCode.AUTHENTICATION,
                    "Google Drive needs to be reconnected in Settings",
                )
            }
        }
        return authorization.accessToken
    }

    private fun findFileId(token: String, objectKey: String): String? =
        try {
            DriveApi.findId(client, token, objectKey, folderId())
        } catch (failure: DriveApiException) {
            val quotaHint = failure.message?.contains("storageQuotaExceeded", ignoreCase = true) == true
            throw StorageProviderFailure(
                httpStatusToStorageErrorCode(failure.httpStatusCode, quotaHint),
                failure.message ?: "search failed",
            )
        }

    private fun execute(request: Request): Response =
        try {
            client.newCall(request).execute()
        } catch (failure: IOException) {
            throw StorageProviderFailure(StorageErrorCode.UNAVAILABLE, failure.message ?: "network failure")
        }

    private fun checkOk(response: Response, operation: String) {
        if (response.isSuccessful) return
        val quotaHint = runCatching { response.peekBody(4096).string() }
            .getOrDefault("")
            .contains("storageQuotaExceeded", ignoreCase = true)
        val code = httpStatusToStorageErrorCode(response.code, quotaHint)
        throw StorageProviderFailure(code, "Drive $operation failed: HTTP ${response.code}")
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
