package team.holder.android.git

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val FIELD_SIZE = 32 // P-256 coordinate size in bytes
private const val DEFAULT_KEY_ALIAS = "holder-git-identity"

/**
 * The app's git SSH identity: a non-exportable AndroidKeyStore EC P-256 key. Holder's git sync
 * (see HolderNative) authenticates with this instead of a filesystem-based key, since Android
 * apps have no ~/.ssh. registerWithNative bridges libssh2's raw-bytes-to-sign challenge across
 * JNI to Keystore's Signature API -- see holder_git_signer.cpp for the native half.
 */
object GitIdentity {
    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun ensureKeyPair(alias: String = DEFAULT_KEY_ALIAS): ECPublicKey {
        val ks = keyStore()
        (ks.getCertificate(alias)?.publicKey as? ECPublicKey)?.let { return it }

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        generator.initialize(spec)
        return generator.generateKeyPair().public as ECPublicKey
    }

    /** SSH wire-format ecdsa-sha2-nistp256 public key blob (RFC 5656 3.1), unencoded. */
    fun sshPublicKeyBlob(alias: String = DEFAULT_KEY_ALIAS): ByteArray {
        val pub = ensureKeyPair(alias)
        val x = unsignedFixed(pub.w.affineX, FIELD_SIZE)
        val y = unsignedFixed(pub.w.affineY, FIELD_SIZE)
        val q = ByteArray(1 + FIELD_SIZE * 2)
        q[0] = 0x04
        System.arraycopy(x, 0, q, 1, FIELD_SIZE)
        System.arraycopy(y, 0, q, 1 + FIELD_SIZE, FIELD_SIZE)

        val out = ByteArrayOutputStream()
        writeSshString(out, "ecdsa-sha2-nistp256".toByteArray(Charsets.US_ASCII))
        writeSshString(out, "nistp256".toByteArray(Charsets.US_ASCII))
        writeSshString(out, q)
        return out.toByteArray()
    }

    /** The pasteable "ecdsa-sha2-nistp256 AAAA... comment" line, e.g. for a GitHub deploy key. */
    fun sshPublicKeyLine(alias: String = DEFAULT_KEY_ALIAS, comment: String = "holder-android"): String {
        val encoded = Base64.encodeToString(sshPublicKeyBlob(alias), Base64.NO_WRAP)
        return "ecdsa-sha2-nistp256 $encoded $comment"
    }

    /**
     * Registers this identity as contextHandle's git SSH signer (see
     * holder_git_set_ssh_signer in holder.h) and points libgit2 at filesDir for
     * ~/.ssh/known_hosts (Android apps have no $HOME). Safe to call once during
     * HolderNative.initialize(); subsequent git operations on this context sign
     * with the Keystore key instead of the default ssh-agent/~/.ssh lookup.
     */
    fun registerWithNative(contextHandle: Long, filesDir: File, alias: String = DEFAULT_KEY_ALIAS): Int =
        nativeRegisterSigner(contextHandle, alias, sshPublicKeyBlob(alias), filesDir.absolutePath)

    /** Called from native code (see holder_git_signer.cpp) to sign an SSH auth challenge.
     * Returns a raw DER ECDSA-Sig-Value{r,s} -- native code reshapes it into SSH wire format. */
    @Suppress("unused") // invoked via JNI
    private fun signForNative(alias: String, data: ByteArray): ByteArray {
        val ks = keyStore()
        val privateKey = ks.getKey(alias, null) as PrivateKey
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(data)
        return signature.sign()
    }

    private fun writeSshString(out: ByteArrayOutputStream, bytes: ByteArray) {
        val len = bytes.size
        out.write((len ushr 24) and 0xFF)
        out.write((len ushr 16) and 0xFF)
        out.write((len ushr 8) and 0xFF)
        out.write(len and 0xFF)
        out.write(bytes)
    }

    private fun unsignedFixed(value: BigInteger, size: Int): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(size)
        if (raw.size >= size) {
            System.arraycopy(raw, raw.size - size, out, 0, size)
        } else {
            System.arraycopy(raw, 0, out, size - raw.size, raw.size)
        }
        return out
    }

    private external fun nativeRegisterSigner(
        contextHandle: Long,
        keyAlias: String,
        publicKeyBlob: ByteArray,
        homedir: String,
    ): Int
}
