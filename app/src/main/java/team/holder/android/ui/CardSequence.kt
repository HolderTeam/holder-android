package team.holder.android.ui

import team.holder.android.HolderCard

/** The four ordering-based connections shown for a card, alongside its explicit links:
 * Next/Previous walk the whole project by last-modified time; Follows/Precedes walk Flowboard's
 * sort_key-ordered siblings (which defaults to creation order until someone drags cards around
 * on desktop -- Android has no reorder UI of its own). Follows is dropped when it's the same
 * card as Next, and Precedes when it's the same as Previous: the two orderings frequently
 * agree (a freshly appended sibling is often also the most recently touched card), so without
 * deduping, an untouched project would show every card connected to itself under two labels. */
data class CardSequenceLinks(
    val next: HolderCard?,
    val previous: HolderCard?,
    val follows: HolderCard?,
    val precedes: HolderCard?,
)

fun cardSequenceLinks(cardId: String, parentCardId: String?, allCards: List<HolderCard>): CardSequenceLinks {
    val byRecency = allCards.sortedByDescending { it.updatedAt }
    val recencyIndex = byRecency.indexOfFirst { it.cardId == cardId }
    val next = recencyIndex.takeIf { it >= 0 }?.let { byRecency.getOrNull(it - 1) }
    val previous = recencyIndex.takeIf { it >= 0 }?.let { byRecency.getOrNull(it + 1) }

    val siblings = allCards.filter { it.parentCardId == parentCardId }
    val bySortKey = siblings.sortedWith(compareBy<HolderCard> { it.sortKey }.thenByDescending { it.updatedAt })
    val sortKeyIndex = bySortKey.indexOfFirst { it.cardId == cardId }
    // Higher sort_key comes after (newer/"follows"); lower comes before (older/"precedes").
    val newerSibling = sortKeyIndex.takeIf { it >= 0 }?.let { bySortKey.getOrNull(it + 1) }
    val olderSibling = sortKeyIndex.takeIf { it >= 0 }?.let { bySortKey.getOrNull(it - 1) }

    return CardSequenceLinks(
        next = next,
        previous = previous,
        follows = newerSibling?.takeIf { it.cardId != next?.cardId },
        precedes = olderSibling?.takeIf { it.cardId != previous?.cardId },
    )
}
