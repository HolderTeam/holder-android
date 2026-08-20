package team.holder.android.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import team.holder.android.HolderNative
import team.holder.android.HolderSettings
import java.io.File

/**
 * Periodic background sync: pulls/pushes every project with a configured git remote, via
 * HolderNative.gitSyncIfDue -- which itself only actually syncs if enough time has passed
 * since the last attempt, so this is safe to run more often than the desired sync interval.
 *
 * Runs in the app's own process (WorkManager may start it without any Activity having run
 * first, e.g. after the process was killed), so it initializes HolderNative itself; that call
 * is a cheap no-op if the app already opened it.
 */
class GitSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
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
                    runCatching { HolderNative.gitSyncIfDue(project.projectId, intervalSeconds, intervalSeconds) }
                }
            }
        }

        if (result.isSuccess) Result.success() else Result.retry()
    }
}
