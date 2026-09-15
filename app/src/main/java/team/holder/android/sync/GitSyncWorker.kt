package team.holder.android.sync

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

/** [team.holder.android.GitSyncIfDueResult.pushStatus] values that mean the push itself
 * succeeded -- see holder-core's PushResult.h: every other attempted status (auth_failed,
 * not_found, network_error, non_fast_forward, remote_unset, unknown_error) is a real failure. */
private val SUCCESSFUL_PUSH_STATUSES = setOf("pushed", "up_to_date")

/** [team.holder.android.GitSyncIfDueResult.pullStatus] value that means the pull itself
 * succeeded -- any other attempted status (e.g. "failed") is a real failure. */
private const val SUCCESSFUL_PULL_STATUS = "succeeded"

/**
 * Periodic background sync: pulls/pushes every project with a configured git remote, via
 * HolderNative.gitSyncIfDue -- which itself only actually syncs if enough time has passed
 * since the last attempt, so this is safe to run more often than the desired sync interval.
 *
 * Runs in the app's own process (WorkManager may start it without any Activity having run
 * first, e.g. after the process was killed), so it initializes HolderNative itself; that call
 * is a cheap no-op if the app already opened it.
 *
 * Records a Settings > Diagnostics line per project per direction actually attempted (see
 * [syncLogMessages]), and one for any failure -- either one project's own sync call throwing
 * outright (rare: the C ABI converts most real sync failures into a structured failed status,
 * not an exception, so [syncLogMessages] already covers those) or this whole run never reaching
 * the per-project loop at all (e.g. HolderNative.initialize itself failing).
 *
 * Also tracks a consecutive-failure streak (see [HolderSettings.gitSyncConsecutiveFailures]) and
 * posts one [ReliabilityNotifier] notification once [shouldNotifyReliabilityFailure] says so,
 * reset on the next tick where everything attempted succeeds -- see sync_reliability.md. That
 * streak is computed from each project's structured push/pull status strings, not from whether
 * anything here threw: a single project's sync failing is otherwise a silent structured result,
 * with nothing in the UI to notice it happened until Settings > Diagnostics is opened by hand.
 */
class GitSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val logFile = diagnosticsLogFile(applicationContext)

        // Overall signal for this run's consecutive-failure counter (see the reliability-count
        // block below) -- deliberately NOT derived from `result`'s own success/failure below.
        // A single project's push/pull failing is a *structured* GitSyncIfDueResult, not a
        // thrown exception, so it never touches `result` at all; without tracking it here
        // separately, a device where every project has been failing to sync for days would
        // still see `result` succeed every tick and this feature would never fire. See
        // sync_reliability.md.
        var anyAttempted = false
        var anyFailed = false

        val result = runCatching {
            HolderNative.initialize(
                context = applicationContext,
                dataDir = File(applicationContext.filesDir, "holder"),
                schemaSql = applicationContext.assets.open("schema.sql").bufferedReader().use { it.readText() },
                welcomeContent = applicationContext.assets.open("WELCOME.md").bufferedReader().use { it.readText() },
            )

            val intervalSeconds =
                HolderSettings.gitBackgroundSyncIntervalMinutes(applicationContext).first() * 60

            for (project in HolderNative.listProjects()) {
                if (!project.gitRemoteUrl.isNullOrEmpty()) {
                    // Best-effort per project: one project's failure shouldn't stop the rest.
                    runCatching {
                        HolderNative.gitSyncIfDue(project.projectId, intervalSeconds, intervalSeconds)
                    }.onSuccess { syncResult ->
                        val now = System.currentTimeMillis() / 1000
                        for (message in syncLogMessages(project.name, syncResult)) {
                            DiagnosticsLog.append(logFile, DiagnosticsEntry(now, message))
                        }
                        // Structured success/failure, read off the status strings themselves --
                        // not off whether this call threw. Neither push nor pull attempted this
                        // tick (gitSyncIfDue's own due-interval check said there was nothing to
                        // do) contributes no signal either way for this project.
                        if (syncResult.pushAttempted) {
                            anyAttempted = true
                            if (syncResult.pushStatus !in SUCCESSFUL_PUSH_STATUSES) anyFailed = true
                        }
                        if (syncResult.pullAttempted) {
                            anyAttempted = true
                            if (syncResult.pullStatus != SUCCESSFUL_PULL_STATUS) anyFailed = true
                        }
                    }.onFailure { error ->
                        anyAttempted = true
                        anyFailed = true
                        DiagnosticsLog.append(
                            logFile,
                            DiagnosticsEntry(
                                System.currentTimeMillis() / 1000,
                                "${project.name} sync failed: ${error.message}",
                            ),
                        )
                    }
                }
            }
        }

        // Reached only if something failed before or between per-project attempts above (e.g.
        // HolderNative.initialize, or listProjects itself) -- every per-project failure is
        // already logged individually inside the loop. Just as real an overall failure as any
        // per-project one, so it counts the same way against the streak below.
        result.onFailure { error ->
            anyAttempted = true
            anyFailed = true
            DiagnosticsLog.append(
                logFile,
                DiagnosticsEntry(System.currentTimeMillis() / 1000, "Sync failed: ${error.message}"),
            )
        }

        // null (untouched) when this tick attempted nothing at all -- a device with no
        // configured git remotes (or nothing due yet on every configured one) must never
        // accumulate a false failure streak from having nothing to attempt. See
        // ReliabilityFailureTracking.kt for the pure, unit-tested logic itself.
        val newFailureCount = nextConsecutiveFailureCount(
            currentCount = HolderSettings.gitSyncConsecutiveFailures(applicationContext).first(),
            attempted = anyAttempted,
            succeeded = !anyFailed,
        )
        if (newFailureCount != null) {
            HolderSettings.setGitSyncConsecutiveFailures(applicationContext, newFailureCount)
            if (shouldNotifyReliabilityFailure(newFailureCount, succeeded = !anyFailed)) {
                ReliabilityNotifier.notify(applicationContext, ReliabilityFailureKind.GIT_SYNC)
            }
        }

        if (result.isSuccess) Result.success() else Result.retry()
    }
}
