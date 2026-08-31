package team.holder.android.resource

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import team.holder.android.HolderNative

/**
 * Downloads (or reads back from an on-disk cache, keyed by resourceId) a non-image
 * Resource's bytes and hands them to whatever app the user has installed for its media
 * type, via `ACTION_VIEW` and a `FileProvider`-granted `content://` URI -- Holder itself
 * never renders anything beyond images; the phone already has good apps for PDFs, office
 * documents, and everything else, matching holder-desktop's own committed philosophy
 * (see `ASSET_PREVIEW_PLAN.md`: metadata-preview-then-open-externally, not embedded
 * rendering, even for PDF).
 *
 * Requires the `androidx.core.content.FileProvider` `<provider>` declared in
 * AndroidManifest.xml (authority `${applicationId}.fileprovider`) and its
 * `resource-attachments` cache path in `res/xml/file_paths.xml` -- only that cache
 * subdirectory is ever exposed to other apps, and only read access is granted, revoked
 * automatically once the receiving app is done with it.
 *
 * Throws (callers should wrap in runCatching) if no app can handle the file's media
 * type -- there is no special handling here beyond letting that exception surface with
 * a clear message; ACTION_VIEW / createChooser already reports that case distinctly
 * from a genuine download/retrieve failure.
 */
suspend fun openResourceExternally(context: Context, resourceId: String) {
    val (file, mediaType) = withContext(Dispatchers.IO) {
        val resource = HolderNative.getResource(resourceId)
        val asset = resource.assets.firstOrNull() ?: error("resource has no asset")
        val placement = asset.placements.firstOrNull() ?: error("asset has no placement")
        val cacheDir = File(context.cacheDir, "resource-attachments").apply { mkdirs() }
        val safeName = asset.originalFilename.ifBlank { "attachment" }.replace('/', '_')
        val file = File(cacheDir, "$resourceId-$safeName")
        if (!file.exists()) {
            HolderNative.retrieveAsset(resourceId, asset.assetId, placement.placementId, file.absolutePath)
        }
        file to asset.mediaType
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mediaType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Open ${file.name.substringAfter('-')}"))
}
