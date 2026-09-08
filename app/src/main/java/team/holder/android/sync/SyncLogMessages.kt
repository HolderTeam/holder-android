package team.holder.android.sync

import team.holder.android.GitSyncIfDueResult

/** Turns one project's [GitSyncIfDueResult] into 0-2 Diagnostics log lines -- one per direction
 * actually attempted, since [team.holder.android.HolderNative.gitSyncIfDue] is called far more
 * often than it actually syncs (it only does anything once the configured interval has
 * elapsed), and a line for "nothing was due" every 15 minutes would drown out everything real.
 * Pure and Context-free so it's unit-testable without a device -- see
 * [team.holder.android.git.backup.SnapshotReader]'s doc comment for this codebase's usual split
 * between pure logic and device-only I/O. */
fun syncLogMessages(projectName: String, result: GitSyncIfDueResult): List<String> {
    val messages = mutableListOf<String>()
    if (result.pushAttempted) {
        messages += "$projectName push: ${result.pushStatus}" +
            (result.pushError?.let { " ($it)" } ?: "")
    }
    if (result.pullAttempted) {
        val conflicts = if (result.pullConflictsResolved > 0) {
            ", resolved ${result.pullConflictsResolved} conflict(s)"
        } else {
            ""
        }
        messages += "$projectName pull: ${result.pullStatus}$conflicts" +
            (result.pullError?.let { " ($it)" } ?: "")
    }
    return messages
}
