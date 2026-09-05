package team.holder.android.git.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import team.holder.android.HolderNative
import team.holder.android.HolderSettings
import java.io.File

/**
 * Periodic background regeneration of the backup snapshot -- see
 * BACKUP_RESTORE_IMPLEMENTATION_PLAN.md steps 12-13. Unlike [team.holder.android.sync.GitSyncWorker],
 * always scheduled with no user opt-in toggle (see [SnapshotScheduler]): this is purely local
 * disk I/O, no network, nothing worth asking permission for the way background git sync's
 * battery/data cost is.
 *
 * Skips the actual write entirely if nothing has changed since the last successful
 * regeneration ([SnapshotWriter.deviceMaxUpdatedAt] compared against
 * [HolderSettings.lastSnapshotMaxUpdatedAt]) -- "dirty-tracking" derived from the cards
 * themselves rather than a flag threaded through every mutating call site in the app, so a
 * device that hasn't touched any cards since the last tick does no more work than a handful of
 * cheap 1-card page reads.
 *
 * Runs in the app's own process, same as GitSyncWorker -- initializes HolderNative itself,
 * since WorkManager may start it without any Activity having run first (e.g. after the process
 * was killed).
 */
class SnapshotWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val result = runCatching {
            HolderNative.initialize(
                context = applicationContext,
                dataDir = File(applicationContext.filesDir, "holder"),
                schemaSql = applicationContext.assets.open("schema.sql").bufferedReader().use { it.readText() },
                welcomeContent = applicationContext.assets.open("WELCOME.md").bufferedReader().use { it.readText() },
            )

            val currentMax = SnapshotWriter.deviceMaxUpdatedAt()
            val lastMax = HolderSettings.lastSnapshotMaxUpdatedAt(applicationContext).first()
            // SnapshotProtection.isArmed guards against a real data-loss race: right after a
            // genuine "lost phone" reinstall, ensure_default_project's fresh "Home" card is
            // enough on its own to make currentMax look newer than lastMax (itself restored
            // from the OLD device's stale high-water mark, since HolderSettings' DataStore
            // isn't excluded from backup) -- without this check, this worker's very first tick
            // would overwrite the actual restorable snapshot before anyone reads it. See
            // SnapshotProtection's doc comment for the full reasoning.
            if (currentMax != null && currentMax > lastMax && !SnapshotProtection.isArmed(applicationContext.filesDir)) {
                SnapshotWriter.regenerateAndRecordFreshness(applicationContext)
            }
        }

        if (result.isSuccess) Result.success() else Result.retry()
    }
}
