package team.holder.android.git.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Covers writeLines, the pure streaming/budget core -- writeDeviceSnapshot/deviceCards need a
 * real HolderNative/native library and aren't unit-testable without more infra, matching this
 * codebase's existing split between pure-logic and device-only coverage (see
 * GitHubBackfillTest's doc comment).
 */
class SnapshotWriterTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun readGzipLines(file: File): List<String> =
        GZIPInputStream(file.inputStream()).bufferedReader(Charsets.UTF_8).readLines()

    @Test
    fun writeLines_writesEveryLineInOrder_asValidGzippedJsonl() {
        val destination = File(tempFolder.newFolder("snapshot"), "snapshot.jsonl.gz")
        val lines = listOf("""{"card_id":"a"}""", """{"card_id":"b"}""", """{"card_id":"c"}""")

        val result = SnapshotWriter.writeLines(lines.asSequence(), destination, budgetBytes = 10_000_000)

        assertEquals(3, result.cardCount)
        assertFalse(result.truncated)
        assertEquals(destination.length(), result.compressedBytes)
        assertTrue(destination.isFile)
        assertEquals(lines, readGzipLines(destination))
    }

    @Test
    fun writeLines_stopsBeforeExceedingTheBudget_withoutOverExceedingIt() {
        val destination = File(tempFolder.newFolder("snapshot"), "snapshot.jsonl.gz")
        val lines = List(20) { """{"card_id":"card-$it","title":"Card number $it","body":"some body text"}""" }

        // A budget well below what all 20 lines need (measured via an unbounded run) but well
        // above what a handful need, so the run genuinely has to stop partway through rather
        // than at either extreme.
        val fullSize = SnapshotWriter.writeLines(
            lines.asSequence(),
            File(tempFolder.newFolder("probe"), "probe.jsonl.gz"),
            budgetBytes = 10_000_000,
        ).compressedBytes
        val budget = fullSize / 3

        val result = SnapshotWriter.writeLines(lines.asSequence(), destination, budget)

        assertTrue(result.truncated)
        assertTrue("expected some lines written, got ${result.cardCount}", result.cardCount > 0)
        assertTrue("expected fewer than all 20 lines written, got ${result.cardCount}", result.cardCount < 20)
        assertTrue(
            "compressed size ${result.compressedBytes} must not exceed the budget $budget",
            result.compressedBytes <= budget,
        )
        // What was written is an exact, uncorrupted prefix of the input -- not just "some
        // subset" or a truncated last line.
        assertEquals(lines.take(result.cardCount), readGzipLines(destination))
    }

    @Test
    fun writeLines_leavesAnExistingSnapshotUntouched_whenThereIsNothingToWrite() {
        val destination = File(tempFolder.newFolder("snapshot"), "snapshot.jsonl.gz")
        destination.writeBytes(byteArrayOf(1, 2, 3, 4))

        val result = SnapshotWriter.writeLines(emptySequence(), destination, budgetBytes = 1000)

        assertEquals(0, result.cardCount)
        assertFalse(result.truncated)
        assertEquals(listOf<Byte>(1, 2, 3, 4), destination.readBytes().toList())
    }

    @Test
    fun writeLines_createsNoFile_whenThereIsNothingToWriteAndNoneExistedBefore() {
        val destination = File(tempFolder.newFolder("snapshot"), "snapshot.jsonl.gz")

        SnapshotWriter.writeLines(emptySequence(), destination, budgetBytes = 1000)

        assertFalse(destination.exists())
        assertFalse(File(destination.parentFile, destination.name + ".tmp").exists())
    }

    @Test
    fun writeLines_leavesNoTempFileBehind_afterANormalSuccessfulWrite() {
        val destination = File(tempFolder.newFolder("snapshot"), "snapshot.jsonl.gz")

        SnapshotWriter.writeLines(sequenceOf("""{"card_id":"a"}"""), destination, budgetBytes = 10_000_000)

        assertFalse(File(destination.parentFile, destination.name + ".tmp").exists())
    }

    @Test
    fun writeLines_replacesAPreviousSnapshot_ratherThanAppendingOrMerging() {
        val destination = File(tempFolder.newFolder("snapshot"), "snapshot.jsonl.gz")
        SnapshotWriter.writeLines(sequenceOf("""{"card_id":"stale"}"""), destination, budgetBytes = 10_000_000)

        SnapshotWriter.writeLines(sequenceOf("""{"card_id":"fresh"}"""), destination, budgetBytes = 10_000_000)

        assertEquals(listOf("""{"card_id":"fresh"}"""), readGzipLines(destination))
    }
}
