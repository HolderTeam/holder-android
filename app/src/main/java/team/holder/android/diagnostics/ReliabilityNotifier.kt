package team.holder.android.diagnostics

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import team.holder.android.MainActivity
import team.holder.android.R

/** Fires MainActivity straight to Settings > Diagnostics when a background-reliability
 * notification (see [ReliabilityNotifier]) is tapped -- read in MainActivity.isOpenDiagnosticsIntent
 * the same way ".../action.NEW_CARD" is read for the "New card" shortcut. */
const val ACTION_OPEN_DIAGNOSTICS = "team.holder.android.action.OPEN_DIAGNOSTICS"

/** Consecutive-failure count (see [HolderSettings.gitSyncConsecutiveFailures] /
 * [HolderSettings.snapshotConsecutiveFailures]) at which GitSyncWorker/SnapshotWorker posts one
 * notification via [ReliabilityNotifier.notify] -- compared with `==`, not `>=`, so exactly one
 * notification fires per failure streak rather than one on every tick past the threshold. */
const val RELIABILITY_FAILURE_THRESHOLD = 3

/** One background job [ReliabilityNotifier] can notify about -- a distinct notification id and
 * distinct text per kind, so a git-sync failure notification and a snapshot failure notification
 * never overwrite each other in the shade. */
enum class ReliabilityFailureKind(val notificationId: Int, val title: String, val text: String) {
    GIT_SYNC(
        notificationId = 1001,
        title = "Background sync has been failing",
        text = "Holder hasn't been able to sync recently. Check Settings > Diagnostics for details.",
    ),
    SNAPSHOT(
        notificationId = 1002,
        title = "Backup snapshot has been failing",
        text = "Holder's local backup hasn't been able to update recently. Check Settings > Diagnostics for details.",
    ),
}

private const val RELIABILITY_CHANNEL_ID = "background_reliability"

/** Surfaces a sustained GitSyncWorker/SnapshotWorker failure streak -- see sync_reliability.md.
 * Both workers run with no Activity around (WorkManager may start either after the process was
 * killed), so this only ever *checks* POST_NOTIFICATIONS, never requests it: a Worker has no
 * Activity to prompt from, and this codebase's convention (see the dictation feature's mic
 * permission handling in HolderMarkdownEditor.kt) is to request a runtime permission only at its
 * own point of use -- for POST_NOTIFICATIONS, that's SyncSettingsScreen's background-sync toggle.
 * If the permission was never granted, this just skips posting silently, the same "external
 * capability unavailable, don't block, don't crash" shape as that dictation onError handling. */
object ReliabilityNotifier {
    /** Idempotent: creating an already-existing channel is a harmless no-op, so callers never
     * need to guard against calling this more than once. */
    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                RELIABILITY_CHANNEL_ID,
                "Background reliability",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    fun notify(context: Context, kind: ReliabilityFailureKind) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        ensureChannel(context)

        // FLAG_ACTIVITY_NEW_TASK: required when starting an Activity from a non-Activity
        // Context (this is called from a Worker's applicationContext). FLAG_ACTIVITY_CLEAR_TOP
        // brings an already-running MainActivity to the front (routed through onNewIntent,
        // since MainActivity is launchMode singleTop) rather than spawning a duplicate instance.
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_DIAGNOSTICS
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            kind.notificationId,
            intent,
            // No mutation needed -- FLAG_IMMUTABLE per Android's guidance for any PendingIntent
            // that doesn't need to be filled in by the receiving component.
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, RELIABILITY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_alert)
            .setContentTitle(kind.title)
            .setContentText(kind.text)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(kind.notificationId, notification)
    }
}
