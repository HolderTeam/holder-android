package team.holder.android.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Covers ensureKnownHosts in isolation from GitIdentity's AndroidKeyStore-backed half, which
 * needs a real device/emulator. This is the part that actually fixed "unknown remote ssh
 * hostkey" failing every brand-new SSH remote on a fresh install -- Android apps have no
 * pre-existing ~/.ssh/known_hosts for libgit2 to check against, and nothing else populates one.
 */
class GitIdentityTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun ensureKnownHosts_createsTheFileWithEveryBundledEntry_whenNoneExists() {
        val filesDir = tempFolder.newFolder("files")

        GitIdentity.ensureKnownHosts(filesDir)

        val knownHosts = filesDir.resolve(".ssh/known_hosts")
        assertTrue(knownHosts.isFile)
        val lines = knownHosts.readLines()
        for (bundled in BUNDLED_KNOWN_HOSTS) {
            assertTrue("expected known_hosts to contain: $bundled", lines.contains(bundled))
        }
        assertEquals(BUNDLED_KNOWN_HOSTS.size, lines.size)
    }

    @Test
    fun ensureKnownHosts_addsOnlyWhatsMissing_withoutDisturbingExistingEntries() {
        val filesDir = tempFolder.newFolder("files")
        val sshDir = filesDir.resolve(".ssh").apply { mkdirs() }
        val customEntry = "example.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAsomeCustomHostKey"
        sshDir.resolve("known_hosts").writeText("$customEntry\n")

        GitIdentity.ensureKnownHosts(filesDir)

        val lines = sshDir.resolve("known_hosts").readLines()
        assertTrue("custom entry should survive", lines.contains(customEntry))
        for (bundled in BUNDLED_KNOWN_HOSTS) {
            assertTrue(lines.contains(bundled))
        }
        assertEquals(BUNDLED_KNOWN_HOSTS.size + 1, lines.size)
    }

    @Test
    fun ensureKnownHosts_isIdempotent_writingNothingOnASecondCall() {
        val filesDir = tempFolder.newFolder("files")
        GitIdentity.ensureKnownHosts(filesDir)
        val knownHosts = filesDir.resolve(".ssh/known_hosts")
        val firstContent = knownHosts.readText()

        GitIdentity.ensureKnownHosts(filesDir)

        assertEquals(firstContent, knownHosts.readText())
    }
}
