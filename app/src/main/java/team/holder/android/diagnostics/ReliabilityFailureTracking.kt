package team.holder.android.diagnostics

/**
 * Pure core of GitSyncWorker/SnapshotWorker's consecutive-failure streak (see
 * sync_reliability.md) -- pulled out for a fast, deterministic unit test of its own, the same
 * reasoning SnapshotWorker's own `shouldRegenerate` already gets for its one load-bearing
 * condition. [attempted] is only meaningful when this tick actually did something: a tick with
 * nothing to attempt (no configured git remote, or SnapshotWorker's own `shouldRegenerate` false)
 * must never touch the streak, so this returns null for that case rather than a count -- callers
 * should leave the stored count untouched when this returns null, not write it back unchanged.
 */
fun nextConsecutiveFailureCount(currentCount: Int, attempted: Boolean, succeeded: Boolean): Int? {
    if (!attempted) return null
    return if (succeeded) 0 else currentCount + 1
}

/** True exactly once per failure streak -- `==` against the threshold inside
 * [nextConsecutiveFailureCount]'s result, not `>=`, so this doesn't refire on every tick past the
 * threshold until a success resets the streak back to 0. */
fun shouldNotifyReliabilityFailure(newCount: Int?, succeeded: Boolean): Boolean =
    newCount != null && !succeeded && newCount == RELIABILITY_FAILURE_THRESHOLD
