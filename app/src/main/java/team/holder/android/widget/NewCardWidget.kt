package team.holder.android.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import team.holder.android.MainActivity

/** A single "+ New card" button, reusing the same NEW_CARD intent action that the app's static
 * shortcut (res/xml/shortcuts.xml) already fires -- see MainActivity.isNewCardShortcutIntent and
 * HolderNavHost's project-count handling. The widget deliberately has no logic of its own beyond
 * launching that intent. */
class NewCardWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(Color(0xFF6200EE))
                    .clickable(actionStartActivity(newCardIntent(context))),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+ New card",
                    style = TextStyle(
                        color = ColorProvider(Color.White),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
        }
    }

    private fun newCardIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = "team.holder.android.action.NEW_CARD"
            // No calling Activity for a widget click to launch from, unlike the static shortcut.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
}
