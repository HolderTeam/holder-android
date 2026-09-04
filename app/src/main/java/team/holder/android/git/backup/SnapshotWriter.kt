package team.holder.android.git.backup

import android.content.Context
import org.json.JSONObject
import team.holder.android.HolderNative
import team.holder.android.HolderSettings
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.GZIPOutputStream

/** 20 MiB: Android's Auto Backup app-data quota (25 MiB) minus 5 MiB reserved for
 * HolderSettings' own small preferences and everything else that quota has to share --
 * see backup-spike-2-snapshot.txt. */
const val SNAPSHOT_BUDGET_BYTES: Long = 20L * 1024 * 1024

/** Where the rolling snapshot file lives -- filesDir/backup/, a sibling to (deliberately not
 * inside) the files/holder/ directory data_extraction_rules.xml/backup_rules.xml exclude from
 * Auto Backup. Both rule files default to backing up everything under filesDir *not*
 * explicitly excluded, so this path needs no `<include>` of its own -- it only has to stay out
 * of that one exclusion. [team.holder.android.git.backup.SnapshotReader] and everything that
 * reads a snapshot back (RestoreOffer, RestoreBackupScreen) looks for the file at this same
 * path. */
fun snapshotFile(context: Context): File = File(context.filesDir, "backup/snapshot.jsonl.gz")

/** Comfortably more than a gzip trailer (CRC32 + ISIZE, 8 bytes) plus whatever [GZIPOutputStream]'s
 * own [GZIPOutputStream.finish] emits closing out the deflate stream after the last synced
 * flush -- see [SnapshotWriter.writeLines]'s doc comment for why this exists at all. Not meant
 * to be exact, just larger than that overhead ever plausibly is. */
private const val TRAILER_RESERVE_BYTES: Long = 64

/** Result of a [SnapshotWriter.writeLines] (or [SnapshotWriter.writeDeviceSnapshot]) run. */
data class SnapshotWriteResult(
    val cardCount: Int,
    val compressedBytes: Long,
    /** True if writing stopped because the budget was reached before there were no more
     * cards left to write -- i.e. some of the device's cards didn't make it into this
     * snapshot. */
    val truncated: Boolean,
)

/**
 * Writes the device's most-recently-updated cards as gzip-compressed JSONL into a single
 * rolling snapshot file -- the purpose-built export `backup-spike-2-snapshot.txt` measured
 * capacity for. Android's Auto Backup then picks the file up on its own schedule as an
 * ordinary app file (see backup_rules.xml / data_extraction_rules.xml for where it has to
 * live for that to happen).
 */
object SnapshotWriter {
    /**
     * The real entry point: every local project's cards, most-recently-updated first within
     * each project, in [HolderNative.listProjects] order across projects -- see [deviceCards]'s
     * doc comment for why cross-project recency isn't attempted. Not itself unit-tested (it
     * needs the native library and a real `HolderNative.initialize()`) -- see [writeLines] for
     * the tested core, matching this codebase's usual split between pure-logic and device-only
     * coverage (e.g. `GitHubBackfillTest`'s doc comment).
     */
    fun writeDeviceSnapshot(
        destination: File,
        budgetBytes: Long = SNAPSHOT_BUDGET_BYTES,
        pageSize: Int = 500,
    ): SnapshotWriteResult =
        writeLines(deviceCards(pageSize).map { it.toString() }, destination, budgetBytes)

    /** The largest `updated_at` among any local project's cards right now, or null if there
     * are no cards anywhere -- a cheap one-card-page-per-project check via
     * [HolderNative.backupSnapshotPage] (`limit = 1`, not a full pull), used to decide whether
     * a scheduled regeneration ([team.holder.android.git.backup.SnapshotWorker]) has anything
     * new to write, and recorded by [regenerateAndRecordFreshness] after writing. Any card
     * mutation that matters for a snapshot -- title/body, links, milestones, trash/restore --
     * already bumps the card's own `updated_at` in holder-core itself (see `CardStore.cpp`'s
     * `touch_updated` calls), so this needs no separate dirty-flag threaded through every
     * mutating call site in the app; it's derived from the same source of truth the snapshot
     * itself reads. */
    fun deviceMaxUpdatedAt(): Long? =
        HolderNative.listProjects()
            .mapNotNull { project ->
                HolderNative.backupSnapshotPage(project.projectId, 0, null, limit = 1)
                    .cards.firstOrNull()?.getLong("updated_at")
            }
            .maxOrNull()

    /** Regenerates the on-disk snapshot unconditionally (see [writeDeviceSnapshot]) and
     * records [deviceMaxUpdatedAt] into [team.holder.android.HolderSettings], so
     * [team.holder.android.git.backup.SnapshotWorker]'s own freshness check doesn't see
     * stale-looking bookkeeping and redundantly regenerate again on its very next tick. Used
     * directly by Settings' "Prepare Backup Now" button (deliberately unconditional -- a
     * manual request always does the work, dirty or not), and by [SnapshotWorker] once *it*
     * has already decided, via [deviceMaxUpdatedAt], that something changed. */
    suspend fun regenerateAndRecordFreshness(context: Context): SnapshotWriteResult {
        val result = writeDeviceSnapshot(snapshotFile(context))
        deviceMaxUpdatedAt()?.let { HolderSettings.setLastSnapshotMaxUpdatedAt(context, it) }
        return result
    }

