package team.holder.android.resource.drive

import android.content.Context
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import team.holder.android.HolderLocation
import team.holder.android.HolderSettings
import team.holder.android.resource.AndroidStorageProviderBridge
import team.holder.android.resource.StorageLocations

private const val HOLDER_FOLDER_NAME = "Holder"
private const val RESOURCES_FOLDER_NAME = "Resources"
private const val ACCOUNT_EMAIL_KEY = "account_email"
private const val FOLDER_ID_KEY = "folder_id"

/**
 * Ties [GoogleDriveAuth], the well-known "Holder/Resources" Drive folder, and
 * [AndroidStorageProviderBridge] registration together into the two operations Settings
 * actually needs -- see GOOGLE_DRIVE.md's "Authentication UX" section: basic connect/
 * disconnect/status is the whole scope here, nothing more elaborate.
 *
 * One Drive folder is shared by every project on this device, not one per project -- see
 * [GoogleDriveStorageProvider]'s doc comment for why that's today's deliberate
 * simplification, tracking a real limitation in holder-core's StorageProvider interface
 * itself (one provider instance's configuration, not one per Location) rather than
 * something specific to Drive.
 */
object GoogleDriveConnection {
    const val PROVIDER_ID = "google-drive"

    /** Registers the Drive provider under [PROVIDER_ID] -- safe and cheap to call on every
     * app startup regardless of whether Drive is actually connected yet; holder-core only
     * ever invokes it for a project that actually has a google-drive Location. */
    fun registerProvider(context: Context) {
        val appContext = context.applicationContext
        AndroidStorageProviderBridge.register(
            PROVIDER_ID,
            GoogleDriveStorageProvider(appContext, folderId = { requireStoredFolderId(appContext) }),
        )
    }

    /** Null when Drive isn't connected. Not a secret -- just which account to show in
     * Settings and to pass to GoogleDriveAuth.authorize so it can skip the account picker.
     * See GoogleDriveAuth's doc comment for why no token is stored anywhere at all. */
    fun connectedAccountEmail(context: Context): Flow<String?> =
        HolderSettings.connectedProviderConfig(context, PROVIDER_ID).map { it?.get(ACCOUNT_EMAIL_KEY) }

    /** The id of the single well-known "Holder/Resources" Drive folder every project's
     * google-drive Location shares -- see [GoogleDriveStorageProvider]'s doc comment for why
     * one global folder, not one per project, is today's deliberate simplification. Null
     * until Drive has been connected once; this, not [connectedAccountEmail], is the real
     * "connected" signal (email is best-effort, see [GoogleDriveAuth]'s EMAIL_SCOPE comment). */
    fun folderId(context: Context): Flow<String?> =
        HolderSettings.connectedProviderConfig(context, PROVIDER_ID).map { it?.get(FOLDER_ID_KEY) }

    /** Full connect flow: authorize `drive.file`, find-or-create the "Holder/Resources"
     * folder, and remember the connected account + folder id locally (see
     * [connectedAccountEmail]/[folderId] for why that's all that's stored -- no token).
     * [resolveConsent] is how the caller supplies UI for the account picker/consent screen,
     * via `ActivityResultContracts.StartIntentSenderForResult()`. */
    suspend fun connect(context: Context, resolveConsent: suspend (IntentSenderRequest) -> ActivityResult) {
        val appContext = context.applicationContext
        val authorization = GoogleDriveAuth.authorize(appContext, accountEmail = null, resolveConsent = resolveConsent)
        val folderId = withContext(Dispatchers.IO) {
            val client = OkHttpClient()
            val holderFolderId = DriveApi.findOrCreateFolder(client, authorization.accessToken, HOLDER_FOLDER_NAME, null)
            DriveApi.findOrCreateFolder(client, authorization.accessToken, RESOURCES_FOLDER_NAME, holderFolderId)
        }
        HolderSettings.setConnectedProviderConfig(
            appContext,
            PROVIDER_ID,
            buildMap {
                authorization.accountEmail?.let { put(ACCOUNT_EMAIL_KEY, it) }
                put(FOLDER_ID_KEY, folderId)
            },
        )
    }

    /** Forgets the local connection. Does not revoke Google's own consent grant -- the user
     * can do that from their Google Account settings if they want to; Holder just stops
     * trying to use Drive. Reconnecting later reuses the same "Holder/Resources" folder
     * rather than creating a duplicate, since it's found by name, not remembered by id alone. */
    suspend fun disconnect(context: Context) {
        HolderSettings.setConnectedProviderConfig(context.applicationContext, PROVIDER_ID, null)
    }

    /** Ensures projectId has a "google-drive" Location pointing at the connected Drive
     * folder, reusing one if this project already has it (e.g. from an earlier attach)
     * rather than creating a duplicate every time. Throws [GoogleDriveAuthException] if
     * Drive isn't connected. Signature matches what [team.holder.android.resource
     * .ConnectedStorageProviders] routes to by provider id. */
    suspend fun ensureLocationForProject(context: Context, projectId: String): HolderLocation {
        val folderId = folderId(context.applicationContext).first()
            ?: throw GoogleDriveAuthException("Connect Google Drive in Settings first")
        return StorageLocations.ensureLocationForProject(
            projectId = projectId,
            providerId = PROVIDER_ID,
            locationName = "Google Drive",
            configuration = mapOf("folder_id" to folderId),
        )
    }

    private fun requireStoredFolderId(context: Context): String =
        runBlocking { folderId(context).first() }
            ?: error("Google Drive is not connected -- connect it from Settings first")
}
