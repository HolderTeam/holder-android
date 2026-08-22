package team.holder.android.ui.markdown

import android.content.Context
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager

private const val SUGGESTIONS_LIMIT = 5

data class MisspelledSpan(val range: IntRange, val suggestions: List<String>)

/** One piece of text to check in isolation, e.g. the whole masked card body, or a single word
 * split out of a wikilink name -- [absoluteStart] is where [text] begins in the real buffer, so
 * a result's local offset can be translated back with a plain addition. */
data class CheckableText(val text: String, val absoluteStart: Int)

/**
 * Wraps a single [SpellCheckerSession] for the lifetime of one editor. Not usable if the device
 * has no spell checker service enabled (newSpellCheckerSession returns null in that case) --
 * [check] is then a silent no-op rather than a crash.
 *
 * Requests aren't correlated to their callback 1:1 (the platform listener is registered once for
 * the whole session, not per call): if two checks are in flight, whichever result lands last
 * wins, even if it's for an older request. Harmless here since results only ever feed transient
 * highlighting, and the next debounced check corrects any stale flash.
 */
class MarkdownSpellChecker(context: Context) {
    private var pendingItems: List<CheckableText> = emptyList()
    private var onResult: ((List<MisspelledSpan>) -> Unit)? = null

    private val listener = object : SpellCheckerSession.SpellCheckerSessionListener {
        override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {
            // Unused: we only ever call getSentenceSuggestions, never the word-level API this
            // callback answers.
        }

        override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
            if (results == null) return
            val items = pendingItems
            val spans = mutableListOf<MisspelledSpan>()
            for ((index, info) in results.withIndex()) {
                if (info == null) continue
                val absoluteStart = items.getOrNull(index)?.absoluteStart ?: continue
                for (i in 0 until info.suggestionsCount) {
                    val suggestions = info.getSuggestionsInfoAt(i) ?: continue
                    if (suggestions.suggestionsAttributes and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO == 0) {
                        continue
                    }
                    val offset = absoluteStart + info.getOffsetAt(i)
                    val length = info.getLengthAt(i)
                    val words = List(suggestions.suggestionsCount) { j -> suggestions.getSuggestionAt(j) }
                    spans += MisspelledSpan(offset until (offset + length), words)
                }
            }
            onResult?.invoke(spans)
        }
    }

    private val session: SpellCheckerSession? = runCatching {
        val manager = context.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as? TextServicesManager
        manager?.newSpellCheckerSession(null, null, listener, true)
    }.getOrNull()

    /** Each item's text is checked independently -- results are correlated back to items by
     * array position, per the platform API's own ordering guarantee, then offset by that item's
     * absoluteStart. */
    fun check(items: List<CheckableText>, onResult: (List<MisspelledSpan>) -> Unit) {
        // TextInfo throws IllegalArgumentException on an empty string.
        val nonEmpty = items.filter { it.text.isNotEmpty() }
        if (nonEmpty.isEmpty()) {
            onResult(emptyList())
            return
        }
        val activeSession = session ?: run {
            onResult(emptyList())
            return
        }
        pendingItems = nonEmpty
        this.onResult = onResult
        activeSession.getSentenceSuggestions(nonEmpty.map { TextInfo(it.text) }.toTypedArray(), SUGGESTIONS_LIMIT)
    }

    fun close() {
        session?.close()
    }
}

/** Replaces every excluded range's characters with spaces, preserving length so the spell
 * checker's returned offsets are already absolute positions in the original text -- no mapping
 * table needed. Spaces read as sentence/word breaks, not words, so they don't themselves attract
 * suggestions. */
fun maskExcludedRanges(text: String, excludedRanges: List<IntRange>): String {
    if (excludedRanges.isEmpty()) return text
    val masked = StringBuilder(text)
    for (range in excludedRanges) {
        for (i in range) {
            if (masked[i] != '\n') masked[i] = ' '
        }
    }
    return masked.toString()
}

// Zero-width boundary before an uppercase letter that follows a lowercase/digit (myWord -> my|Word),
// or before the last letter of a run of capitals followed by a lowercase (HTTPServer -> HTTP|Server).
private val CAMEL_CASE_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")

/** Splits text into individually-checkable words: first on existing whitespace, then on
 * camelCase boundaries within each whitespace-delimited run, so a wikilink written as
 * "MySecondCard" gets checked as My/Second/Card rather than flagged whole as one unknown word,
 * while one already written with spaces ("My second card") is unaffected -- each piece paired
 * with its absolute range in the original buffer ([absoluteStart] + local offset). */
fun splitIntoWords(text: String, absoluteStart: Int): List<Pair<IntRange, String>> {
    val words = mutableListOf<Pair<IntRange, String>>()

    fun addRun(runStart: Int, run: String) {
        val boundaries = listOf(0) + CAMEL_CASE_BOUNDARY.findAll(run).map { it.range.first }.toList() + listOf(run.length)
        for (i in 0 until boundaries.size - 1) {
            val start = boundaries[i]
            val stop = boundaries[i + 1]
            if (stop > start) {
                val from = absoluteStart + runStart + start
                val to = absoluteStart + runStart + stop - 1
                words += IntRange(from, to) to run.substring(start, stop)
            }
        }
    }

    var runStart = -1
    for (i in text.indices) {
        if (text[i].isWhitespace()) {
            if (runStart >= 0) addRun(runStart, text.substring(runStart, i))
            runStart = -1
        } else if (runStart < 0) {
            runStart = i
        }
    }
    if (runStart >= 0) addRun(runStart, text.substring(runStart))
    return words
}
