package team.holder.android.git.backup

import java.io.File

/**
 * Guards against a real data-loss race between [SnapshotWorker]'s periodic, automatic
 * regeneration and [team.holder.android.ui.screens.RestoreBackupScreen] actually reading
 * whatever snapshot a genuine "lost phone" reinstall found waiting at [snapshotFile] before
 * this install's first launch.
 *
 * The race: `ensure_default_project` seeds a fresh "Home" welcome card, timestamped `now`, the
 * moment `HolderNative.initialize()` first runs on this install -- including a reinstall, since
 * `files/holder/` is excluded from Auto Backup and always starts empty. That alone is enough
 * for [SnapshotWriter.deviceMaxUpdatedAt] to look newer than
 * [team.holder.android.HolderSettings.lastSnapshotMaxUpdatedAt] -- which, unhelpfully, is
 * itself backed up and restored right along with everything else in `files/datastore/`, so on
 * a real restore it holds the *old* device's stale high-water mark, not 0. [SnapshotWorker]
 * reads that as "something changed, regenerate" on its very first tick -- which
 * BACKUP_RESTORE_IMPLEMENTATION_PLAN.md's own device testing measured firing "within seconds"
 * of being scheduled, with no way to hold it back further.
 *
 * That regeneration would silently overwrite the one file
 * [team.holder.android.ui.screens.RestoreBackupScreen] needs to read, with a snapshot of the
 * practically-empty new "Home" project -- permanently, before anyone ever sees what was
 * actually there. This would be low-risk if the automatic "we found a backup" offer reliably
 * beat it there, but [RestoreOffer]'s own doc comment already explains why it doesn't for the
 * realistic case: `restoreOfferShown` is *also* backed up, so any returning user -- the only
 * kind of user with a real snapshot worth protecting -- already has it as `true` from their
 * very first-ever launch, long before the phone was lost. The automatic offer silently declines
 * to fire, leaving the manual "Restore from backup" button in Settings as the real path -- one
 * a human has to notice and navigate to, almost certainly slower than a background worker's
 * first tick.
 *
 * The fix: arm a guard, checked only by [SnapshotWorker]'s automatic path, the moment a fresh
 * install ([armIfFreshInstallHasAnUnseenSnapshot]'s `dataDirExistedBeforeInit == false`) finds a
 * snapshot already sitting there -- something that can only mean Android's own restore
 * mechanism put it there, since nothing on a fresh install could have written it yet.
 * [team.holder.android.ui.screens.RestoreBackupScreen] disarms it the moment it actually reads
 * the file, on every path through that screen (automatic offer or manual entry, successful
 * restore or just a look), since that's the exact moment the risk this guards against is over.
 *
 * The marker itself lives under `files/local_state/`, not `files/holder/` (holder-core's own
 * directory -- not somewhere for Android-side bookkeeping to leave stray files) and not a
 * DataStore preference (backed up along with everything else, which is exactly the failure mode
 * being guarded against here). `files/local_state/` is excluded from Auto Backup for exactly
 * this reason (see backup_rules.xml/data_extraction_rules.xml): a fresh reinstall always starts
 * without this marker, correctly re-arming the guard regardless of what any backed-up flag
 * says.
 */
object SnapshotProtection {
    private fun markerFile(filesDir: File): File = File(filesDir, "local_state/pending_restore_snapshot")

    /** Call once, in [team.holder.android.MainActivity.onCreate], with
     * [dataDirExistedBeforeInit] captured strictly before this install's first-ever
     * `HolderNative.initialize()` call -- the fact that it didn't exist yet is what makes
     * "first ever launch" exact rather than approximate; every later launch always finds it
     * already there, so this is a no-op on every launch after the first. */
    fun armIfFreshInstallHasAnUnseenSnapshot(filesDir: File, snapshot: File, dataDirExistedBeforeInit: Boolean) {
        if (dataDirExistedBeforeInit) return
        if (!SnapshotReader.hasSnapshot(snapshot)) return
        markerFile(filesDir).apply { parentFile?.mkdirs() }.createNewFile()
    }

    /** Checked only by [SnapshotWorker]'s automatic, dirty-check-triggered regeneration --
     * deliberately NOT checked by [team.holder.android.ui.screens.BackupSettingsScreen]'s
     * "Prepare" button, an explicit manual request this guard was never meant to block. */
    fun isArmed(filesDir: File): Boolean = markerFile(filesDir).isFile

    /** Call once [team.holder.android.ui.screens.RestoreBackupScreen] has actually read
     * whatever was at [snapshotFile] -- see this object's doc comment for why that's the right
     * moment, on every path through that screen. A no-op if never armed. */
    fun disarm(filesDir: File) {
        markerFile(filesDir).delete()
    }
}
