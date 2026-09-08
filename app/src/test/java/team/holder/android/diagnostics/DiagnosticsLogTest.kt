package team.holder.android.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DiagnosticsLogTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun logFile(): File = File(tempFolder.newFolder("diagnostics"), "log.json")

    @Test
    fun read_isEmpty_whenNoFileExists() {
        assertTrue(DiagnosticsLog.read(File(tempFolder.newFolder("nope"), "log.json")).isEmpty())
    }

    @Test
    fun read_isEmpty_forCorruptFile() {
        val file = logFile()
        file.writeText("not json")

        assertTrue(DiagnosticsLog.read(file).isEmpty())
    }

    @Test
    fun append_roundTripsOneEntry() {
        val file = logFile()

        DiagnosticsLog.append(file, DiagnosticsEntry(1_800_000_000L, "Project push: pushed"))

        val entries = DiagnosticsLog.read(file)
        assertEquals(1, entries.size)
        assertEquals(1_800_000_000L, entries.single().timestampSeconds)
        assertEquals("Project push: pushed", entries.single().message)
    }

    @Test
    fun append_preservesOrderAcrossMultipleCalls() {
        val file = logFile()

        DiagnosticsLog.append(file, DiagnosticsEntry(1, "first"))
        DiagnosticsLog.append(file, DiagnosticsEntry(2, "second"))
        DiagnosticsLog.append(file, DiagnosticsEntry(3, "third"))

        assertEquals(listOf("first", "second", "third"), DiagnosticsLog.read(file).map { it.message })
    }

    @Test
    fun append_dropsOldestEntriesOnceOverTheCap() {
        val file = logFile()

        repeat(205) { index -> DiagnosticsLog.append(file, DiagnosticsEntry(index.toLong(), "entry-$index")) }

        val entries = DiagnosticsLog.read(file)
        assertEquals(200, entries.size)
        assertEquals("entry-5", entries.first().message)
        assertEquals("entry-204", entries.last().message)
    }
}
