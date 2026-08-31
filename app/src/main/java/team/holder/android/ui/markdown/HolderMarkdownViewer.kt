package team.holder.android.ui.markdown

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.alerts.Alert
import org.commonmark.ext.gfm.alerts.AlertsExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.Image as MdImage
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.ThematicBreak
import org.commonmark.node.Text as MdText
import org.commonmark.parser.Parser
import team.holder.android.HolderCard
import team.holder.android.HolderNative

private val WIKILINK_REGEX = Regex("\\[\\[([^\\]\n]+)\\]\\]")
private const val HOLDER_LINK_SCHEME = "holder-link:"

// The same scheme holder-desktop's MarkdownResourceImageController already renders --
// `![label](holder://resource/<resource-id>)`. Only recognized when it's the entire
// paragraph (see the Paragraph branch in MarkdownBlock below), matching desktop's own
// restriction: a resource image mixed inline with other text just falls back to plain link
// rendering rather than an attempt at inline bitmap layout.
private const val HOLDER_RESOURCE_SCHEME = "holder://resource/"

// Roughly matches GitHub's own alert accent colors; doesn't need to be pixel-exact.
private val ALERT_COLORS = mapOf(
    "NOTE" to Color(0xFF0969DA),
    "TIP" to Color(0xFF1A7F37),
    "IMPORTANT" to Color(0xFF8250DF),
    "WARNING" to Color(0xFF9A6700),
    "CAUTION" to Color(0xFFCF222E),
)

private fun Node.children(): List<Node> {
    val result = mutableListOf<Node>()
    var child = firstChild
    while (child != null) {
        result.add(child)
        child = child.next
    }
    return result
}

/** The literal text inside node's subtree -- used for an MdImage's alt text (its children are
 * the `[...]` contents, themselves ordinary inline nodes like any other), not for anything
 * that needs real inline formatting. */
private fun plainText(node: Node): String = buildString {
    fun visit(current: Node) {
        if (current is MdText) append(current.literal)
        current.children().forEach(::visit)
    }
    visit(node)
}

/** Rewrites `[[Name]]` into an ordinary Markdown link with a custom scheme, so a vanilla
 * CommonMark parser handles it without any custom parser extension. */
private fun preprocessWikilinks(markdown: String): String =
    WIKILINK_REGEX.replace(markdown) { match ->
        val name = match.groupValues[1]
        "[$name]($HOLDER_LINK_SCHEME${URLEncoder.encode(name, "UTF-8")})"
    }

private fun resolveWikilink(target: String, cards: List<HolderCard>): HolderCard? =
    cards.firstOrNull { it.cardId == target }
        ?: cards.firstOrNull { it.title == target }
        ?: cards.firstOrNull { it.title.equals(target, ignoreCase = true) }

/**
 * Renders a card's Markdown body as a document -- headings, emphasis, lists, code, quotes,
 * and links, with `[[Name]]` internal links resolved and made tappable. Rendering and
 * Holder-link resolution live together here since they're inseparable: a wikilink is only
 * meaningful once resolved against this project's cards.
 */
