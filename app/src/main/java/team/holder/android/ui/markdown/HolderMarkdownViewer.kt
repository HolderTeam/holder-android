package team.holder.android.ui.markdown

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
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
import org.commonmark.node.CustomNode
import org.commonmark.node.Delimited
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.Image as MdImage
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.Nodes
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.SourceSpans
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.ThematicBreak
import org.commonmark.node.Text as MdText
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import org.commonmark.parser.delimiter.DelimiterProcessor
import org.commonmark.parser.delimiter.DelimiterRun
import team.holder.android.CardReferenceResolution
import team.holder.android.HolderCard
import team.holder.android.HolderNative
import team.holder.android.R
import team.holder.android.resource.openResourceExternally

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

/** One `[[Name]]` -> `[Name](holder-link:...)` rewrite done by [preprocessWikilinks], recorded so
 * [translateToOriginalOffset] can later map a position measured against the *rewritten* string
 * back to the corresponding position in the original `markdown` passed to
 * [HolderMarkdownViewer] -- see click_to_edit_position.md. */
private data class WikilinkSubstitution(
    /** Where the replacement text starts, in the rewritten string's own coordinates. */
    val rewrittenStart: Int,
    /** replacement.length - original.length -- always positive in practice (the holder-link:
     * scheme prefix plus URL-encoding only ever adds characters), but the math below holds
     * regardless of sign. */
    val lengthDelta: Int,
)

private data class PreprocessedMarkdown(val text: String, val substitutions: List<WikilinkSubstitution>)

/** Rewrites `[[Name]]` into an ordinary Markdown link with a custom scheme, so a vanilla
 * CommonMark parser handles it without any custom parser extension -- and records each
 * substitution's position/length delta so a source offset measured against the rewritten text
 * (as every [org.commonmark.node.SourceSpan] necessarily is, since that's the string actually
 * parsed) can be translated back to the original `markdown` string's own coordinates. */
private fun preprocessWikilinks(markdown: String): PreprocessedMarkdown {
    val substitutions = mutableListOf<WikilinkSubstitution>()
    val result = StringBuilder()
    var last = 0
    for (match in WIKILINK_REGEX.findAll(markdown)) {
        result.append(markdown, last, match.range.first)
        val name = match.groupValues[1]
        val replacement = "[$name]($HOLDER_LINK_SCHEME${URLEncoder.encode(name, "UTF-8")})"
        substitutions += WikilinkSubstitution(
            rewrittenStart = result.length,
            lengthDelta = replacement.length - match.value.length,
        )
        result.append(replacement)
        last = match.range.last + 1
    }
    result.append(markdown, last, markdown.length)
    return PreprocessedMarkdown(result.toString(), substitutions)
}

/** The inverse of the substitutions [preprocessWikilinks] performed. Only ever called with a
 * [rewrittenOffset] that fell on a [MdText]/[Code] leaf reached outside a [Link] (see
 * `appendInline`'s `insideLink` handling) -- which a rewritten wikilink's own `[Name](holder-link:...)`
 * span never is, so [rewrittenOffset] can never land *inside* one of these substitutions, only
 * before all of them or strictly after any given one. Cards without a `[[wikilink]]` (the common
 * case) take the fast path: an empty list, offset unchanged. */
private fun translateToOriginalOffset(rewrittenOffset: Int, substitutions: List<WikilinkSubstitution>): Int {
    var delta = 0
    for (substitution in substitutions) {
        if (substitution.rewrittenStart > rewrittenOffset) break
        delta += substitution.lengthDelta
    }
    return rewrittenOffset - delta
}

/** Holder's own `++text++` underline convention -- not part of CommonMark or GFM, so unlike
 * `~~text~~` (commonmark-java's built-in StrikethroughExtension) it needs a real parser
 * extension rather than an existing node to recognize it. */
private class Underline(private val delimiter: String) : CustomNode(), Delimited {
    override fun getOpeningDelimiter() = delimiter
    override fun getClosingDelimiter() = delimiter
}

