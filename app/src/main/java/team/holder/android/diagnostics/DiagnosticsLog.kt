package team.holder.android.diagnostics

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A rolling window of what this device's own background work has done -- sync results today,
 * more later -- for Settings > Diagnostics. A product-facing activity history, not a durable
 * audit trail or a programmer's console: entries are capped at [MAX_ENTRIES], oldest dropped
 * first, and nothing here is more important than the Git history it's describing. */
data class DiagnosticsEntry(val timestampSeconds: Long, val message: String)

/** filesDir/diagnostics/log.json -- a sibling to backup/snapshot.jsonl.gz, outside the excluded
 * files/holder/ directory so it isn't swept up by Android Auto Backup either (see
 * [team.holder.android.git.backup.snapshotFile]'s doc comment for that exclusion boundary; this
 * file doesn't need to survive a restore the way the snapshot does). */
fun diagnosticsLogFile(context: Context): File = File(context.filesDir, "diagnostics/log.json")

private const val MAX_ENTRIES = 200

/** Plain, uncompressed JSON -- unlike [team.holder.android.git.backup.SnapshotWriter]'s JSONL,
 * this is small enough (a few hundred short lines at most) that there's no reason to stream or
 * compress it; every read/write is a single small file. */
object DiagnosticsLog {
    private val lock = Any()

    /** Oldest first. Returns an empty list, not an error, for a missing or corrupt file -- a
     * best-effort log with nothing recorded (or a torn write from a killed process) should look
     * like an empty log, not a crash. */
    fun read(source: File): List<DiagnosticsEntry> {
        if (!source.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(source.readText())
            List(array.length()) { index ->
                val entry = array.getJSONObject(index)
                DiagnosticsEntry(entry.getLong("timestamp"), entry.getString("message"))
            }
        }.getOrDefault(emptyList())
    }

    /** Appends one entry, trimming the oldest once past [MAX_ENTRIES]. Guarded by an in-process
     * lock against two background workers racing on the same file (e.g. an overlapping
     * WorkManager run); not a cross-process guarantee, which this single-process app never
     * needs. */
    fun append(destination: File, entry: DiagnosticsEntry) {
        synchronized(lock) {
            val entries = (read(destination) + entry).takeLast(MAX_ENTRIES)
            destination.parentFile?.mkdirs()
            val array = JSONArray()
            entries.forEach { e ->
                array.put(JSONObject().put("timestamp", e.timestampSeconds).put("message", e.message))
            }
            destination.writeText(array.toString())
        }
    }
}
