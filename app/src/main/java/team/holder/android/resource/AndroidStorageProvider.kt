package team.holder.android.resource

/**
 * Codes matching holder-core's `holder_storage_error_code` (see holder.h) -- ordinal
 * position must stay in sync with that C enum, since [encode]/`report_failure` in
 * holder_storage_provider_bridge.cpp cross the JNI boundary as plain ordinals, not names.
 */
enum class StorageErrorCode {
    UNAVAILABLE,
    AUTHENTICATION,
    PERMISSION,
    CAPACITY,
    INTEGRITY,
    CONFLICT,
    INVALID_CONFIGURATION,
    TRANSIENT,
}

/** Encodes a failure for [AndroidStorageProvider]'s put/get/remove return convention. */
fun StorageErrorCode.encode(message: String): String = "${ordinal}:$message"

/**
 * Implemented by a Kotlin storage backend (e.g. [team.holder.android.resource.drive.GoogleDriveStorageProvider])
 * registered with holder-core's process-wide storage provider registry via
 * [AndroidStorageProviderBridge.register] -- native side: holder_storage_provider_bridge.cpp,
 * `holder_storage_provider_register` in holder.h. Mirrors
 * [team.holder.android.HolderNative]'s object_key-addressed model exactly: an object_key is
 * an opaque, already-unique string (see holder-core's `object_key_for`), never something a
 * provider chooses or interprets itself.
 *
 * put/get/remove return `null` on success, or a [StorageErrorCode]-[encode]d string on
 * failure -- deliberately a plain string rather than a custom exception type, so the JNI
 * bridge can report a typed failure back across the C ABI without needing Java reflection to
 * pull fields out of a thrown exception. Any *other* exception thrown from these three is
 * still treated as a failure (a generic, unspecific one) by the bridge, so implementations
 * are free to let an unexpected exception propagate rather than catching everything --
 * [encode] is only needed to report a specific, actionable code.
 *
 * [exists] is the one exception to that convention: a genuine inability to determine
 * whether an object exists is rare and not worth a typed code, so it simply throws; the
 * bridge reports that as a generic failure. Returning `false` because the object legitimately
 * isn't there is not a failure at all -- see [holder_storage_provider_bridge.cpp]/
 * `StorageProvider::exists` in holder-core.
 */
interface AndroidStorageProvider {
    fun put(objectKey: String, stagedFilePath: String, storedSize: Long, storedSha256: String): String?
    fun get(objectKey: String, destinationFilePath: String): String?
    fun exists(objectKey: String): Boolean
    fun remove(objectKey: String): String?
}
