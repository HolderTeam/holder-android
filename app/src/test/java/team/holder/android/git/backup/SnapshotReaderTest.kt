package team.holder.android.git.backup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.GZIPOutputStream

class SnapshotReaderTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun gzipJsonl(lines: List<String>): File {
        val file = File(tempFolder.newFolder("snapshot"), "snapshot.jsonl.gz")
        GZIPOutputStream(file.outputStream()).use { gzip ->
            lines.forEach { gzip.write((it + "\n").toByteArray(Charsets.UTF_8)) }
        }
        return file
    }

    private fun card(projectId: String, projectName: String, privacyMode: String, cardId: String) =
        JSONObject().apply {
            put("card_id", cardId)
            put("project_id", projectId)
            put("project_name", projectName)
            put("privacy_mode", privacyMode)
            put("title", "Title $cardId")
            put("body", "Body $cardId")
            put("created_at", 1)
            put("updated_at", 1)
        }.toString()

    @Test
    fun hasSnapshot_isFalse_whenNoFileExists() {
        val file = File(tempFolder.newFolder("nope"), "snapshot.jsonl.gz")
        assertFalse(SnapshotReader.hasSnapshot(file))
    }

    @Test
    fun hasSnapshot_isFalse_forAnEmptyFile() {
        val file = File(tempFolder.newFolder("empty"), "snapshot.jsonl.gz")
        file.createNewFile()
        assertFalse(SnapshotReader.hasSnapshot(file))
    }

    @Test
    fun hasSnapshot_isTrue_forANonEmptyFile() {
        val file = gzipJsonl(listOf(card("p1", "House", "plain", "c1")))
        assertTrue(SnapshotReader.hasSnapshot(file))
    }

    @Test
    fun readGroups_groupsCardsByProjectId_inFirstSeenOrder() {
        val file = gzipJsonl(
            listOf(
                card("p2", "Car", "plain", "c1"),
                card("p1", "House", "encrypted_git", "c2"),
                card("p2", "Car", "plain", "c3"),
                card("p1", "House", "encrypted_git", "c4"),
            ),
        )

        val groups = SnapshotReader.readGroups(file)

        assertEquals(listOf("Car", "House"), groups.map { it.projectName })
        assertEquals(listOf("plain", "encrypted_git"), groups.map { it.privacyMode })
        assertEquals(listOf(2, 2), groups.map { it.cardCount })
    }

    @Test
    fun readGroups_cardsJson_isAJsonArrayOfTheOriginalCardObjects_inOrder() {
        val file = gzipJsonl(
            listOf(
                card("p1", "House", "plain", "c1"),
                card("p1", "House", "plain", "c2"),
            ),
        )

        val group = SnapshotReader.readGroups(file).single()
        val cardsJson = JSONArray(group.cardsJson)

        assertEquals(2, cardsJson.length())
        assertEquals("c1", cardsJson.getJSONObject(0).getString("card_id"))
        assertEquals("c2", cardsJson.getJSONObject(1).getString("card_id"))
        assertEquals("Body c1", cardsJson.getJSONObject(0).getString("body"))
    }

    @Test
    fun readGroups_skipsBlankAndMalformedLines_withoutFailingTheWholeRead() {
        val file = File(tempFolder.newFolder("snapshot"), "snapshot.jsonl.gz")
        GZIPOutputStream(file.outputStream()).use { gzip ->
            gzip.write((card("p1", "House", "plain", "c1") + "\n").toByteArray(Charsets.UTF_8))
            gzip.write("\n".toByteArray(Charsets.UTF_8))
            gzip.write("not json at all, e.g. a truncated last line\n".toByteArray(Charsets.UTF_8))
            gzip.write("""{"card_id":"c2","title":"no project_id"}""".plus("\n").toByteArray(Charsets.UTF_8))
            gzip.write((card("p1", "House", "plain", "c3") + "\n").toByteArray(Charsets.UTF_8))
        }

        val group = SnapshotReader.readGroups(file).single()

        assertEquals(2, group.cardCount)
        assertEquals(
            listOf("c1", "c3"),
            JSONArray(group.cardsJson).let { arr -> List(arr.length()) { arr.getJSONObject(it).getString("card_id") } },
        )
    }

    @Test
    fun readGroups_returnsEmptyList_forASnapshotWithNoCards() {
        val file = gzipJsonl(emptyList())
        assertTrue(SnapshotReader.readGroups(file).isEmpty())
    }

    @Test
    fun readGroups_returnsEmptyList_ratherThanThrowing_whenNoFileExistsAtAll() {
        // The common real case: the manual "Restore from backup" button has no guard of its
        // own (see RestoreBackupScreen), so most taps on it happen on a device that's never had
        // anything to restore. This used to surface as a raw FileNotFoundException instead of
        // the same "nothing here" result an empty snapshot already produces above.
        val file = File(tempFolder.newFolder("nope"), "snapshot.jsonl.gz")
        assertTrue(SnapshotReader.readGroups(file).isEmpty())
    }

    @Test
    fun readGroups_defaultsProjectNameAndPrivacyMode_whenMissing() {
        val file = File(tempFolder.newFolder("snapshot"), "snapshot.jsonl.gz")
        val bareCard = JSONObject().apply {
            put("card_id", "c1")
            put("project_id", "p1")
            put("title", "t")
            put("body", "b")
            put("created_at", 1)
            put("updated_at", 1)
        }.toString()
        GZIPOutputStream(file.outputStream()).use { gzip ->
            gzip.write((bareCard + "\n").toByteArray(Charsets.UTF_8))
        }

        val group = SnapshotReader.readGroups(file).single()

        assertEquals("Restored project", group.projectName)
        assertEquals("plain", group.privacyMode)
    }
}
