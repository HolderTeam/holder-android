package team.holder.android.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

val WalnutBase = Color(0xFF4A2E18)

private data class GrainStreak(
    val yFraction: Float,
    val amplitudeDp: Float,
    val frequency: Float,
    val phase: Float,
    val color: Color,
    val strokeWidthDp: Float,
    val alpha: Float,
)

/** Fixed, hand-picked rather than random -- stays identical across recompositions and doesn't
 * need a seeded RNG. Frequencies/phases are irregular on purpose so streaks don't visibly repeat. */
private val GrainStreaks = listOf(
    GrainStreak(0.03f, 9f, 2.3f, 0.4f, Color(0xFF2E1B0E), 3f, 0.55f),
    GrainStreak(0.09f, 5f, 3.1f, 1.9f, Color(0xFF7A5432), 1.5f, 0.4f),
    GrainStreak(0.15f, 13f, 1.6f, 3.2f, Color(0xFF2E1B0E), 2.5f, 0.5f),
    GrainStreak(0.19f, 4f, 4.2f, 5.6f, Color(0xFF8C6239), 1f, 0.35f),
    GrainStreak(0.26f, 7f, 2.7f, 2.1f, Color(0xFF2E1B0E), 2f, 0.45f),
    GrainStreak(0.33f, 16f, 1.3f, 0.8f, Color(0xFF2E1B0E), 4f, 0.6f),
    GrainStreak(0.36f, 6f, 3.6f, 4.4f, Color(0xFF8C6239), 1.5f, 0.4f),
    GrainStreak(0.44f, 8f, 2.1f, 1.2f, Color(0xFF2E1B0E), 2f, 0.45f),
    GrainStreak(0.50f, 5f, 3.9f, 3.7f, Color(0xFF7A5432), 1f, 0.35f),
    GrainStreak(0.55f, 12f, 1.8f, 6.0f, Color(0xFF2E1B0E), 3f, 0.5f),
    GrainStreak(0.61f, 4f, 4.6f, 2.6f, Color(0xFF8C6239), 1.5f, 0.35f),
    GrainStreak(0.68f, 10f, 2.4f, 0.5f, Color(0xFF2E1B0E), 2.5f, 0.5f),
    GrainStreak(0.72f, 6f, 3.3f, 5.1f, Color(0xFF7A5432), 1f, 0.35f),
    GrainStreak(0.78f, 15f, 1.5f, 1.6f, Color(0xFF2E1B0E), 3.5f, 0.55f),
    GrainStreak(0.83f, 5f, 3.7f, 4.0f, Color(0xFF8C6239), 1.5f, 0.4f),
    GrainStreak(0.89f, 8f, 2.6f, 2.9f, Color(0xFF2E1B0E), 2f, 0.45f),
    GrainStreak(0.94f, 11f, 1.9f, 0.3f, Color(0xFF2E1B0E), 3f, 0.5f),
    GrainStreak(0.98f, 4f, 4.1f, 3.4f, Color(0xFF8C6239), 1f, 0.35f),
)

/** A procedural walnut wood-grain texture: a base plank color with wavy horizontal streaks
 * (dark grain lines and lighter highlights) plus a soft diagonal sheen. Fixed streak data means
 * it redraws identically on every recomposition instead of flickering. */
@Composable
fun WalnutBackground(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        drawRect(color = WalnutBase)

        GrainStreaks.forEach { streak ->
            val baseY = streak.yFraction * size.height
            val amplitudePx = streak.amplitudeDp.dp.toPx()
            val path = Path()
            val steps = 48
            for (i in 0..steps) {
                val t = i.toFloat() / steps
                val x = size.width * t
                val y = baseY + amplitudePx * sin(streak.frequency * t * 2f * PI.toFloat() + streak.phase)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = streak.color.copy(alpha = streak.alpha),
                style = Stroke(width = streak.strokeWidthDp.dp.toPx()),
            )
        }

        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(Color.White.copy(alpha = 0.05f), Color.Transparent, Color.Black.copy(alpha = 0.10f)),
            ),
        )
    }
}
