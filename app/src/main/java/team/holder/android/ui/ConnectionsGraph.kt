package team.holder.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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

// The graph's own comfortable reference size for a handful of nodes -- unchanged from before
// pan/zoom existed, so a typical 2-6 connection card looks exactly like it always did. Radius
// only grows past this once MinArcSpacing needs more ring circumference than this gives it (see
// ringRadius below) -- it no longer has to also fit inside the viewport at 1:1, since fitScale
// now does that job.
private val GraphMaxSize = 360.dp
private val GraphPadding = 24.dp
private const val RadiusFraction = 0.38f

// Enough tangential room per satellite that GraphSatelliteNode's own widthIn(max = 92.dp) bubbles
// don't run into their neighbors around the ring once there are many of them -- this, not the
// viewport, is what decides how big the *unscaled* graph gets; pinch-zoom/pan (and the initial
// fit-to-screen below) are what make a graph bigger than one screen fully visible and readable.
private val MinArcSpacingPerNode = 108.dp
private val SatelliteDiameter = 120.dp

private const val MinScale = 0.3f
private const val MaxScale = 3f

private fun angleRadiansFor(index: Int, count: Int): Double =
    Math.toRadians((index * (360f / count) - 90f).toDouble())

private fun ringRadius(nodeCount: Int): Dp {
    val comfortable = GraphMaxSize * RadiusFraction
    val arcBased = MinArcSpacingPerNode * nodeCount / (2 * Math.PI).toFloat()
    return maxOf(comfortable, arcBased)
}

/** Radial connections graph: [centerTitle] fixed in the middle, [nodes] evenly spaced around it
 * with an arrow (direction per [GraphNode.direction]) drawn underneath. Tapping a satellite
 * reports it via [onNodeClick] -- recentering is the caller's job (it just refetches for the
 * tapped card), this composable only ever renders what it's given. Long-pressing a satellite
 * (via [onNodeOpen]) or the center bubble (via [onCenterOpen]) is the way to actually leave the
 * graph and open that card, since there's no desktop-style second pane to update here -- tap
 * stays a pure "look around" gesture with no navigation side effect, so the same finger sweep
 * that recenters a few hops out never accidentally jumps you out of the graph mid-explore.
 *
 * Pannable and pinch-zoomable like an ordinary infinite-canvas app (Miro, Google Maps, ...):
 * the ring's own radius grows with node count (see [ringRadius]) rather than always cramming
 * into one fixed size, and opening the graph (or tapping a satellite to recenter on it) fits
 * whatever that node count needs into view, zoomed out just enough and no further -- from there,
 * pinching in is for reading comfort, not a workaround for overlap. */
@Composable
fun ConnectionsGraphView(
    centerTitle: String,
    nodes: List<GraphNode>,
    onNodeClick: (GraphNode) -> Unit,
    onNodeOpen: (GraphNode) -> Unit,
    onCenterOpen: () -> Unit,
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
    val density = LocalDensity.current

    val radius = remember(nodes.size) { ringRadius(nodes.size) }
    val graphSize = radius * 2 + SatelliteDiameter + GraphPadding * 2

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    // Fits the whole (unscaled) graph into whatever viewport space is actually available, the
    // moment both are known -- on first composition, and again on a fresh recenter (a new
    // `nodes` list, from tapping a satellite), so a bigger or smaller connection set always
    // opens fully visible rather than at a zoom level left over from whichever card was centered
    // before it. Never zooms *in* past 1x automatically -- only ever out, and only as far as
    // MinScale, so an enormous graph still opens at a sane (if small) size rather than vanishing.
    LaunchedEffect(nodes, viewportSize) {
        val viewport = viewportSize
        if (viewport.width > 0 && viewport.height > 0) {
            val graphSizePx = with(density) { graphSize.toPx() }
            val fit = (minOf(viewport.width, viewport.height) / graphSizePx)
            scale = fit.coerceIn(MinScale, 1f)
            offset = Offset.Zero
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewportSize = it }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val oldScale = scale
                    val newScale = (oldScale * zoom).coerceIn(MinScale, MaxScale)
                    // Keeps whatever point is currently under the fingers visually fixed while
                    // scale changes, the way pinch-zoom is expected to feel (Miro, Google Maps,
                    // Photos, ...) -- translationX/Y apply in absolute screen pixels regardless
                    // of this layer's own scale (see GraphicsLayerScope's own doc comment), so a
                    // naive "just add pan" would otherwise make the content drift out from under
                    // a pinch that isn't centered on the ring's middle.
                    val viewportCenter = Offset(viewportSize.width / 2f, viewportSize.height / 2f)
                    val focal = centroid - viewportCenter - offset
                    offset += focal * (1f - newScale / oldScale) + pan
                    scale = newScale
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(graphSize)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            contentAlignment = Alignment.Center,
        ) {
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
                modifier = Modifier
                    .align(Alignment.Center)
                    .widthIn(max = 100.dp)
                    .combinedClickable(onClick = {}, onLongClickLabel = "Open card", onLongClick = onCenterOpen),
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
                    onLongClick = { onNodeOpen(node) },
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
private fun GraphSatelliteNode(
    node: GraphNode,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
            .combinedClickable(onClick = onClick, onLongClickLabel = "Open card", onLongClick = onLongClick)
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
