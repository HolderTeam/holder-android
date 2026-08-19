package team.holder.android.ui.markdown

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

private fun Node.children(): List<Node> {
    val result = mutableListOf<Node>()
    var child = firstChild
    while (child != null) {
        result.add(child)
        child = child.next
    }
    return result
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
    onNavigateToCard: (cardId: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingWikilink by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }

    val document = remember(markdown) {
        val parser = Parser.builder()
            .extensions(
                listOf(
                    StrikethroughExtension.create(),
                    TablesExtension.create(),
                    TaskListItemsExtension.create(),
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
            MarkdownBlock(node, onWikilinkClick, onUrlClick)
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
                text = inlineText(node, onWikilinkClick, onUrlClick, linkColor),
                style = style,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        is Paragraph -> Text(
            text = inlineText(node, onWikilinkClick, onUrlClick, linkColor),
            modifier = Modifier.padding(vertical = 4.dp),
        )
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
                            for (child in item.children()) MarkdownBlock(child, onWikilinkClick, onUrlClick)
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
                                for (child in item.children()) MarkdownBlock(child, onWikilinkClick, onUrlClick)
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
            for (child in node.children()) MarkdownBlock(child, onWikilinkClick, onUrlClick)
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
                                            text = inlineText(cell, onWikilinkClick, onUrlClick, linkColor),
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
        else -> for (child in node.children()) MarkdownBlock(child, onWikilinkClick, onUrlClick)
    }
}

@Composable
private fun inlineText(
    node: Node,
    onWikilinkClick: (String) -> Unit,
    onUrlClick: (String) -> Unit,
    linkColor: Color,
): AnnotatedString = remember(node, onWikilinkClick, onUrlClick, linkColor) {
    buildAnnotatedString { appendInline(node, this, onWikilinkClick, onUrlClick, linkColor) }
}

private fun appendInline(
    node: Node,
    builder: AnnotatedString.Builder,
    onWikilinkClick: (String) -> Unit,
    onUrlClick: (String) -> Unit,
    linkColor: Color,
) {
    var child = node.firstChild
    while (child != null) {
        when (child) {
            is MdText -> builder.append(child.literal)
            is Emphasis -> {
                val start = builder.length
                appendInline(child, builder, onWikilinkClick, onUrlClick, linkColor)
                builder.addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, builder.length)
            }
            is StrongEmphasis -> {
                val start = builder.length
                appendInline(child, builder, onWikilinkClick, onUrlClick, linkColor)
                builder.addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, builder.length)
            }
            is Strikethrough -> {
                val start = builder.length
                appendInline(child, builder, onWikilinkClick, onUrlClick, linkColor)
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
                appendInline(child, builder, onWikilinkClick, onUrlClick, linkColor)
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
            else -> appendInline(child, builder, onWikilinkClick, onUrlClick, linkColor)
        }
        child = child.next
    }
}
