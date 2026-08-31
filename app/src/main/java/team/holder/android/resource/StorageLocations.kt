package team.holder.android.resource

import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import team.holder.android.HolderLocation
import team.holder.android.HolderNative

/**
 * The "ensure this project has a Location for provider X, reusing one if it already exists"
 * pattern -- originally [team.holder.android.resource.drive.GoogleDriveConnection]'s own
 * `ensureLocationForProject`, factored out here so a second storage provider (S3, WebDAV, ...)
 * doesn't have to copy-paste it. See RESOURCE_STORAGE_ROADMAP.md's step 1.
 */
object StorageLocations {
    suspend fun ensureLocationForProject(
        projectId: String,
        providerId: String,
        locationName: String,
        configuration: Map<String, String>,
    ): HolderLocation = withContext(Dispatchers.IO) {
        HolderNative.listLocations(projectId).firstOrNull { it.provider == providerId }
            ?: HolderNative.putLocation(
                locationId = UUID.randomUUID().toString(),
                projectId = projectId,
                name = locationName,
                provider = providerId,
                configuration = configuration,
                now = System.currentTimeMillis() / 1000,
            )
    }
}
