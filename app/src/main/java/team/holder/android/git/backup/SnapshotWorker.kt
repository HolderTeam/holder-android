package team.holder.android.git.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import team.holder.android.HolderNative
import team.holder.android.HolderSettings
import team.holder.android.diagnostics.DiagnosticsEntry
import team.holder.android.diagnostics.DiagnosticsLog
import team.holder.android.diagnostics.ReliabilityFailureKind
import team.holder.android.diagnostics.ReliabilityNotifier
import team.holder.android.diagnostics.diagnosticsLogFile
import team.holder.android.diagnostics.nextConsecutiveFailureCount
import team.holder.android.diagnostics.shouldNotifyReliabilityFailure
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
 *
 * Records a Settings > Diagnostics line for every regeneration this runs, and for any failure
 * that stops one from completing -- this is arguably the single most important thing
 * Diagnostics logs at all: a broken backup safety net that fails silently is only ever
 * discovered at restore time, when it's too late to do anything about it.
 *
 * Also tracks a consecutive-failure streak (see [HolderSettings.snapshotConsecutiveFailures])
 * and posts one [ReliabilityNotifier] notification once [shouldNotifyReliabilityFailure] says so,
 * reset on the next successfully-attempted regeneration -- see sync_reliability.md. Unlike
 * GitSyncWorker, [result]'s own success/failure is already the right signal here (a snapshot
 * write either succeeds or throws, no structured failure status to unpack); the only nuance is
 * that a tick where [shouldRegenerate] is false is a no-op, not a success, so it must not reset
 * an accumulating streak on its own.
 */
class SnapshotWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val logFile = diagnosticsLogFile(applicationContext)
        // True only once shouldRegenerate has actually said yes this tick -- a plain no-op
        // (nothing changed since the last snapshot) never sets this.
        var writeAttemptedThisTick = false
        val result = runCatching {
            HolderNative.initialize(
                context = applicationContext,
                dataDir = File(applicationContext.filesDir, "holder"),
                schemaSql = applicationContext.assets.open("schema.sql").bufferedReader().use { it.readText() },
                welcomeContent = applicationContext.assets.open("WELCOME.md").bufferedReader().use { it.readText() },
            )

            val currentMax = SnapshotWriter.deviceMaxUpdatedAt()
            val lastMax = HolderSettings.lastSnapshotMaxUpdatedAt(applicationContext).first()
            val armed = SnapshotProtection.isArmed(applicationContext.filesDir)
            if (shouldRegenerate(currentMax, lastMax, armed)) {
                writeAttemptedThisTick = true
                val writeResult = SnapshotWriter.regenerateAndRecordFreshness(applicationContext)
                DiagnosticsLog.append(
                    logFile,
                    DiagnosticsEntry(System.currentTimeMillis() / 1000, snapshotLogMessage(writeResult)),
                )
            }
        }

        // This is the one failure mode Diagnostics exists to catch: the backup safety net
        // breaking silently, discovered only at restore time -- by then it's too late to do
        // anything but note that this run never even reached regenerateAndRecordFreshness.
        result.onFailure { error ->
            DiagnosticsLog.append(
                logFile,
                DiagnosticsEntry(System.currentTimeMillis() / 1000, "Backup snapshot failed: ${error.message}"),
            )
        }

        // null (untouched) when this tick carries no real signal: shouldRegenerate was false and
        // nothing else went wrong, which is a no-op, not evidence anything works. See
        // ReliabilityFailureTracking.kt for the pure, unit-tested logic itself.
        val newFailureCount = nextConsecutiveFailureCount(
            currentCount = HolderSettings.snapshotConsecutiveFailures(applicationContext).first(),
            attempted = writeAttemptedThisTick || result.isFailure,
            succeeded = result.isSuccess,
        )
        if (newFailureCount != null) {
            HolderSettings.setSnapshotConsecutiveFailures(applicationContext, newFailureCount)
            if (shouldNotifyReliabilityFailure(newFailureCount, succeeded = result.isSuccess)) {
                ReliabilityNotifier.notify(applicationContext, ReliabilityFailureKind.SNAPSHOT)
            }
        }

        if (result.isSuccess) Result.success() else Result.retry()
    }

    companion object {
        /**
         * Pulled out of [doWork] as a pure function purely so it has a fast, deterministic unit
         * test of its own ([SnapshotWorkerTest]) -- [armed] in particular guards against a real
         * data-loss race (see [SnapshotProtection]'s doc comment), and that guard is exactly the
         * kind of one-line condition a later refactor could silently drop without this test
         * catching it. The rest of [doWork] (HolderNative.initialize, the actual file I/O) needs
         * a real device and isn't unit-tested, matching this codebase's usual split (see
         * `GitHubBackfillTest`'s doc comment).
         */
        fun shouldRegenerate(currentMax: Long?, lastMax: Long, armed: Boolean): Boolean =
            currentMax != null && currentMax > lastMax && !armed

        /** The Diagnostics line for one successful regeneration -- [SnapshotWriteResult.truncated]
         * is the important part: it means some of the device's cards did NOT make it into the
         * snapshot, so the backup this run produced is incomplete, without that ever surfacing
         * as an error (writeLines stops cleanly at the budget, it doesn't fail). */
        fun snapshotLogMessage(result: SnapshotWriteResult): String {
            val truncatedNote = if (result.truncated) " (truncated -- some cards did not fit)" else ""
            return "Backup snapshot: ${result.cardCount} card(s), ${result.compressedBytes / 1024} KB$truncatedNote"
        }
    }
}
