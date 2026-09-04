package team.holder.android.git.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import team.holder.android.HolderNative
import team.holder.android.HolderProject

/** One [SnapshotGroup]'s result from a [BackupRestore.restoreAll] run. */
sealed interface RestoreOutcome {
    data class Success(val project: HolderProject) : RestoreOutcome
    data class Failure(val message: String) : RestoreOutcome
}

data class RestoreResult(val group: SnapshotGroup, val outcome: RestoreOutcome)

/**
 * Bulk-restores every group a snapshot file contained, one at a time -- see
 * [team.holder.android.ui.screens.RestoreBackupScreen], which reads the snapshot via
 * [SnapshotReader] and drives this. Same shape as
 * [team.holder.android.git.github.GitHubBackfill.backfill]: sequential, not parallel (each
 * call is an independent bulk-write, but there's no reason to race holder-core's own git/db
 * work against itself), and one group failing doesn't stop the rest.
 */
object BackupRestore {
    /** Matches BACKUP_RESTORE_DESIGN.md's chosen commit message -- plain, factual, and
     * honestly signals that this project's history begins here, not a continuation of
     * anything. */
    const val COMMIT_MESSAGE = "Restored from Android backup"

    suspend fun restoreAll(
        groups: List<SnapshotGroup>,
        onProgress: suspend (index: Int, total: Int, group: SnapshotGroup) -> Unit,
    ): List<RestoreResult> = groups.mapIndexed { index, group ->
        onProgress(index, groups.size, group)
        RestoreResult(group, restoreOne(group))
    }

    private suspend fun restoreOne(group: SnapshotGroup): RestoreOutcome = runCatching {
        withContext(Dispatchers.IO) {
            HolderNative.backupRestore(group.projectName, group.privacyMode, group.cardsJson, COMMIT_MESSAGE)
        }
    }.fold(
        onSuccess = { RestoreOutcome.Success(it) },
        onFailure = { RestoreOutcome.Failure(it.message ?: it::class.java.simpleName) },
    )
}