    /**
     * The tested core: streams [lines] (each the literal per-card JSON object
     * `holder_backup_snapshot_page` returns, already `.toString()`'d -- card_id, project_id,
     * project_name, privacy_mode, title, body, created_at, updated_at, links, milestones,
     * unchanged, so restore can group cards back into projects without a separate per-project
     * header record) one per line into a gzip stream at [destination], stopping *before*
     * writing whichever line would push the compressed output over [budgetBytes] -- checked
     * after every line via a sync-flushing [GZIPOutputStream], not some fixed batch size, so a
     * handful of huge cards can't overshoot the budget just because they arrived in the same
     * "batch" as many small ones. A card written right up against the edge of the budget can
     * still land the file a little under [budgetBytes] rather than exactly at it (the next
     * card is what proves we're over, and it was never written) -- Auto Backup's own 5 MiB
     * margin above [SNAPSHOT_BUDGET_BYTES] absorbs that slack, this never needs to be exact.
     * Never buffers-then-recompresses the whole output to check its size -- that would make
     * writing a large snapshot quadratic in card count (the mistake avoided when the spike
     * scripts this reimplements were first written, see backup-spike-2-snapshot.txt).
     *
     * Writes to a temp file first, swapping it into place only once complete, so a crash or
     * low-storage failure mid-write can never leave a half-written (invalid gzip) file where
     * Auto Backup, or a later restore, would look for a valid one. Leaves any existing
     * [destination] untouched if [lines] turns out to be empty, rather than deleting a good
     * previous snapshot just because this run found nothing to write.
     */
    fun writeLines(lines: Sequence<String>, destination: File, budgetBytes: Long): SnapshotWriteResult {
        destination.parentFile?.mkdirs()
        val tempFile = File(destination.parentFile, destination.name + ".tmp")

        var cardCount = 0
        var truncated = false
        val counting = CountingOutputStream(tempFile.outputStream())
        GZIPOutputStream(counting, /* syncFlush = */ true).use { gzip ->
            val iterator = lines.iterator()
            while (iterator.hasNext()) {
                // Checked against budgetBytes - TRAILER_RESERVE_BYTES, not budgetBytes itself:
                // counting.bytesWritten reflects the sync-flushed stream so far, but
                // GZIPOutputStream.close() (below, via .use{}) still has to append the gzip
                // trailer (CRC32 + ISIZE) after the last line is written, which this check
                // can't see coming. Reserving a little headroom keeps the *final* file under
                // budgetBytes, not just the last pre-trailer measurement.
                if (counting.bytesWritten >= budgetBytes - TRAILER_RESERVE_BYTES) {
                    truncated = true
                    break
                }
                gzip.write((iterator.next() + "\n").toByteArray(Charsets.UTF_8))
                gzip.flush()
                cardCount++
            }
        }

        if (cardCount == 0) {
            tempFile.delete()
            return SnapshotWriteResult(cardCount = 0, compressedBytes = 0, truncated = false)
        }

        destination.delete()
        tempFile.renameTo(destination)
        return SnapshotWriteResult(
            cardCount = cardCount,
            compressedBytes = destination.length(),
            truncated = truncated,
        )
    }

    /**
     * Every local project's cards, most-recently-updated first within each project, lazily
     * paginated from [HolderNative.backupSnapshotPage] [pageSize] cards at a time so
     * [writeLines] never needs more than one page's worth of cards in memory at once. Projects
     * are visited in [HolderNative.listProjects] order -- true cross-project recency
     * (interleaving by updated_at across every project on the device) isn't attempted: a
     * typical Holder user has one primary project (see holder-core's `ensure_default_project`),
     * so one big project starving a smaller, later one of budget is a real but low-value edge
     * case for this deliberately modest "recover recent cards" feature to solve up front --
     * see BACKUP_RESTORE_DESIGN.md.
     */
    private fun deviceCards(pageSize: Int): Sequence<JSONObject> = sequence {
        for (project in HolderNative.listProjects()) {
            var cursorUpdatedAt = 0L
            var cursorCardId: String? = null
            while (true) {
                val page = HolderNative.backupSnapshotPage(
                    project.projectId,
                    cursorUpdatedAt,
                    cursorCardId,
                    pageSize,
                )
                yieldAll(page.cards)
                val next = page.nextCursor ?: break
                cursorUpdatedAt = next.updatedAt
                cursorCardId = next.cardId
            }
        }
    }
}

/** Counts bytes actually written to [out] -- used to check the snapshot's real compressed
 * size against the budget without a separate stat()/length() call, which a still-open
 * [java.io.FileOutputStream] isn't guaranteed to reflect accurately mid-write. */
private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
    var bytesWritten = 0L
        private set

    override fun write(b: Int) {
        out.write(b)
        bytesWritten++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        bytesWritten += len
    }
}
