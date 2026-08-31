package team.holder.android.resource.drive

import android.content.Context
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import team.holder.android.HolderSettings
import team.holder.android.resource.AndroidStorageProviderBridge

private const val DRIVE_PROVIDER_NAME = "google-drive"
private const val HOLDER_FOLDER_NAME = "Holder"
private const val RESOURCES_FOLDER_NAME = "Resources"

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
    /** Registers the Drive provider under "google-drive" -- safe and cheap to call on every
     * app startup regardless of whether Drive is actually connected yet; holder-core only
     * ever invokes it for a project that actually has a google-drive Location. */
    fun registerProvider(context: Context) {
        val appContext = context.applicationContext
        AndroidStorageProviderBridge.register(
            DRIVE_PROVIDER_NAME,
            GoogleDriveStorageProvider(appContext, folderId = { requireStoredFolderId(appContext) }),
        )
    }

    /** Full connect flow: authorize `drive.file`, find-or-create the "Holder/Resources"
     * folder, and remember the connected account + folder id locally (see
     * [HolderSettings.driveConnectedAccountEmail]/[HolderSettings.driveFolderId] for why
     * that's all that's stored -- no token). [resolveConsent] is how the caller supplies UI
     * for the account picker/consent screen, via `ActivityResultContracts
     * .StartIntentSenderForResult()`. */
    suspend fun connect(context: Context, resolveConsent: suspend (IntentSenderRequest) -> ActivityResult) {
        val appContext = context.applicationContext
        val authorization = GoogleDriveAuth.authorize(appContext, accountEmail = null, resolveConsent = resolveConsent)
        val folderId = withContext(Dispatchers.IO) {
            val client = OkHttpClient()
            val holderFolderId = DriveApi.findOrCreateFolder(client, authorization.accessToken, HOLDER_FOLDER_NAME, null)
            DriveApi.findOrCreateFolder(client, authorization.accessToken, RESOURCES_FOLDER_NAME, holderFolderId)
        }
        HolderSettings.setDriveConnectedAccountEmail(appContext, authorization.accountEmail)
        HolderSettings.setDriveFolderId(appContext, folderId)
    }

    /** Forgets the local connection. Does not revoke Google's own consent grant -- the user
     * can do that from their Google Account settings if they want to; Holder just stops
     * trying to use Drive. Reconnecting later reuses the same "Holder/Resources" folder
     * rather than creating a duplicate, since it's found by name, not remembered by id alone. */
    suspend fun disconnect(context: Context) {
        val appContext = context.applicationContext
        HolderSettings.setDriveConnectedAccountEmail(appContext, null)
        HolderSettings.setDriveFolderId(appContext, null)
    }

    private fun requireStoredFolderId(context: Context): String =
        runBlocking { HolderSettings.driveFolderId(context).first() }
            ?: error("Google Drive is not connected -- connect it from Settings first")
}
