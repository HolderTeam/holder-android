package team.holder.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.holder.android.GitSyncIfDueResult

private fun result(
    pullAttempted: Boolean = false,
    pullStatus: String? = null,
    pullError: String? = null,
    pullConflictsResolved: Int = 0,
    pushAttempted: Boolean = false,
    pushStatus: String? = null,
    pushError: String? = null,
) = GitSyncIfDueResult(
    pullAttempted = pullAttempted,
    pullStatus = pullStatus,
    pullError = pullError,
    pullConflictsResolved = pullConflictsResolved,
    pushAttempted = pushAttempted,
    pushStatus = pushStatus,
    pushError = pushError,
)

class SyncLogMessagesTest {
    @Test
    fun neitherAttempted_producesNoLines() {
        assertTrue(syncLogMessages("Home", result()).isEmpty())
    }

    @Test
    fun successfulPush_producesOneLineWithoutParentheses() {
        val messages = syncLogMessages("Home", result(pushAttempted = true, pushStatus = "pushed"))

        assertEquals(listOf("Home push: pushed"), messages)
    }

    @Test
    fun failedPush_includesTheErrorMessage() {
        val messages = syncLogMessages(
            "Home",
            result(pushAttempted = true, pushStatus = "auth_failed", pushError = "Permission denied"),
        )

        assertEquals(listOf("Home push: auth_failed (Permission denied)"), messages)
    }

    @Test
    fun successfulPull_producesOneLine() {
        val messages = syncLogMessages("Home", result(pullAttempted = true, pullStatus = "succeeded"))

        assertEquals(listOf("Home pull: succeeded"), messages)
    }

    @Test
    fun pullWithResolvedConflicts_notesTheCount() {
        val messages = syncLogMessages(
            "Home",
            result(pullAttempted = true, pullStatus = "succeeded", pullConflictsResolved = 2),
        )

        assertEquals(listOf("Home pull: succeeded, resolved 2 conflict(s)"), messages)
    }

    @Test
    fun failedPull_includesTheErrorMessage() {
        val messages = syncLogMessages(
            "Home",
            result(pullAttempted = true, pullStatus = "failed", pullError = "Network unreachable"),
        )

        assertEquals(listOf("Home pull: failed (Network unreachable)"), messages)
    }

    @Test
    fun bothAttempted_producesTwoLinesPushFirst() {
        val messages = syncLogMessages(
            "Home",
            result(
                pushAttempted = true,
                pushStatus = "pushed",
                pullAttempted = true,
                pullStatus = "succeeded",
            ),
        )

        assertEquals(listOf("Home push: pushed", "Home pull: succeeded"), messages)
    }
}
