package team.holder.android.resource.s3

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import team.holder.android.HolderLocation
import team.holder.android.HolderSettings
import team.holder.android.keyring.AndroidKeyringStore
import team.holder.android.resource.AndroidStorageProviderBridge
import team.holder.android.resource.StorageLocations

private const val ENDPOINT_KEY = "endpoint"
private const val REGION_KEY = "region"
private const val BUCKET_KEY = "bucket"
private const val ACCESS_KEY_ID_SECRET_KEY = "s3-access-key-id"
private const val SECRET_ACCESS_KEY_SECRET_KEY = "s3-secret-access-key"
// A key vanishingly unlikely to exist, probed with HEAD when connecting so a wrong endpoint/
// region/bucket/credential is caught immediately rather than surfacing later on first attach --
// a 404 from a real bucket is success (it proves the credentials and address are good), while
// an auth/permission/network failure is not.
private const val CONNECTIVITY_PROBE_KEY = ".holder-connectivity-check"

class S3ConnectException(message: String) : Exception(message)

/**
 * Ties non-secret config (endpoint/region/bucket -- held via
 * [HolderSettings.connectedProviderConfig], the same generic slot Drive uses), the access key
 * pair (held in [AndroidKeyringStore] as local secrets, never synced -- matching how desktop's
 * `LocationBindingStore` keeps S3 credentials host-local, see `resources_tool_view.vala`'s
 * "The endpoint and bucket are shared through Git. Credentials stay in this device's
 * keyring." copy), and [AndroidStorageProviderBridge] registration together. Same shape as
 * [team.holder.android.resource.drive.GoogleDriveConnection] for Drive. See
 * RESOURCE_STORAGE_ROADMAP.md's step 3.
 *
 * One connected bucket per device, not one per Location -- the same simplification Drive
 * already makes (see [team.holder.android.resource.drive.GoogleDriveStorageProvider]'s doc
 * comment), tracking the same underlying limitation: [AndroidStorageProviderBridge] registers
 * one provider *instance* per provider name process-wide, not one per Location.
 *
 * Connecting today is manual entry (endpoint/region/bucket/access key/secret key, mirroring
 * desktop's "Add S3-compatible Storage" dialog) -- the QR-code desktop-aided credential
 * handoff RESOURCE_STORAGE_ROADMAP.md describes is future work with its own design pass, not
 * built here. Typing an access key on a phone keyboard is inconvenient but not incorrect.
 */
object S3Connection {
    const val PROVIDER_ID = "s3_compatible"

    /** Registers the S3 provider under [PROVIDER_ID] -- safe and cheap to call on every app
     * startup regardless of whether S3 is actually connected yet, same contract as
     * [team.holder.android.resource.drive.GoogleDriveConnection.registerProvider]. */
    fun registerProvider(context: Context) {
        val appContext = context.applicationContext
        AndroidStorageProviderBridge.register(PROVIDER_ID, AndroidS3Provider(config = { requireStoredConfig(appContext) }))
    }

    /** Null when S3 isn't connected. The bucket name is the one thing worth showing in
     * Settings ("Connected to my-bucket") -- nothing in the non-secret config is sensitive. */
    fun connectedBucket(context: Context): Flow<String?> =
        HolderSettings.connectedProviderConfig(context, PROVIDER_ID).map { it?.get(BUCKET_KEY) }

