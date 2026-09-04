package team.holder.android.git.backup

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import team.holder.android.HolderSettings

/**
 * The one-time "we found a backup snapshot, restore it?" offer -- the automatic half of
 * BACKUP_RESTORE_IMPLEMENTATION_PLAN.md step 9's detection (the "post-reinstall" case: Auto
 * Backup restored `files/backup/snapshot.jsonl.gz` before this install's first launch, while
 * `files/holder/` -- excluded from backup -- starts out empty). The "deliberate" case (a
 * manual "Restore from backup" entry point in Settings) is separate and unconditional: it
 * doesn't check [HolderSettings.restoreOfferShown] at all, since a user explicitly asking to
 * restore isn't the one-time surprise this guards against firing more than once.
 *
 * `HolderNative.initialize()` always creates a default "Home" welcome project on a project-
 * less device (see `ensure_default_project`) before this offer ever gets a chance to run, so
 * unlike [team.holder.android.git.github.GitHubBackfill] this can't gate on "zero local
 * projects" -- that's never actually true by the time a caller can check. The flag is the
 * only signal: a device that has already been offered this once, ever, won't be offered again
 * even if a new snapshot file later appears (e.g. from a second Auto Backup run) -- accepted,
 * matching [HolderSettings.githubBackfillOfferShown]'s own precedent, and the manual entry
 * point remains available regardless.
 */
object RestoreOffer {
    /** Call once at startup (see MainActivity). Returns true if this device has a snapshot
     * file to offer restoring AND has never been asked before -- marks the flag as a side
     * effect of calling this, unconditionally, so it can never re-fire. */
    suspend fun checkAndMarkOfferedOnce(context: Context): Boolean {
        val appContext = context.applicationContext
        if (HolderSettings.restoreOfferShown(appContext).first()) return false
        HolderSettings.setRestoreOfferShown(appContext, true)
        return withContext(Dispatchers.IO) { SnapshotReader.hasSnapshot(snapshotFile(appContext)) }
    }
}
