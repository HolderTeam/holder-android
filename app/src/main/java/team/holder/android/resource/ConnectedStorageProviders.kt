package team.holder.android.resource

import android.content.Context
import team.holder.android.HolderLocation
import team.holder.android.resource.drive.GoogleDriveConnection

/**
 * Routes an attach flow's "ensure I have a Location to write into" step to whichever connected
 * storage provider it's asked for, by provider id -- the one place a new provider (S3, WebDAV,
 * ...) gets wired into attaching, instead of callers like [attachPickedPhoto] hard-coding a
 * single provider by construction. See RESOURCE_STORAGE_ROADMAP.md's step 1.
 *
 * Each entry's connect-if-needed semantics (OAuth consent, a pasted credential, ...) belong to
 * the provider itself; this registry only knows how to route to the right one.
 */
object ConnectedStorageProviders {
    private val ensureLocation: Map<String, suspend (Context, String) -> HolderLocation> = mapOf(
        GoogleDriveConnection.PROVIDER_ID to GoogleDriveConnection::ensureLocationForProject,
    )

    suspend fun ensureLocationForProject(context: Context, projectId: String, providerId: String): HolderLocation =
        (ensureLocation[providerId] ?: error("Unknown storage provider: $providerId"))(context, projectId)
}
