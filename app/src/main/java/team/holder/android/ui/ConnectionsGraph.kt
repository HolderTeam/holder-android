package team.holder.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import team.holder.android.HolderCardLinks
import team.holder.android.HolderNative

/** Which way a [GraphNode]'s arrow points relative to the centered card: OUTGOING points away
 * from center (parent-of, next, an outgoing typed link), INCOMING points toward it (child-of,
 * previous, a backlink). */
enum class GraphDirection { OUTGOING, INCOMING }

/** One satellite in the connections graph -- everything [ConnectionsGraphView] needs to draw a
 * node and its edge, plus a clear spoken description for TalkBack. */
data class GraphNode(
    val cardId: String,
    val title: String,
    val relationshipLabel: String,
    val customLabel: String?,
    val direction: GraphDirection,
)

/** Builds the flat, ordered node list for [ConnectionsGraphView] from the same data
 * [team.holder.android.ui.screens.ConnectionsScreen]'s list view already fetches -- same order
 * (parent, children, sequence, outgoing, backlinks) so same-relationship nodes cluster together
 * around the ring, same labels and filtering (see the outgoing `toType != "resource"` filter) so
 * Map never shows something List doesn't. */
fun buildConnectionGraphNodes(links: HolderCardLinks, sequence: CardSequenceLinks): List<GraphNode> {
    val nodes = mutableListOf<GraphNode>()
    links.parent?.let {
        nodes += GraphNode(it.cardId, it.title, "Child of", customLabel = null, GraphDirection.INCOMING)
    }
    links.children.forEach {
        nodes += GraphNode(it.cardId, it.title, "Parent of", customLabel = null, GraphDirection.OUTGOING)
    }
    sequence.next?.let { nodes += GraphNode(it.cardId, it.title, "Next", customLabel = null, GraphDirection.OUTGOING) }
    sequence.previous?.let { nodes += GraphNode(it.cardId, it.title, "Previous", customLabel = null, GraphDirection.INCOMING) }
    sequence.follows?.let { nodes += GraphNode(it.cardId, it.title, "Follows", customLabel = null, GraphDirection.OUTGOING) }
    sequence.precedes?.let { nodes += GraphNode(it.cardId, it.title, "Precedes", customLabel = null, GraphDirection.INCOMING) }
    links.outgoing.filter { it.toType != "resource" }.forEach { link ->
        nodes += GraphNode(
            cardId = link.toCardId,
            title = link.toTitle ?: link.toCardId,
            relationshipLabel = HolderNative.linkKindLabel(link.kind, forward = true),
            customLabel = link.label,
            direction = GraphDirection.OUTGOING,
        )
    }
    links.backlinks.forEach { link ->
        nodes += GraphNode(
            cardId = link.fromCardId,
            title = link.fromTitle ?: link.fromCardId,
            relationshipLabel = HolderNative.linkKindLabel(link.kind, forward = false),
            customLabel = link.label,
            direction = GraphDirection.INCOMING,
        )
    }
    return nodes
}

private val GraphMaxSize = 360.dp
private val GraphPadding = 24.dp
private const val RadiusFraction = 0.38f

private fun angleRadiansFor(index: Int, count: Int): Double =
    Math.toRadians((index * (360f / count) - 90f).toDouble())

/** Radial connections graph: [centerTitle] fixed in the middle, [nodes] evenly spaced around it
 * with an arrow (direction per [GraphNode.direction]) drawn underneath. Tapping a satellite
 * reports it via [onNodeClick] -- recentering is the caller's job (it just refetches for the
 * tapped card), this composable only ever renders what it's given. */
@Composable
fun ConnectionsGraphView(
    centerTitle: String,
    nodes: List<GraphNode>,
    onNodeClick: (GraphNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (nodes.isEmpty()) {
        CenteredMessage(modifier) {
            Text(
                "No connections yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val edgeColor = MaterialTheme.colorScheme.outline

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = GraphMaxSize)
                .aspectRatio(1f)
                .padding(GraphPadding),
        ) {
            val radius = maxWidth * RadiusFraction

            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val radiusPx = radius.toPx()
                nodes.forEachIndexed { index, node ->
                    val angle = angleRadiansFor(index, nodes.size)
                    val target = Offset(
                        x = center.x + radiusPx * cos(angle).toFloat(),
                        y = center.y + radiusPx * sin(angle).toFloat(),
                    )
                    drawGraphEdge(center, target, node.direction, edgeColor)
                }
            }

            Surface(
                modifier = Modifier.align(Alignment.Center).widthIn(max = 100.dp),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 2.dp,
            ) {
                Text(
                    centerTitle.ifEmpty { "Card" },
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            nodes.forEachIndexed { index, node ->
                val angle = angleRadiansFor(index, nodes.size)
                val dx = radius * cos(angle).toFloat()
                val dy = radius * sin(angle).toFloat()
                GraphSatelliteNode(
                    node = node,
                    onClick = { onNodeClick(node) },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset { IntOffset(dx.toPx().roundToInt(), dy.toPx().roundToInt()) },
                )
            }
        }
    }
}

/** Line from [from] to [to] plus a small two-segment arrowhead: near the [to] end pointing
 * outward for [GraphDirection.OUTGOING], near the [from] (center) end pointing inward for
 * [GraphDirection.INCOMING] -- matches desktop Holder's direction-carrying arrows without
 * needing anything fancier for what's realistically ~1-12 edges per card. */
private fun DrawScope.drawGraphEdge(from: Offset, to: Offset, direction: GraphDirection, color: Color) {
    val strokeWidth = 1.5.dp.toPx()
    drawLine(color = color, start = from, end = to, strokeWidth = strokeWidth)

    val dx = to.x - from.x
    val dy = to.y - from.y
    val forwardAngle = Math.atan2(dy.toDouble(), dx.toDouble())
    val (tip, pointingAngle) = if (direction == GraphDirection.OUTGOING) {
        Offset(from.x + dx * 0.82f, from.y + dy * 0.82f) to forwardAngle
    } else {
        Offset(from.x + dx * 0.22f, from.y + dy * 0.22f) to (forwardAngle + Math.PI)
    }
    val arrowSize = 8.dp.toPx()
    val spread = Math.toRadians(150.0)
    listOf(pointingAngle + spread, pointingAngle - spread).forEach { wingAngle ->
        drawLine(
            color = color,
            start = tip,
            end = Offset(
                tip.x + (cos(wingAngle) * arrowSize).toFloat(),
                tip.y + (sin(wingAngle) * arrowSize).toFloat(),
            ),
            strokeWidth = strokeWidth,
        )
    }
}

@Composable
private fun GraphSatelliteNode(node: GraphNode, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = buildString {
        append(node.relationshipLabel)
        append(", ")
        append(node.title)
        if (!node.customLabel.isNullOrBlank()) {
            append(" · ")
            append(node.customLabel)
        }
    }
    Surface(
        modifier = modifier
            .widthIn(max = 92.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        // Cleared so TalkBack reads only the explicit description above -- otherwise the two
        // Texts below would merge into it too, doubling up on (and possibly outrunning) the
        // deliberately-composed relationship/title/custom-label description.
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).clearAndSetSemantics {},
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                node.relationshipLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                node.title,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
