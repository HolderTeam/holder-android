package team.holder.android.ui

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Opens [url] in whatever app handles it (normally the system browser) -- the same plain
 * `ACTION_VIEW` pattern [team.holder.android.ui.markdown.HolderMarkdownViewer] already uses
 * for links inside card content. Silently does nothing if no app can handle it, rather than
 * crashing -- there's no good in-app fallback for "no browser installed" worth building. */
fun openUrlExternally(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