    /** Validates the given config actually works -- a connectivity probe, see
     * [CONNECTIVITY_PROBE_KEY] -- before persisting anything. Throws [S3ConnectException] if
     * the probe fails; otherwise stores the non-secret shape via [HolderSettings] and the
     * access key pair via [AndroidKeyringStore]. */
    suspend fun connect(
        context: Context,
        endpoint: String,
        region: String,
        bucket: String,
        accessKeyId: String,
        secretAccessKey: String,
    ) {
        val trimmedEndpoint = endpoint.trim()
        val trimmedRegion = region.trim()
        val trimmedBucket = bucket.trim()
        val trimmedAccessKeyId = accessKeyId.trim()
        if (trimmedEndpoint.isEmpty() || trimmedRegion.isEmpty() || trimmedBucket.isEmpty() ||
            trimmedAccessKeyId.isEmpty() || secretAccessKey.isEmpty()
        ) {
            throw S3ConnectException("Endpoint, region, bucket and credentials are required")
        }
        val appContext = context.applicationContext
        val config = S3Config(trimmedEndpoint, trimmedRegion, trimmedBucket, trimmedAccessKeyId, secretAccessKey)
        withContext(Dispatchers.IO) {
            try {
                AndroidS3Provider(config = { config }).exists(CONNECTIVITY_PROBE_KEY)
            } catch (failure: Exception) {
                throw S3ConnectException(failure.message ?: "Could not connect to this S3 bucket")
            }
        }
        AndroidKeyringStore.storeLocalSecret(appContext, ACCESS_KEY_ID_SECRET_KEY, trimmedAccessKeyId)
        AndroidKeyringStore.storeLocalSecret(appContext, SECRET_ACCESS_KEY_SECRET_KEY, secretAccessKey)
        HolderSettings.setConnectedProviderConfig(
            appContext,
            PROVIDER_ID,
            mapOf(ENDPOINT_KEY to trimmedEndpoint, REGION_KEY to trimmedRegion, BUCKET_KEY to trimmedBucket),
        )
    }

    /** Forgets the local connection, including both stored credentials. Doesn't touch the
     * bucket itself. */
    suspend fun disconnect(context: Context) {
        val appContext = context.applicationContext
        AndroidKeyringStore.removeLocalSecret(appContext, ACCESS_KEY_ID_SECRET_KEY)
        AndroidKeyringStore.removeLocalSecret(appContext, SECRET_ACCESS_KEY_SECRET_KEY)
        HolderSettings.setConnectedProviderConfig(appContext, PROVIDER_ID, null)
    }

    /** Ensures projectId has an "s3_compatible" Location, reusing one if this project already
     * has it (e.g. one synced in from desktop, or from an earlier attach on this device)
     * rather than creating a duplicate. The configuration written for a *new* Location
     * matches desktop's own shape (endpoint/region/bucket/addressing_style, no credentials --
     * see this object's doc comment) so it stays readable there too. Throws
     * [S3ConnectException] if S3 isn't connected. Signature matches what
     * [team.holder.android.resource.ConnectedStorageProviders] routes to by provider id. */
    suspend fun ensureLocationForProject(context: Context, projectId: String): HolderLocation {
        val appContext = context.applicationContext
        val config = HolderSettings.connectedProviderConfig(appContext, PROVIDER_ID).first()
            ?: throw S3ConnectException("Connect an S3-compatible bucket in Settings first")
        val bucket = config.getValue(BUCKET_KEY)
        return StorageLocations.ensureLocationForProject(
            projectId = projectId,
            providerId = PROVIDER_ID,
            locationName = bucket,
            configuration = mapOf(
                ENDPOINT_KEY to config.getValue(ENDPOINT_KEY),
                REGION_KEY to config.getValue(REGION_KEY),
                BUCKET_KEY to bucket,
                "addressing_style" to "path",
            ),
        )
    }

    private fun requireStoredConfig(context: Context): S3Config {
        val stored = runBlocking { HolderSettings.connectedProviderConfig(context, PROVIDER_ID).first() }
            ?: error("S3 is not connected -- connect it from Settings first")
        val accessKeyId = AndroidKeyringStore.getLocalSecret(context, ACCESS_KEY_ID_SECRET_KEY)
            ?: error("S3 access key is missing -- reconnect from Settings")
        val secretAccessKey = AndroidKeyringStore.getLocalSecret(context, SECRET_ACCESS_KEY_SECRET_KEY)
            ?: error("S3 secret access key is missing -- reconnect from Settings")
        return S3Config(
            endpoint = stored.getValue(ENDPOINT_KEY),
            region = stored.getValue(REGION_KEY),
            bucket = stored.getValue(BUCKET_KEY),
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey,
        )
    }
}
