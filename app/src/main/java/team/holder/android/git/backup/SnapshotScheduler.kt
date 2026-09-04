package team.holder.android.git.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

private const val UNIQUE_WORK_NAME = "backup-snapshot"

// WorkManager's periodic-work floor is 15 minutes (see HolderSettings' git-sync-interval
// comment); this doubles it. Regenerating only ever needs to be "reasonably fresh, if
// anything changed" -- see SnapshotWorker's own dirty-check -- not tight, since Android
// decides entirely on its own schedule when Auto Backup actually runs anyway (often far less
// often than this), and there's no way for this app to change that from in-process code; see
// the "Prepare Backup Now" section in SettingsScreen for the honest framing of what that
// button can and can't do.
private const val INTERVAL_MINUTES = 30L

/**
 * Schedules [SnapshotWorker] to run roughly every 30 minutes. Unlike
 * [team.holder.android.sync.GitSyncScheduler], always on -- no enable/disable toggle, no
 * interval setting: this is purely local disk I/O with no network and no meaningful battery
 * story to ask permission for, and [SnapshotWorker]'s own dirty-check already keeps a device
 * with nothing new to write from doing real work on every tick.
 *
 * [ExistingPeriodicWorkPolicy.KEEP], not `UPDATE` -- unlike GitSyncScheduler's interval, this
 * one never changes, so there's no reason to reset an already-scheduled periodic work's phase
 * every time this is called (once per app startup; see MainActivity).
 */
object SnapshotScheduler {
    fun reconcile(context: Context) {
        val request = PeriodicWorkRequestBuilder<SnapshotWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
