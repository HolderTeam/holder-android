package team.holder.android.resource

/**
 * Registers an [AndroidStorageProvider] as holder-core's process-wide storage provider for a
 * given provider name (e.g. "google-drive") -- the storage-seam counterpart to
 * [team.holder.android.keyring.AndroidKeyringStore]'s keyring bridge. Unlike the keyring
 * seam (one process-wide provider, so [AndroidKeyringStore] itself is the callback target),
 * multiple named storage providers can be registered side by side, so the provider instance
 * is passed explicitly here rather than this object being the target itself. See
 * holder_storage_provider_bridge.cpp / holder_storage_provider_register in holder.h for the
 * native side and the exact ownership contract.
 */
object AndroidStorageProviderBridge {
    /**
     * Registers [provider] for [providerName] for the lifetime of the native process --
     * replacing whatever was previously registered under that name, if anything. Returns a
     * HOLDER_* status code (0 is HOLDER_OK); safe to call again to re-register after
     * [team.holder.android.HolderNative] closes and reopens its context, since this seam is
     * process-wide, not scoped to a context.
     */
    fun register(providerName: String, provider: AndroidStorageProvider): Int =
        nativeRegisterProvider(providerName, provider)

    @JvmStatic
    private external fun nativeRegisterProvider(providerName: String, provider: AndroidStorageProvider): Int
}
