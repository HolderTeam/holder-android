package team.holder.android.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers [nextConsecutiveFailureCount] and [shouldNotifyReliabilityFailure] in isolation --
 * GitSyncWorker/SnapshotWorker's own `doWork` needs a real device (HolderNative, WorkManager) and
 * isn't unit-tested, matching this codebase's usual split (see `SnapshotWorkerTest`'s doc
 * comment). This is the one part of sync_reliability.md's feature that's pure logic and worth
 * pinning down exactly: a later refactor could easily flip `==` to `>=` (repeated renotification)
 * or forget the "not attempted" case (a false failure streak on a device with nothing to sync). */
class ReliabilityFailureTrackingTest {
    @Test
    fun untouched_whenNothingWasAttempted() {
        // A device with no configured git remote, or a SnapshotWorker tick where
        // shouldRegenerate was false -- neither success nor failure, so the streak must not move.
        assertNull(nextConsecutiveFailureCount(currentCount = 2, attempted = false, succeeded = true))
        assertNull(nextConsecutiveFailureCount(currentCount = 2, attempted = false, succeeded = false))
    }

    @Test
    fun resetsToZero_onSuccess() {
        assertEquals(0, nextConsecutiveFailureCount(currentCount = 5, attempted = true, succeeded = true))
    }

    @Test
    fun increments_onFailure() {
        assertEquals(1, nextConsecutiveFailureCount(currentCount = 0, attempted = true, succeeded = false))
        assertEquals(3, nextConsecutiveFailureCount(currentCount = 2, attempted = true, succeeded = false))
    }

    @Test
    fun doesNotNotify_belowThreshold() {
        assertFalse(shouldNotifyReliabilityFailure(newCount = RELIABILITY_FAILURE_THRESHOLD - 1, succeeded = false))
    }

    @Test
    fun notifies_exactlyAtThreshold() {
        assertTrue(shouldNotifyReliabilityFailure(newCount = RELIABILITY_FAILURE_THRESHOLD, succeeded = false))
    }

    @Test
    fun doesNotRenotify_pastThreshold() {
        // The actual regression this test exists to catch: `==` against the threshold, not `>=`
        // -- a fourth, fifth, sixth... consecutive failure must not post a second notification.
        assertFalse(shouldNotifyReliabilityFailure(newCount = RELIABILITY_FAILURE_THRESHOLD + 1, succeeded = false))
        assertFalse(shouldNotifyReliabilityFailure(newCount = RELIABILITY_FAILURE_THRESHOLD + 5, succeeded = false))
    }

    @Test
    fun neverNotifies_onSuccess_regardlessOfCount() {
        // Can't actually happen via nextConsecutiveFailureCount (a success always yields 0), but
        // shouldNotifyReliabilityFailure is tested here as its own unit, independent of that.
        assertFalse(shouldNotifyReliabilityFailure(newCount = RELIABILITY_FAILURE_THRESHOLD, succeeded = true))
    }

    @Test
    fun neverNotifies_whenUntouched() {
        assertFalse(shouldNotifyReliabilityFailure(newCount = null, succeeded = false))
    }
}
