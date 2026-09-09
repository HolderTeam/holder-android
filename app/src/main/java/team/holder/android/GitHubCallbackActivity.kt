package team.holder.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import team.holder.android.git.github.GitHubConnection

/**
 * The only exported App Link boundary for GitHub OAuth and installation returns. It deliberately
 * has no UI and never initializes Holder's main workspace: it forwards the raw URI into the
 * process-scoped coordinator then immediately finishes. Intent extras and caller identity are
 * intentionally ignored; the coordinator's endpoint/state/PKCE checks remain authoritative.
 */
class GitHubCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dispatch(intent)
        finish()
    }

    private fun dispatch(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        when (callbackKind(uri.path)) {
            CallbackKind.OAuth -> GitHubConnection.dispatchOAuthCallbackUri(uri)
            CallbackKind.Installation ->
                GitHubConnection.dispatchInstallationReturn(applicationContext, uri.getQueryParameter("state"))
            null -> Unit
        }
    }

    internal enum class CallbackKind { OAuth, Installation }

    internal companion object {
        /** Kept pure so the narrow exported path routing can be covered without constructing
         * an Android Activity in a plain JVM test. Host/scheme/state validation stays in the
         * coordinator. */
        fun callbackKind(path: String?): CallbackKind? = when (path) {
            "/android/oauth-callback" -> CallbackKind.OAuth
            "/github/install-complete" -> CallbackKind.Installation
            else -> null
        }
    }
}
