package team.holder.android.resource

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import team.holder.android.HolderNative
import team.holder.android.resource.drive.GoogleDriveConnection

/**
 * Copies [uri] (as picked from the system photo picker) into holder-core's Resource/Asset
 * model, attached to [cardId] in [projectId] -- ensuring a Google Drive Location exists
 * first (see [GoogleDriveConnection.ensureLocationForProject]), the only storage backend
 * exposed to Android today. Returns the Markdown image reference to insert into the card's
 * body (`![label](holder://resource/<id>)`, the same scheme holder-desktop already renders
 * -- see `HolderMarkdownViewer`) -- inserting it is the caller's job, not this function's.
 */
suspend fun attachPickedPhoto(context: Context, projectId: String, cardId: String, uri: Uri): String =
    withContext(Dispatchers.IO) {
        val location = GoogleDriveConnection.ensureLocationForProject(context, projectId)
        val displayName = queryDisplayName(context, uri) ?: "photo.jpg"
        val staging = File(context.cacheDir, "attach-staging").apply { mkdirs() }
        val stagedFile = File(staging, "${UUID.randomUUID()}-$displayName")
        try {
            val opened = context.contentResolver.openInputStream(uri)
                ?: error("could not open the picked photo")
            opened.use { input -> stagedFile.outputStream().use { output -> input.copyTo(output) } }

            val result = HolderNative.importAsset(projectId, cardId, location.locationId, stagedFile.absolutePath)
            val label = displayName.substringBeforeLast('.').ifBlank { "Photo" }
            "![$label](holder://resource/${result.resourceId})"
        } finally {
            stagedFile.delete()
        }
    }

private fun queryDisplayName(context: Context, uri: Uri): String? {
    val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
    return context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
}
