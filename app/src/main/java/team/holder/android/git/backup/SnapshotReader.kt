package team.holder.android.git.backup

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.GZIPInputStream

/** One source project's worth of cards read back out of a snapshot file, ready to pass to
 * `HolderNative.backupRestore` -- see [team.holder.android.ui.screens.RestoreBackupScreen]. */
data class SnapshotGroup(
    val projectName: String,
    val privacyMode: String,
    val cardCount: Int,
    /** A JSON array string of the group's cards, each unchanged from what
     * `holder_backup_snapshot_page`/[SnapshotWriter] wrote -- the literal input
     * `holder_backup_restore` expects. */
    val cardsJson: String,
)

/** The inverse of [SnapshotWriter]: reads a gzip-JSONL snapshot file back into one
 * [SnapshotGroup] per original project. */
object SnapshotReader {
    /** True if [destination] (see [snapshotFile]) exists and looks non-empty -- the cheap
     * check to run before doing the real work of reading it. */
    fun hasSnapshot(destination: File): Boolean = destination.isFile && destination.length() > 0

    /**
     * Reads [source] (gzip JSONL -- the format [SnapshotWriter.writeLines] produces) and
     * groups its lines back into one [SnapshotGroup] per original `project_id`, in first-seen
     * order. Grouping works with no separate per-project header record because every card
     * already carries its own `project_id`/`project_name`/`privacy_mode` -- see
     * [SnapshotWriter]'s doc comment for why that's how the format was designed. A line's
     * `project_name`/`privacy_mode` are taken from the first card seen for that `project_id`;
     * later cards in the same group only contribute their own content, not a second identity
     * for the same project.
     *
     * A malformed or incomplete line (valid JSON but missing `project_id`, or not valid JSON
     * at all -- e.g. the very last line of a snapshot Auto Backup captured mid-write) is
     * skipped, not fatal: a snapshot is a best-effort recovery aid, and one bad line
     * shouldn't sink everything else in it.
     */
    fun readGroups(source: File): List<SnapshotGroup> {
        val order = mutableListOf<String>()
        val cardsByProject = mutableMapOf<String, MutableList<JSONObject>>()
        val nameByProject = mutableMapOf<String, String>()
        val privacyByProject = mutableMapOf<String, String>()

        GZIPInputStream(source.inputStream()).bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val card = runCatching { JSONObject(line) }.getOrNull() ?: continue
                val projectId = card.optString("project_id").takeIf { it.isNotBlank() } ?: continue

                val cards = cardsByProject.getOrPut(projectId) {
                    order.add(projectId)
                    nameByProject[projectId] = card.optString("project_name").ifBlank { "Restored project" }
                    privacyByProject[projectId] = card.optString("privacy_mode").ifBlank { "plain" }
                    mutableListOf()
                }
                cards.add(card)
            }
        }

        return order.map { projectId ->
            val cards = cardsByProject.getValue(projectId)
            SnapshotGroup(
                projectName = nameByProject.getValue(projectId),
                privacyMode = privacyByProject.getValue(projectId),
                cardCount = cards.size,
                cardsJson = JSONArray(cards).toString(),
            )
        }
    }
}
