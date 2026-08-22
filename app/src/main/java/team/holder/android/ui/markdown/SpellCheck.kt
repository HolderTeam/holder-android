package team.holder.android.ui.markdown

import android.content.Context
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager

private const val SUGGESTIONS_LIMIT = 5

data class MisspelledSpan(val range: IntRange, val suggestions: List<String>)

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
    private var onResult: ((List<MisspelledSpan>) -> Unit)? = null

    private val listener = object : SpellCheckerSession.SpellCheckerSessionListener {
        override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {
            // Unused: we only ever call getSentenceSuggestions, never the word-level API this
            // callback answers.
        }

        override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
            val info = results?.firstOrNull() ?: return
            val spans = mutableListOf<MisspelledSpan>()
            for (i in 0 until info.suggestionsCount) {
                val suggestions = info.getSuggestionsInfoAt(i) ?: continue
                if (suggestions.suggestionsAttributes and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO == 0) {
                    continue
                }
                val offset = info.getOffsetAt(i)
                val length = info.getLengthAt(i)
                val words = List(suggestions.suggestionsCount) { j -> suggestions.getSuggestionAt(j) }
                spans += MisspelledSpan(offset until (offset + length), words)
            }
            onResult?.invoke(spans)
        }
    }

    private val session: SpellCheckerSession? = runCatching {
        val manager = context.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as? TextServicesManager
        manager?.newSpellCheckerSession(null, null, listener, true)
    }.getOrNull()

    /** text should already have non-checkable ranges (code, link destinations) masked out with
     * spaces -- see [maskExcludedRanges] -- so offsets in the result line up with the original
     * buffer without any remapping. */
    fun check(text: String, onResult: (List<MisspelledSpan>) -> Unit) {
        // TextInfo throws IllegalArgumentException on an empty string (e.g. a just-cleared or
        // fully-masked-out body) rather than just reporting no suggestions.
        if (text.isEmpty()) {
            onResult(emptyList())
            return
        }
        val activeSession = session ?: return
        this.onResult = onResult
        activeSession.getSentenceSuggestions(arrayOf(TextInfo(text)), SUGGESTIONS_LIMIT)
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