/** Mirrors commonmark-java's own StrikethroughDelimiterProcessor almost exactly, but -- unlike
 * that processor's optional single-tilde mode -- only accepts exactly two `+` characters. A lone
 * `+` is too common in ordinary prose, arithmetic, version numbers, and `C++` to safely claim. */
private class UnderlineDelimiterProcessor : DelimiterProcessor {
    override fun getOpeningCharacter() = '+'

    override fun getClosingCharacter() = '+'

    override fun getMinLength() = 2

    override fun process(openingRun: DelimiterRun, closingRun: DelimiterRun): Int {
        if (openingRun.length() != closingRun.length() || openingRun.length() != 2) return 0

        val opener = openingRun.opener
        val underline = Underline(opener.literal + opener.literal)

        val sourceSpans = SourceSpans()
        sourceSpans.addAllFrom(openingRun.getOpeners(2))
        for (node in Nodes.between(opener, closingRun.closer)) {
            underline.appendChild(node)
            sourceSpans.addAll(node.sourceSpans)
        }
        sourceSpans.addAllFrom(closingRun.getClosers(2))
        underline.setSourceSpans(sourceSpans.sourceSpans)

        opener.insertAfter(underline)
        return 2
    }
}

// holder-core's CardReferenceResolver (used via HolderNative.resolveCardReference) doesn't do
// case-insensitive title matching -- its exact-title match is a case-sensitive SQL `=`. This is
// kept as an Android-side fallback, tried only when the native resolver returns not_found, for
// existing content that relied on the old hand-rolled resolver's case-insensitive third branch.
private fun resolveWikilinkCaseInsensitive(target: String, cards: List<HolderCard>): HolderCard? =
    cards.firstOrNull { it.title.equals(target, ignoreCase = true) }

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
    onCardCreated: (cardId: String, title: String, content: String) -> Unit,
    modifier: Modifier = Modifier,
    // Null keeps every existing caller unaffected. Fires with an offset into `markdown` itself
    // (already translated past any wikilink rewriting -- see translateToOriginalOffset) when a
    // tap lands on plain rendered text, outside any link/tag span. See click_to_edit_position.md.
    onRequestEditAt: ((Int) -> Unit)? = null,
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

    val preprocessed = remember(markdown) { preprocessWikilinks(markdown) }
    val document = remember(preprocessed) {
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
            .customDelimiterProcessor(UnderlineDelimiterProcessor())
            // BLOCKS_AND_INLINES: every leaf inline node (MdText, Code) carries a SourceSpan
            // recording its absolute offset into `preprocessed.text` -- the basis for mapping a
            // tap on rendered text back to a position in the original markdown. See
            // click_to_edit_position.md.
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .build()
        parser.parse(preprocessed.text)
    }

    // Wraps onRequestEditAt exactly once here, rather than at every leaf callback invocation --
    // every offset MarkdownBlock/appendInline ever produces is measured against
    // preprocessed.text, so this is the one place that needs to know about substitutions at all.
    val translatedOnRequestEditAt: ((Int) -> Unit)? = onRequestEditAt?.let { callback ->
        { rewrittenOffset: Int -> callback(translateToOriginalOffset(rewrittenOffset, preprocessed.substitutions)) }
    }

    val onWikilinkClick: (String) -> Unit = { target ->
        scope.launch {
            val resolution = runCatching {
                withContext(Dispatchers.IO) { HolderNative.resolveCardReference(projectId, target) }
            }.getOrDefault(CardReferenceResolution.NotFound)
            when (resolution) {
                is CardReferenceResolution.Resolved ->
                    onNavigateToCard(resolution.card.cardId, resolution.card.title)
                is CardReferenceResolution.Ambiguous -> {
                    // Deliberate simplification: holder-core can report an ambiguous match (e.g.
                    // two live cards sharing an exact title), but Android has no disambiguation
                    // UI yet. Always landing on the first candidate preserves today's actual
                    // behavior (the old firstOrNull-based resolver never blocked the user
                    // either) rather than introducing a picker, which is out of scope here. A
                    // future pass can add real disambiguation if this ever matters in practice.
                    val first = resolution.candidates.firstOrNull()
                    if (first != null) {
                        onNavigateToCard(first.cardId, first.title)
                    } else {
                        pendingWikilink = target
                    }
                }
                is CardReferenceResolution.NotFound -> {
                    // holder-core's resolver has no case-insensitive title fallback (see
                    // resolveWikilinkCaseInsensitive) -- only try it, at the cost of fetching
                    // every live card, once the cheap native call has already failed.
                    val cards = runCatching {
                        withContext(Dispatchers.IO) { HolderNative.listCards(projectId) }
                    }.getOrDefault(emptyList())
                    val fallback = resolveWikilinkCaseInsensitive(target, cards)
                    if (fallback != null) {
                        onNavigateToCard(fallback.cardId, fallback.title)
                    } else {
                        pendingWikilink = target
                    }
                }
            }
        }
    }

    val onUrlClick: (String) -> Unit = { url ->
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    Column(modifier = modifier) {
        for (node in document.children()) {
            MarkdownBlock(node, onWikilinkClick, onUrlClick, cardTags, onNavigateToTag, translatedOnRequestEditAt)
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
                            // Straight to the editor, not the viewer -- a card just created from
                            // a wikilink is empty by definition, and viewing an empty card is
                            // never what the "Create Card" tap was for.
                            result.onSuccess { onCardCreated(it.cardId, it.title, "") }
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
    onRequestEditAt: ((Int) -> Unit)?,
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
            MarkdownInlineText(
                content = inlineText(node, onWikilinkClick, onUrlClick, cardTags, onTagClick, linkColor),
                onRequestEditAt = onRequestEditAt,
                style = style,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        is Paragraph -> {
            // A resource reference (image or plain link syntax -- see ResourceAttachment's
            // own doc comment for why the markdown syntax used doesn't actually decide how
            // it renders) only gets special treatment when it's the paragraph's entire
            // content, matching holder-desktop's own MarkdownResourceImageController
            // restriction: mixed inline with other text, it just falls back to plain link
            // rendering below.
            val soleChild = node.firstChild?.takeIf { it === node.lastChild }
            val destination = when (soleChild) {
                is MdImage -> soleChild.destination
                is Link -> soleChild.destination
                else -> null
            }
            val resourceId = destination
                ?.takeIf { it.startsWith(HOLDER_RESOURCE_SCHEME) }
                ?.removePrefix(HOLDER_RESOURCE_SCHEME)
            if (resourceId != null && soleChild != null) {
                ResourceAttachment(
                    resourceId = resourceId,
                    label = plainText(soleChild),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            } else {
                MarkdownInlineText(
                    content = inlineText(node, onWikilinkClick, onUrlClick, cardTags, onTagClick, linkColor),
                    onRequestEditAt = onRequestEditAt,
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
                                MarkdownBlock(child, onWikilinkClick, onUrlClick, cardTags, onTagClick, onRequestEditAt)
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
                                    MarkdownBlock(child, onWikilinkClick, onUrlClick, cardTags, onTagClick, onRequestEditAt)
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
            for (child in node.children()) {
                MarkdownBlock(child, onWikilinkClick, onUrlClick, cardTags, onTagClick, onRequestEditAt)
            }
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
                        MarkdownBlock(child, onWikilinkClick, onUrlClick, cardTags, onTagClick, onRequestEditAt)
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
                                        MarkdownInlineText(
                                            content = inlineText(
                                                cell,
                                                onWikilinkClick,
                                                onUrlClick,
                                                cardTags,
                                                onTagClick,
                                                linkColor,
                                            ),
                                            onRequestEditAt = onRequestEditAt,
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
        else -> for (child in node.children()) {
            MarkdownBlock(child, onWikilinkClick, onUrlClick, cardTags, onTagClick, onRequestEditAt)
        }
    }
}

/** Every place [MarkdownBlock] renders a run of inline content (a heading, a paragraph, a table
 * cell) as plain [Text] goes through here instead, so the tap-to-edit-here gesture (see
 * click_to_edit_position.md) is written once rather than three times. [onRequestEditAt] is only
 * ever invoked for a *confirmed* tap -- released without moving past touch slop and without the
 * gesture being consumed elsewhere in the meantime -- specifically because this content sits
 * inside a scrollable container (CardViewScreen's body Column): firing on the raw down instead
 * (the way the editor's own misspelled-word popup does, where there's no ancestor scroll to
 * conflict with) would wrongly jump into the editor the instant a scroll drag started on top of
 * some rendered text. Everything here only *observes* pointer events (Initial pass, never
 * consumed), so this is fully additive -- the existing long-press-anywhere-to-edit gesture and
 * every link/tag click keep working exactly as before, land on the same down event, unaffected. */
@Composable
private fun MarkdownInlineText(
    content: InlineContent,
    onRequestEditAt: ((Int) -> Unit)?,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
) {
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val gestureModifier = if (onRequestEditAt == null) {
        Modifier
    } else {
        Modifier.pointerInput(content.sourceRuns, onRequestEditAt) {
            awaitEachGesture {
                val down = awaitFirstDown(pass = PointerEventPass.Initial)
                var isCleanTap = true
                while (true) {
                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val dx = change.position.x - down.position.x
                    val dy = change.position.y - down.position.y
                    if (change.isConsumed || dx * dx + dy * dy > viewConfiguration.touchSlop * viewConfiguration.touchSlop) {
                        isCleanTap = false
                        break
                    }
                    if (!change.pressed) break
                }
                if (!isCleanTap) return@awaitEachGesture
                val layout = textLayoutResult ?: return@awaitEachGesture
                val tappedOffset = layout.getOffsetForPosition(down.position)
                val run = content.sourceRuns.firstOrNull { tappedOffset in it.visibleRange } ?: return@awaitEachGesture
                onRequestEditAt(run.sourceStart + (tappedOffset - run.visibleRange.first))
            }
        }
    }
    Text(
        text = content.text,
        style = style,
        fontWeight = fontWeight,
        textAlign = textAlign,
        modifier = modifier.then(gestureModifier),
        onTextLayout = { textLayoutResult = it },
    )
}

/** One leaf run of literal text within an [InlineContent]'s [InlineContent.text] -- [visibleRange]
 * is where it landed in that `AnnotatedString`'s own coordinates, [sourceStart] where its first
 * character came from in the (rewritten, pre-wikilink-translation) source string. Never recorded
 * for anything inside a [Link] or a recognized `#tag` span (see `appendInline`/`appendTaggedText`)
 * -- those already do something meaningful on tap, so a hit there must never also trigger
 * tap-to-edit. See click_to_edit_position.md. */
private data class SourceRun(val visibleRange: IntRange, val sourceStart: Int)

private data class InlineContent(val text: AnnotatedString, val sourceRuns: List<SourceRun>)

@Composable
private fun inlineText(
    node: Node,
    onWikilinkClick: (String) -> Unit,
    onUrlClick: (String) -> Unit,
    cardTags: Set<String>,
    onTagClick: (String) -> Unit,
    linkColor: Color,
): InlineContent = remember(node, onWikilinkClick, onUrlClick, cardTags, onTagClick, linkColor) {
    val sourceRuns = mutableListOf<SourceRun>()
    val text = buildAnnotatedString {
        appendInline(node, this, onWikilinkClick, onUrlClick, cardTags, onTagClick, linkColor, sourceRuns)
    }
    InlineContent(text, sourceRuns)
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
    // Where `text` itself starts in the source string, or null when this literal has no source
    // span to speak of (shouldn't happen with source spans turned on, but a missing span just
    // means no run gets recorded for it -- a tap there simply won't trigger tap-to-edit, not a
    // crash).
    sourceStart: Int?,
    sourceRuns: MutableList<SourceRun>,
) {
    // Records one run for a non-tag segment actually appended to `builder` just now --
    // `textOffset` is that segment's own start within `text`, used together with [sourceStart]
    // to land on its absolute position in the source string.
    fun recordRun(visibleStart: Int, visibleEnd: Int, textOffset: Int) {
        if (sourceStart != null && visibleEnd > visibleStart) {
            sourceRuns += SourceRun(visibleStart until visibleEnd, sourceStart + textOffset)
        }
    }
    if (cardTags.isEmpty()) {
        val visibleStart = builder.length
        builder.append(text)
        recordRun(visibleStart, builder.length, 0)
        return
    }
    var last = 0
    for (match in TAG_CANDIDATE_REGEX.findAll(text)) {
        val tag = match.value.removePrefix("#").lowercase()
        if (tag !in cardTags) continue
        val segmentVisibleStart = builder.length
        builder.append(text, last, match.range.first)
        recordRun(segmentVisibleStart, builder.length, last)
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
    val tailVisibleStart = builder.length
    builder.append(text, last, text.length)
    recordRun(tailVisibleStart, builder.length, last)
}

private fun appendInline(
    node: Node,
    builder: AnnotatedString.Builder,
    onWikilinkClick: (String) -> Unit,
    onUrlClick: (String) -> Unit,
    cardTags: Set<String>,
    onTagClick: (String) -> Unit,
    linkColor: Color,
    sourceRuns: MutableList<SourceRun>,
    insideLink: Boolean = false,
) {
    var child = node.firstChild
    while (child != null) {
        when (child) {
            is MdText -> if (insideLink) {
                builder.append(child.literal)
            } else {
                appendTaggedText(
                    child.literal,
                    builder,
                    cardTags,
                    onTagClick,
                    linkColor,
                    child.sourceSpans.firstOrNull()?.inputIndex,
                    sourceRuns,
                )
            }
            is Emphasis -> {
                val start = builder.length
                appendInline(child, builder, onWikilinkClick, onUrlClick, cardTags, onTagClick, linkColor, sourceRuns, insideLink)
                builder.addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, builder.length)
            }
            is StrongEmphasis -> {
                val start = builder.length
                appendInline(child, builder, onWikilinkClick, onUrlClick, cardTags, onTagClick, linkColor, sourceRuns, insideLink)
                builder.addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, builder.length)
            }
            is Strikethrough -> {
                val start = builder.length
                appendInline(child, builder, onWikilinkClick, onUrlClick, cardTags, onTagClick, linkColor, sourceRuns, insideLink)
                builder.addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, builder.length)
            }
            is Underline -> {
                val start = builder.length
                appendInline(child, builder, onWikilinkClick, onUrlClick, cardTags, onTagClick, linkColor, sourceRuns, insideLink)
                builder.addStyle(SpanStyle(textDecoration = TextDecoration.Underline), start, builder.length)
            }
            is Code -> {
                val start = builder.length
                builder.append(child.literal)
                builder.addStyle(SpanStyle(fontFamily = FontFamily.Monospace), start, builder.length)
                if (!insideLink) {
                    child.sourceSpans.firstOrNull()?.let { span ->
                        sourceRuns += SourceRun(start until builder.length, span.inputIndex)
                    }
                }
            }
            is Link -> {
                val destination = child.destination.orEmpty()
                val start = builder.length
                appendInline(
                    child, builder, onWikilinkClick, onUrlClick, cardTags, onTagClick, linkColor, sourceRuns,
                    insideLink = true,
                )
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
            else -> appendInline(child, builder, onWikilinkClick, onUrlClick, cardTags, onTagClick, linkColor, sourceRuns, insideLink)
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

/** Internal, not private -- [team.holder.android.ui.screens.ConnectionsScreen]'s
 * Attachments list needs the same image-vs-file dispatch too, but its own row layout
 * (separate thumbnail slot, headline text, whole-row click target) doesn't fit as a
 * single black-box widget the way [ResourceAttachment] does for an inline body
 * reference -- see [rememberResourceAttachmentKind]. */
internal sealed interface ResourceAttachmentKind {
    object Loading : ResourceAttachmentKind
    object Image : ResourceAttachmentKind
    data class File(val displayName: String) : ResourceAttachmentKind
    data class Failed(val message: String) : ResourceAttachmentKind
}

/**
 * Resolves whether resourceId is image-shaped or not, by its recorded media type -- not
 * by whether the markdown that referenced it used `![...]` or `[...]` syntax, since a
 * resource attached on another platform (or one whose markdown was hand-edited) can't be
 * trusted to have used the syntax that matches its actual content. [label] is only used
 * as the [ResourceAttachmentKind.File] fallback display name if the Resource has no
 * recorded filename of its own.
 */
@Composable
internal fun rememberResourceAttachmentKind(resourceId: String, label: String): ResourceAttachmentKind {
    var kind by remember(resourceId) { mutableStateOf<ResourceAttachmentKind>(ResourceAttachmentKind.Loading) }
    LaunchedEffect(resourceId) {
        kind = runCatching {
            withContext(Dispatchers.IO) {
                HolderNative.getResource(resourceId).assets.firstOrNull() ?: error("resource has no asset")
            }
        }.fold(
            onSuccess = { asset ->
                if (asset.mediaType.startsWith("image/")) {
                    ResourceAttachmentKind.Image
                } else {
                    ResourceAttachmentKind.File(asset.originalFilename.ifBlank { label })
                }
            },
            onFailure = { failure -> ResourceAttachmentKind.Failed(failure.message ?: failure::class.java.simpleName) },
        )
    }
    return kind
}

/**
 * Renders a `holder://resource/<id>` reference as either an inline image (tap for
 * full-screen) or a generic "open externally" file row -- see
 * [rememberResourceAttachmentKind] for how that's decided. This is the one entry point
 * [MarkdownBlock]'s Paragraph branch uses for a sole-child resource reference, image or
 * not.
 */
@Composable
internal fun ResourceAttachment(resourceId: String, label: String, modifier: Modifier = Modifier) {
    when (val current = rememberResourceAttachmentKind(resourceId, label)) {
        is ResourceAttachmentKind.Loading -> Box(
            modifier = modifier.height(48.dp).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }
        is ResourceAttachmentKind.Failed -> Text(
            text = "Couldn't load attachment \"$label\": ${current.message}",
            color = MaterialTheme.colorScheme.error,
            modifier = modifier,
        )
        is ResourceAttachmentKind.Image -> {
            var viewerOpen by remember(resourceId) { mutableStateOf(false) }
            ResourceImage(resourceId = resourceId, altText = label, modifier = modifier, onClick = { viewerOpen = true })
            if (viewerOpen) {
                ResourceImageViewerDialog(resourceId = resourceId, altText = label, onDismiss = { viewerOpen = false })
            }
        }
        is ResourceAttachmentKind.File -> ResourceFileRow(resourceId = resourceId, displayName = current.displayName, modifier = modifier)
    }
}

/** A tap-to-open row for a non-image Resource -- downloads (or reuses the on-disk cache)
 * and hands the bytes to whatever app the user has installed for it, via
 * [openResourceExternally]. Failure (including "no app can open this") surfaces inline
 * rather than crashing or failing silently. */
@Composable
private fun ResourceFileRow(resourceId: String, displayName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var opening by remember(resourceId) { mutableStateOf(false) }
    var openError by remember(resourceId) { mutableStateOf<String?>(null) }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(enabled = !opening) {
                    openError = null
                    opening = true
                    scope.launch {
                        runCatching { openResourceExternally(context, resourceId) }
                            .onFailure { failure -> openError = failure.message ?: failure::class.java.simpleName }
                        opening = false
                    }
                }
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (opening) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                Icon(painterResource(R.drawable.ic_file), contentDescription = null, modifier = Modifier.size(24.dp))
            }
            Text(displayName, modifier = Modifier.padding(start = 8.dp))
        }
        openError?.let { message ->
            Text(
                text = "Couldn't open \"$displayName\": $message",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