@Composable
fun HolderMarkdownViewer(
    markdown: String,
    projectId: String,
    cardId: String?,
    onNavigateToCard: (cardId: String, title: String) -> Unit,
    onNavigateToTag: (tag: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingWikilink by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }

    // The card's real extracted tags, fetched once, are the source of truth for which #word
    // spans are actually clickable -- cheaper and more robust than re-deriving the backend's
    // full matching rules (boundary chars, hex-color exclusion) a second time in Kotlin.
    var cardTags by remember(cardId) { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(cardId) {
        cardTags = if (cardId == null) {
            emptySet()
        } else {
            runCatching {
                withContext(Dispatchers.IO) { HolderNative.listCardTags(cardId) }
            }.getOrDefault(emptyList()).toSet()
        }
    }

    val document = remember(markdown) {
        val parser = Parser.builder()
            .extensions(
                listOf(
                    StrikethroughExtension.create(),
                    TablesExtension.create(),
                    TaskListItemsExtension.create(),
                    AutolinkExtension.create(),
                    AlertsExtension.create(),
                ),
            )
            .build()
        parser.parse(preprocessWikilinks(markdown))
    }

    val onWikilinkClick: (String) -> Unit = { target ->
        scope.launch {
            val cards = runCatching {
                withContext(Dispatchers.IO) { HolderNative.listCards(projectId) }
            }.getOrDefault(emptyList())
            val resolved = resolveWikilink(target, cards)
            if (resolved != null) {
                onNavigateToCard(resolved.cardId, resolved.title)
            } else {
                pendingWikilink = target
            }
        }
    }

    val onUrlClick: (String) -> Unit = { url ->
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    Column(modifier = modifier) {
        for (node in document.children()) {
            MarkdownBlock(node, onWikilinkClick, onUrlClick, cardTags, onNavigateToTag)
        }
    }

    pendingWikilink?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!creating) pendingWikilink = null },
            title = { Text("Create Linked Card?") },
            text = { Text("No card matches [[$target]].") },
            confirmButton = {
                TextButton(
                    enabled = !creating,
                    onClick = {
                        creating = true
                        scope.launch {
                            val result = runCatching {
                                withContext(Dispatchers.IO) { HolderNative.createCard(projectId, target, "") }
                            }
                            creating = false
                            pendingWikilink = null
                            result.onSuccess { onNavigateToCard(it.cardId, it.title) }
                        }
                    },
                ) { Text("Create Card") }
            },
            dismissButton = {
                TextButton(enabled = !creating, onClick = { pendingWikilink = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun MarkdownBlock(
    node: Node,
    onWikilinkClick: (String) -> Unit,
    onUrlClick: (String) -> Unit,
    cardTags: Set<String>,
    onTagClick: (String) -> Unit,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    when (node) {
        is Heading -> {
            val style = when (node.level) {
                1 -> MaterialTheme.typography.headlineMedium
                2 -> MaterialTheme.typography.headlineSmall
                3 -> MaterialTheme.typography.titleLarge
                4 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            }
            Text(
                text = inlineText(node, onWikilinkClick, onUrlClick, cardTags, onTagClick, linkColor),
                style = style,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        is Paragraph -> {
            val soleImage = node.firstChild as? MdImage
            val resourceId = soleImage
                ?.takeIf { it === node.lastChild }
                ?.destination
                ?.takeIf { it.startsWith(HOLDER_RESOURCE_SCHEME) }
                ?.removePrefix(HOLDER_RESOURCE_SCHEME)
            if (resourceId != null) {
                var viewerOpen by remember(resourceId) { mutableStateOf(false) }
                val altText = plainText(soleImage)
                ResourceImage(
                    resourceId = resourceId,
                    altText = altText,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    onClick = { viewerOpen = true },
                )
                if (viewerOpen) {
                    ResourceImageViewerDialog(resourceId = resourceId, altText = altText, onDismiss = { viewerOpen = false })
                }
            } else {
                Text(
                    text = inlineText(node, onWikilinkClick, onUrlClick, cardTags, onTagClick, linkColor),
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
        is BulletList -> Column(modifier = Modifier.padding(start = 16.dp)) {
            for (item in node.children()) {
                if (item is ListItem) {
                    val checked = (item.firstChild as? TaskListItemMarker)?.isChecked
                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                        if (checked != null) {
                            Checkbox(checked = checked, onCheckedChange = null)
                        } else {
                            Text("•  ", modifier = Modifier.padding(top = 4.dp))
                        }
                        Column {
                            for (child in item.children()) {
                                MarkdownBlock(child, onWikilinkClick, onUrlClick, cardTags, onTagClick)
                            }
                        }
                    }
                }
            }
        }
        is OrderedList -> {
            var number = node.markerStartNumber ?: 1
            Column(modifier = Modifier.padding(start = 16.dp)) {
                for (item in node.children()) {
                    if (item is ListItem) {
                        Row(modifier = Modifier.padding(vertical = 2.dp)) {
                            Text("${number++}.  ", modifier = Modifier.padding(top = 4.dp))
                            Column {
                                for (child in item.children()) {
                                    MarkdownBlock(child, onWikilinkClick, onUrlClick, cardTags, onTagClick)
                                }
                            }
                        }
                    }
                }
            }
        }
        is BlockQuote -> Column(
            modifier = Modifier
                .padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp),
        ) {
            for (child in node.children()) MarkdownBlock(child, onWikilinkClick, onUrlClick, cardTags, onTagClick)
        }
        is Alert -> {
            val alertColor = ALERT_COLORS[node.type] ?: MaterialTheme.colorScheme.primary
            val label = AlertsExtension.STANDARD_TYPES[node.type] ?: node.type
            Row(
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .height(IntrinsicSize.Min)
                    .background(alertColor.copy(alpha = 0.08f)),
            ) {
                Box(modifier = Modifier.width(4.dp).fillMaxHeight().background(alertColor))
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(label, color = alertColor, fontWeight = FontWeight.Bold)
                    for (child in node.children()) {
                        MarkdownBlock(child, onWikilinkClick, onUrlClick, cardTags, onTagClick)
                    }
                }
            }
        }
        is FencedCodeBlock -> Text(
            text = node.literal.trimEnd('\n'),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .padding(vertical = 4.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp),
        )
        is IndentedCodeBlock -> Text(
            text = node.literal.trimEnd('\n'),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .padding(vertical = 4.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp),
        )
        is ThematicBreak -> HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        is TableBlock -> Column(
            modifier = Modifier.padding(vertical = 4.dp).horizontalScroll(rememberScrollState()),
        ) {
            for (section in node.children()) {
                for (row in section.children()) {
                    if (row is TableRow) {
                        Row {
                            for (cell in row.children()) {
                                if (cell is TableCell) {
                                    Box(
                                        modifier = Modifier
                                            .width(120.dp)
                                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                            .padding(8.dp),
                                    ) {
                                        Text(
                                            text = inlineText(
                                                cell,
                                                onWikilinkClick,
                                                onUrlClick,
                                                cardTags,
                                                onTagClick,
                                                linkColor,
                                            ),
                                            fontWeight = if (cell.isHeader) FontWeight.Bold else FontWeight.Normal,
                                            textAlign = when (cell.alignment) {
                                                TableCell.Alignment.CENTER -> TextAlign.Center
                                                TableCell.Alignment.RIGHT -> TextAlign.End
                                                else -> TextAlign.Start
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        else -> for (child in node.children()) MarkdownBlock(child, onWikilinkClick, onUrlClick, cardTags, onTagClick)
    }
}

@Composable
private fun inlineText(
    node: Node,
    onWikilinkClick: (String) -> Unit,
    onUrlClick: (String) -> Unit,
    cardTags: Set<String>,
    onTagClick: (String) -> Unit,
    linkColor: Color,
): AnnotatedString = remember(node, onWikilinkClick, onUrlClick, cardTags, onTagClick, linkColor) {
    buildAnnotatedString {
        appendInline(node, this, onWikilinkClick, onUrlClick, cardTags, onTagClick, linkColor)
    }
}

// Finds #word-shaped candidate spans; cardTags (the card's real extracted tags, already
// lowercase) decides which candidates are actually clickable, not this regex -- see the
// cardTags fetch in HolderMarkdownViewer for why that's the source of truth.
private val TAG_CANDIDATE_REGEX = Regex("#[A-Za-z][A-Za-z0-9_/-]*")

private fun appendTaggedText(
    text: String,
    builder: AnnotatedString.Builder,
    cardTags: Set<String>,
    onTagClick: (String) -> Unit,
    linkColor: Color,
) {
    if (cardTags.isEmpty()) {
        builder.append(text)
        return
    }
    var last = 0
    for (match in TAG_CANDIDATE_REGEX.findAll(text)) {
        val tag = match.value.removePrefix("#").lowercase()
        if (tag !in cardTags) continue
        builder.append(text, last, match.range.first)
        val start = builder.length
        builder.append(match.value)
        val end = builder.length
        builder.addLink(
            LinkAnnotation.Clickable(
                tag = "tag",
                styles = TextLinkStyles(SpanStyle(color = linkColor, fontWeight = FontWeight.Medium)),
                linkInteractionListener = object : LinkInteractionListener {
                    override fun onClick(link: LinkAnnotation) = onTagClick(tag)
                },
            ),
            start,
            end,
        )
        last = match.range.last + 1
    }
    builder.append(text, last, text.length)
}

private fun appendInline(
    node: Node,
    builder: AnnotatedString.Builder,
    onWikilinkClick: (String) -> Unit,
    onUrlClick: (String) -> Unit,
    cardTags: Set<String>,
    onTagClick: (String) -> Unit,
    linkColor: Color,
    insideLink: Boolean = false,
) {
    var child = node.firstChild
    while (child != null) {
        when (child) {
            is MdText -> if (insideLink) {
                builder.append(child.literal)
            } else {
                appendTaggedText(child.literal, builder, cardTags, onTagClick, linkColor)
            }
            is Emphasis -> {
                val start = builder.length
                appendInline(child, builder, onWikilinkClick, onUrlClick, cardTags, onTagClick, linkColor, insideLink)
                builder.addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, builder.length)
            }
            is StrongEmphasis -> {
                val start = builder.length
                appendInline(child, builder, onWikilinkClick, onUrlClick, cardTags, onTagClick, linkColor, insideLink)
                builder.addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, builder.length)
            }
            is Strikethrough -> {
                val start = builder.length
                appendInline(child, builder, onWikilinkClick, onUrlClick, cardTags, onTagClick, linkColor, insideLink)
                builder.addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, builder.length)
            }
            is Code -> {
                val start = builder.length
                builder.append(child.literal)
                builder.addStyle(SpanStyle(fontFamily = FontFamily.Monospace), start, builder.length)
            }
            is Link -> {
                val destination = child.destination.orEmpty()
                val start = builder.length
                appendInline(child, builder, onWikilinkClick, onUrlClick, cardTags, onTagClick, linkColor, insideLink = true)
                val end = builder.length
                if (destination.startsWith(HOLDER_LINK_SCHEME)) {
                    val target = URLDecoder.decode(destination.removePrefix(HOLDER_LINK_SCHEME), "UTF-8")
                    builder.addLink(
                        LinkAnnotation.Clickable(
                            tag = "wikilink",
                            styles = TextLinkStyles(SpanStyle(color = linkColor, fontWeight = FontWeight.Medium)),
                            linkInteractionListener = object : LinkInteractionListener {
                                override fun onClick(link: LinkAnnotation) = onWikilinkClick(target)
                            },
                        ),
                        start,
                        end,
                    )
                } else {
                    builder.addLink(
                        LinkAnnotation.Clickable(
                            tag = "url",
                            styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                            linkInteractionListener = object : LinkInteractionListener {
                                override fun onClick(link: LinkAnnotation) = onUrlClick(destination)
                            },
                        ),
                        start,
                        end,
                    )
                }
            }
            is SoftLineBreak -> builder.append(" ")
            is HardLineBreak -> builder.append("\n")
            else -> appendInline(child, builder, onWikilinkClick, onUrlClick, cardTags, onTagClick, linkColor, insideLink)
        }
        child = child.next
    }
}

private sealed interface ResourceImageState {
    object Loading : ResourceImageState
    data class Loaded(val bitmap: ImageBitmap) : ResourceImageState
    data class Failed(val message: String) : ResourceImageState
}

/** Bytes are cached on disk, not just in memory -- see the plan's own success criterion:
 * restarting Holder and reopening the card must still render the image without re-touching
 * Drive every time, while the very first load (or a load after the cache is cleared) still
 * genuinely round-trips through HolderNative.retrieveAsset -- see AssetImportService::retrieve
 * in holder-core for the integrity check that guards what lands in this file. */
private fun resourceImageCacheFile(context: android.content.Context, resourceId: String): File =
    File(context.cacheDir, "resource-images/$resourceId.bin")

// Android's own documented technique (developer.android.com "Loading Large Bitmaps
// Efficiently") for decoding at a reduced resolution without ever holding the full-size
// bitmap in memory -- inSampleSize halves both dimensions per step, so the loop finds the
// largest power-of-two downsample that still meets reqWidth/reqHeight.
private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val height = options.outHeight
    val width = options.outWidth
    var inSampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

/** Decodes path at a resolution no larger than maxDimensionPx on either side -- never reads
 * the full-resolution bitmap into memory just to immediately downscale it. A real camera
 * photo can be 12MP+; decoding that in full for a 48dp thumbnail (or several, in a scrolling
 * list) is a real OOM risk, not just wasted work. */
private fun decodeSampledBitmap(path: String, maxDimensionPx: Int): android.graphics.Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateInSampleSize(bounds, maxDimensionPx, maxDimensionPx)
    }
    return BitmapFactory.decodeFile(path, options) ?: error("could not decode image")
}

/** Downloads (or reads back from an on-disk cache), decodes, and renders a Resource's image
 * bytes -- internal, not private, so [team.holder.android.ui.screens.ConnectionsScreen] can
 * reuse it for a small attachment thumbnail rather than duplicating the retrieve/decode/cache
 * logic. The loading placeholder sizes to its spinner rather than a fixed height, so it looks
 * right both as a full-width inline image and as a small thumbnail -- size it via [modifier].
 * [maxDimensionPx] bounds the *decode* resolution (see [decodeSampledBitmap]), independent of
 * [modifier]'s display size -- a thumbnail should pass a small value; [ResourceImageViewerDialog]
 * passes a much larger one since it's the one place actually meant to show full detail. */
@Composable
internal fun ResourceImage(
    resourceId: String,
    altText: String,
    modifier: Modifier = Modifier,
    maxDimensionPx: Int = 1600,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var state by remember(resourceId, maxDimensionPx) { mutableStateOf<ResourceImageState>(ResourceImageState.Loading) }

    LaunchedEffect(resourceId, maxDimensionPx) {
        state = ResourceImageState.Loading
        state = runCatching {
            withContext(Dispatchers.IO) {
                val cacheFile = resourceImageCacheFile(context, resourceId)
                if (!cacheFile.exists()) {
                    val resource = HolderNative.getResource(resourceId)
                    val asset = resource.assets.firstOrNull() ?: error("resource has no asset")
                    val placement = asset.placements.firstOrNull() ?: error("asset has no placement")
                    cacheFile.parentFile?.mkdirs()
                    HolderNative.retrieveAsset(resourceId, asset.assetId, placement.placementId, cacheFile.absolutePath)
                }
                decodeSampledBitmap(cacheFile.absolutePath, maxDimensionPx).asImageBitmap()
            }
        }.fold(
            onSuccess = { bitmap -> ResourceImageState.Loaded(bitmap) },
            onFailure = { failure -> ResourceImageState.Failed(failure.message ?: failure::class.java.simpleName) },
        )
    }

    val clickableModifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    when (val current = state) {
        is ResourceImageState.Loading -> Box(
            modifier = clickableModifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }
        is ResourceImageState.Failed -> Text(
            text = "Couldn't load image \"$altText\": ${current.message}",
            color = MaterialTheme.colorScheme.error,
            modifier = modifier,
        )
        is ResourceImageState.Loaded -> Image(
            bitmap = current.bitmap,
            contentDescription = altText,
            modifier = clickableModifier,
        )
    }
}

/** Full-screen, tap-anywhere-to-dismiss view of a Resource image at a much higher decode
 * resolution than the inline/thumbnail cases -- still capped (see [decodeSampledBitmap]'s
 * doc comment), never a true uncapped full-resolution decode; nothing in this app has a
 * legitimate need to hold a 12MP+ bitmap in memory at once, and a phone screen couldn't
 * show that much detail anyway. */
@Composable
internal fun ResourceImageViewerDialog(resourceId: String, altText: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            ResourceImage(
                resourceId = resourceId,
                altText = altText,
                maxDimensionPx = 2560,
                modifier = Modifier.fillMaxSize().padding(16.dp),
            )
        }
    }
}
