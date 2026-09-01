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
 * Host keys for the git hosting providers a Holder user is most likely to actually use, so a
 * brand-new install -- Android apps have no $HOME, hence no pre-existing ~/.ssh/known_hosts for
 * libgit2 to check a new SSH remote against -- can still connect without either a manual
 * "trust this host" step or blindly accepting whatever key answers first (trust-on-first-use).
 * These are each provider's own long-lived, publicly documented host keys, not secrets:
 * github.com's from its own https://api.github.com/meta `ssh_keys`; gitlab.com's and
 * bitbucket.org's from their published SSH host key fingerprint docs. A remote on any other
 * host still has no trust path yet -- this only covers the common case.
 */
internal val BUNDLED_KNOWN_HOSTS = listOf(
    "github.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl",
    "github.com ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEmKSENjQEezOmxkZMy7opKgwFB9nkt5YRrYMjNuG5N87uRgg6CLrbo5wAdT/y6v0mKV0U2w0WZ2YB/++Tpockg=",
    "github.com ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQCj7ndNxQowgcQnjshcLrqPEiiphnt+VTTvDP6mHBL9j1aNUkY4Ue1gvwnGLVlOhGeYrnZaMgRK6+PKCUXaDbC7qtbW8gIkhL7aGCsOr/C56SJMy/BCZfxd1nWzAOxSDPgVsmerOBYfNqltV9/hWCqBywINIR+5dIg6JTJ72pcEpEjcYgXkE2YEFXV1JHnsKgbLWNlhScqb2UmyRkQyytRLtL+38TGxkxCflmO+5Z8CSSNY7GidjMIZ7Q4zMjA2n1nGrlTDkzwDCsw+wqFPGQA179cnfGWOWRVruj16z6XyvxvjJwbz0wQZ75XK5tKSb7FNyeIEs4TT4jk+S4dhPeAUC5y+bDYirYgM4GC7uEnztnZyaVWQ7B381AK4Qdrwt51ZqExKbQpTUNn+EjqoTwvqNj4kqx5QUCI0ThS/YkOxJCXmPUWZbhjpCg56i+2aB6CmK2JGhn57K5mj0MNdBXA4/WnwH6XoPWJzK5Nyu2zB3nAZp+S5hpQs+p1vN1/wsjk=",
    "gitlab.com ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQCsj2bNKTBSpIYDEGk9KxsGh3mySTRgMtXL583qmBpzeQ+jqCMRgBqB98u3z++J1sKlXHWfM9dyhSevkMwSbhoR8XIq/U0tCNyokEi/ueaBMCvbcTHhO7FcwzY92WK4Yt0aGROY5qX2UKSeOvuP4D6TPqKF1onrSzH9bx9XUf2lEdWT/ia1NEKjunUqu1xOB/StKDHMoX4/OKyIzuS0q/T1zOATthvasJFoPrAjkohTyaDUz2LN5JoH839hViyEG82yB+MjcFV5MU3N1l1QL3cVUCh93xSaua1N85qivl+siMkPGbO5xR/En4iEY6K2XPASUEMaieWVNTRCtJ4S8H+9",
    "gitlab.com ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBFSMqzJeV9rUzU4kWitGjeR4PWSa29SPqJ1fVkhtj3Hw9xjLVXVYrU9QlYWrOLXBpQ6KWjbjTDTdDkoohFzgbEY=",
    "gitlab.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAfuCHKVTjquxvt6CM6tdG4SLp1Btn/nOeHHE5UOzRdf",
    "bitbucket.org ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQDQeJzhupRu0u0cdegZIa8e86EG2qOCsIsD1Xw0xSeiPDlCr7kq97NLmMbpKTX6Esc30NuoqEEHCuc7yWtwp8dI76EEEB1VqY9QJq6vk+aySyboD5QF61I/1WeTwu+deCbgKMGbUijeXhtfbxSxm6JwGrXrhBdofTsbKRUsrN1WoNgUa8uqN1Vx6WAJw1JHPhglEGGHea6QICwJOAr/6mrui/oB7pkaWKHj3z7d1IC4KWLtY47elvjbaTlkN04Kc/5LFEirorGYVbt15kAUlqGM65pk6ZBxtaO3+30LVlORZkxOh+LKL/BvbZ/iRNhItLqNyieoQj/uh/7Iv4uyH/cV/0b4WDSd3DptigWq84lJubb9t/DnZlrJazxyDCulTmKdOR7vs9gMTo+uoIrPSb8ScTtvw65+odKAlBj59dhnVp9zd7QUojOpXlL62Aw56U4oO+FALuevvMjiWeavKhJqlR7i5n9srYcrNV7ttmDw7kf/97P5zauIhxcjX+xHv4M=",
    "bitbucket.org ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBPIQmuzMBuKdWeF4+a2sjSSpBK0iqitSQ+5BM9KhpexuGt20JpTVM7u5BDZngncgrqDMbWdxMWWOGtZ9UgbqgZE=",
    "bitbucket.org ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIIazEu89wgQZ4bqs3d63QSMzYVa0MuJ2e2gKTKqu+UUO",
)

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
    fun registerWithNative(contextHandle: Long, filesDir: File, alias: String = DEFAULT_KEY_ALIAS): Int {
        ensureKnownHosts(filesDir)
        return nativeRegisterSigner(contextHandle, alias, sshPublicKeyBlob(alias), filesDir.absolutePath)
    }

    /** Makes sure BUNDLED_KNOWN_HOSTS' entries are present in filesDir/.ssh/known_hosts,
     * without disturbing anything else already there (e.g. a future "trust this custom host"
     * feature's own entries) -- appends only the ones missing, or creates the file fresh on a
     * brand-new install. Internal rather than private so GitIdentityTest can exercise it
     * directly with a plain temp directory, without needing AndroidKeyStore. */
    internal fun ensureKnownHosts(filesDir: File) {
        val sshDir = File(filesDir, ".ssh")
        sshDir.mkdirs()
        val knownHosts = File(sshDir, "known_hosts")
        val existingLines = if (knownHosts.isFile) knownHosts.readLines().toSet() else emptySet()
        val missing = BUNDLED_KNOWN_HOSTS.filter { it !in existingLines }
        if (missing.isEmpty()) return
        knownHosts.appendText(missing.joinToString("") { "$it\n" })
    }

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
