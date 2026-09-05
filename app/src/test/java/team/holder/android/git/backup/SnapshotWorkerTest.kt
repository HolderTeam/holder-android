package team.holder.android.git.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers [SnapshotWorker.shouldRegenerate] in isolation -- the rest of `doWork` needs a real
 * device (HolderNative, file I/O) and isn't unit-testable without more infra, matching this
 * codebase's usual split (see `GitHubBackfillTest`'s doc comment). This one's worth its own test
 * specifically because of `armed`: see SnapshotProtection's doc comment for the real data-loss
 * race it guards against, and this test is what stops a later refactor from silently dropping
 * that check while "simplifying" this condition. */
class SnapshotWorkerTest {
    @Test
    fun regenerates_whenSomethingChangedAndNotArmed() {
        assertTrue(SnapshotWorker.shouldRegenerate(currentMax = 100, lastMax = 50, armed = false))
    }

    @Test
    fun neverRegenerates_whenArmed_regardlessOfHowDirtyItLooks() {
        // The actual regression this test exists to catch: a device fresh out of a real
        // restore always looks "dirty" (see SnapshotProtection's doc comment for why), and
        // that must never be enough on its own to overwrite an unread inbound snapshot.
        assertFalse(SnapshotWorker.shouldRegenerate(currentMax = 100, lastMax = 50, armed = true))
        assertFalse(SnapshotWorker.shouldRegenerate(currentMax = Long.MAX_VALUE, lastMax = 0, armed = true))
    }

    @Test
    fun doesNotRegenerate_whenNothingHasChanged() {
        assertFalse(SnapshotWorker.shouldRegenerate(currentMax = 50, lastMax = 50, armed = false))
        assertFalse(SnapshotWorker.shouldRegenerate(currentMax = 40, lastMax = 50, armed = false))
    }

    @Test
    fun doesNotRegenerate_whenTheDeviceHasNoCardsAtAll() {
        // deviceMaxUpdatedAt() returns null when there are no cards anywhere to measure.
        assertFalse(SnapshotWorker.shouldRegenerate(currentMax = null, lastMax = 0, armed = false))
    }
}
