package team.holder.android.git.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.GZIPOutputStream

class SnapshotProtectionTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun nonEmptySnapshot(filesDir: File): File {
        val file = File(filesDir, "backup/snapshot.jsonl.gz").apply { parentFile?.mkdirs() }
        GZIPOutputStream(file.outputStream()).use { it.write("{}\n".toByteArray()) }
        return file
    }

    @Test
    fun isArmed_isFalse_beforeEverArmed() {
        val filesDir = tempFolder.newFolder("files")
        assertFalse(SnapshotProtection.isArmed(filesDir))
    }

    @Test
    fun armIfFreshInstallHasAnUnseenSnapshot_arms_whenDataDirIsNewAndASnapshotAlreadyExists() {
        // The only combination that can mean "Android's own restore mechanism put a snapshot
        // here before this install ever ran" -- see this object's doc comment.
        val filesDir = tempFolder.newFolder("files")
        val snapshot = nonEmptySnapshot(filesDir)

        SnapshotProtection.armIfFreshInstallHasAnUnseenSnapshot(filesDir, snapshot, dataDirExistedBeforeInit = false)

        assertTrue(SnapshotProtection.isArmed(filesDir))
    }

    @Test
    fun armIfFreshInstallHasAnUnseenSnapshot_doesNothing_whenDataDirAlreadyExisted() {
        // Every ordinary launch after the first -- there's nothing new to protect, and this
        // must never re-arm mid-install just because a snapshot happens to exist by then (it
        // always will, once SnapshotWorker has run at least once).
        val filesDir = tempFolder.newFolder("files")
        val snapshot = nonEmptySnapshot(filesDir)

        SnapshotProtection.armIfFreshInstallHasAnUnseenSnapshot(filesDir, snapshot, dataDirExistedBeforeInit = true)

        assertFalse(SnapshotProtection.isArmed(filesDir))
    }

    @Test
    fun armIfFreshInstallHasAnUnseenSnapshot_doesNothing_whenNoSnapshotExistsYet() {
        // The overwhelmingly common case: an ordinary fresh install with nothing to protect --
        // must add zero friction here.
        val filesDir = tempFolder.newFolder("files")
        val snapshot = File(filesDir, "backup/snapshot.jsonl.gz")

        SnapshotProtection.armIfFreshInstallHasAnUnseenSnapshot(filesDir, snapshot, dataDirExistedBeforeInit = false)

        assertFalse(SnapshotProtection.isArmed(filesDir))
    }

    @Test
    fun disarm_clearsAnArmedGuard() {
        val filesDir = tempFolder.newFolder("files")
        val snapshot = nonEmptySnapshot(filesDir)
        SnapshotProtection.armIfFreshInstallHasAnUnseenSnapshot(filesDir, snapshot, dataDirExistedBeforeInit = false)

        SnapshotProtection.disarm(filesDir)

        assertFalse(SnapshotProtection.isArmed(filesDir))
    }

    @Test
    fun disarm_isSafe_whenNeverArmed() {
        val filesDir = tempFolder.newFolder("files")
        SnapshotProtection.disarm(filesDir)
        assertFalse(SnapshotProtection.isArmed(filesDir))
    }
}
