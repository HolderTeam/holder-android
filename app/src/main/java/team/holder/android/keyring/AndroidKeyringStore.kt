package team.holder.android.keyring

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.Keep
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val KEY_ALIAS = "holder-platform-keyring"
private const val PREFS_NAME = "holder_platform_keyring"
private const val GCM_TAG_LENGTH_BITS = 128
private const val GCM_IV_LENGTH_BYTES = 12
private const val TRANSFORMATION = "AES/GCM/NoPadding"

/**
 * Android's substitute for the desktop platform keyrings (libsecret/Keychain/Windows
 * Credential Manager) holder-core otherwise relies on for encrypted_git project keys and
 * other secrets (e.g. AI provider credentials). Values are AES-256-GCM encrypted with a
 * non-exportable AndroidKeyStore key before being written to plain SharedPreferences --
 * built directly on Keystore rather than androidx.security:security-crypto, whose
 * EncryptedSharedPreferences/MasterKey Google deprecated in favor of exactly this
 * (release notes: "Deprecated all APIs in favour of existing platform APIs and direct use
 * of Android Keystore"). Registered once as holder-core's process-wide external keyring
 * provider (see holder_keyring_set_provider in holder.h) via the JNI bridge in
 * holder_keyring_bridge.cpp.
 */
object AndroidKeyringStore {
    private lateinit var appContext: Context
    private var registered = false

    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun ensureKey(): SecretKey {
        val ks = keyStore()
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, ensureKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        // GCM's IV is not secret; storing it alongside the ciphertext (rather than
        // separately) is the standard approach.
        return Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val combined = Base64.decode(stored, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH_BYTES)
        val ciphertext = combined.copyOfRange(GCM_IV_LENGTH_BYTES, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, ensureKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    /** Stores an arbitrary Android-only local secret (e.g. an S3 secret access key) under
     * the same AndroidKeyStore-backed AES-256-GCM encryption this object already uses for
     * holder-core's own keyring secrets -- callable directly from Kotlin, not just via the
     * JNI seam below. For secrets that are purely local device config and never flow through
     * holder-core's project-key model (which S3 credentials aren't -- see
     * [team.holder.android.resource.s3.S3Connection]). Takes [context] directly rather than
     * relying on [registerWithNative] having already been called, since callers of this may
     * run before that registration happens. */
    fun storeLocalSecret(context: Context, key: String, secret: String) {
        localSecretPrefs(context).edit().putString(localSecretKey(key), encrypt(secret)).apply()
    }

    fun getLocalSecret(context: Context, key: String): String? =
        localSecretPrefs(context).getString(localSecretKey(key), null)?.let(::decrypt)

    fun removeLocalSecret(context: Context, key: String) {
        localSecretPrefs(context).edit().remove(localSecretKey(key)).apply()
    }

    private fun localSecretPrefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun localSecretKey(key: String) = "local_secret:$key"

    /** Registers this store as holder-core's platform keyring provider for the process.
     * Idempotent -- only the first call actually registers. */
    @Synchronized
    fun registerWithNative(context: Context): Int {
        if (registered) return 0
        appContext = context.applicationContext
        val rc = nativeRegisterProvider()
        registered = rc == 0
        return rc
    }

    private fun storageKey(kind: Int, service: String, account: String, projectId: String?): String =
        if (kind == 1) "project:$projectId:$account" else "generic:$service:$account"

    /** Called from native code (see holder_keyring_bridge.cpp). kind is 0 for a generic
     * secret (service+account) or 1 for a project key (projectId+account). */
    @Keep
    @Suppress("unused") // invoked via JNI
    private fun lookup(kind: Int, service: String, account: String, projectId: String?): String? {
        val stored = prefs.getString(storageKey(kind, service, account, projectId), null) ?: return null
        return decrypt(stored)
    }

    @Keep
    @Suppress("unused") // invoked via JNI
    private fun store(kind: Int, service: String, account: String, projectId: String?, secret: String) {
        prefs.edit().putString(storageKey(kind, service, account, projectId), encrypt(secret)).apply()
    }

    @Keep
    @Suppress("unused") // invoked via JNI
    private fun remove(kind: Int, service: String, account: String, projectId: String?) {
        prefs.edit().remove(storageKey(kind, service, account, projectId)).apply()
    }

    private external fun nativeRegisterProvider(): Int
}
