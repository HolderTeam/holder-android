package team.holder.android.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import team.holder.android.HolderCard
import team.holder.android.HolderNative
import team.holder.android.ui.CenteredMessage
import team.holder.android.ui.cardDeck
import team.holder.android.ui.initialDeckPage
import team.holder.android.ui.sortKeyOrderedSiblings

/**
 * Prototype A of swipeable card navigation (see holder-planning/current/swipe_spike.md):
 * horizontal swipe moves between [cardId]'s siblings -- same parentCardId, Holder's existing
 * sort_key order (see [sortKeyOrderedSiblings]) -- while everything else about viewing a card
 * stays exactly [CardViewScreen]'s own behavior: vertical scrolling, links/tags, Edit/Child/
 * Tools, and system/predictive Back are all untouched. This wrapper never reaches into
 * CardViewScreen's internals; it only decides *which* card each page shows.
 *
 * Tapping a link or connection -- including a sibling's own Next/Previous row -- still goes
 * through [onNavigateToCard] and pushes a new back-stack entry with a fresh deck scoped to
 * wherever it lands. Only the swipe gesture is deck-internal, non-navigating state: "horizontal
 * = move between siblings in place, tap = navigate" is the whole interaction this prototype is
 * testing, and it's why swiping never calls [onNavigateToCard] itself.
 *
 * Swiping is exactly the kind of navigation MainActivity's single `selectedCardTitle` var
 * doesn't otherwise learn about -- it's only ever updated by an explicit `navController.navigate`
 * call, which a same-deck swipe deliberately never makes. [onPageChanged] is how this wrapper
 * keeps that app-level "which card is this" notion coherent regardless: called whenever the
 * pager settles on a different page, so a subsequent Edit/Tools/Connections tap on a
 * swiped-to (never explicitly navigated-to) card doesn't act on stale title/content.
 *
 * Each page's vertical scroll position survives swiping away and back "for free": CardViewScreen
 * already builds its scroll state via `rememberScrollState()`, which Compose Foundation itself
 * implements as `rememberSaveable(saver = ScrollState.Saver)`. Wrapping each page in
 * [rememberSaveableStateHolder]'s [androidx.compose.runtime.saveable.SaveableStateHolder.SaveableStateProvider],
 * keyed by that page's card id, is therefore enough to make that already-saveable state survive
 * HorizontalPager disposing an off-screen page -- no bespoke per-card scroll-state map needed.
 */
@Composable
fun CardViewPagerScreen(
    cardId: String,
    projectId: String,
    cardTitle: String,
    refreshKey: Any,
    onEdit: (cardId: String, content: String) -> Unit,
    onNavigateToCard: (cardId: String, title: String) -> Unit,
    onNavigateToTag: (tag: String) -> Unit,
    onConnectionsClick: (cardId: String) -> Unit,
    onCreateChildCard: (cardId: String) -> Unit,
    onDeleted: () -> Unit,
    onBack: () -> Unit,
    onPageChanged: (cardId: String, title: String) -> Unit = { _, _ -> },
) {
    // Keyed on cardId alone (not refreshKey): a refreshKey bump re-fetches and updates this in
    // place below rather than resetting it to null first, so an already-composed pager never
    // sees a momentary single-page deck and loses its current page/scroll state to it.
    var deck by remember(cardId) { mutableStateOf<List<HolderCard>?>(null) }

    LaunchedEffect(cardId, projectId, refreshKey) {
        val allCards = runCatching {
            withContext(Dispatchers.IO) { HolderNative.listCards(projectId) }
        }.getOrDefault(emptyList())
        // A miss (the fetch raced or failed) keeps whatever deck we already had rather than
        // collapsing a working pager down to a single placeholder page.
        deck = cardDeck(cardId, allCards) ?: deck ?: listOf(HolderCard(cardId, projectId, cardTitle, null, 0L, 0L, 0.0))
    }

    val loadedDeck = deck
    if (loadedDeck == null) {
        CenteredMessage(Modifier.fillMaxSize()) { CircularProgressIndicator() }
        return
    }

    val initialPage = remember(cardId) { initialDeckPage(cardId, loadedDeck) }
    val pagerState = rememberPagerState(initialPage = initialPage) { loadedDeck.size }
    val saveableStateHolder = rememberSaveableStateHolder()

    LaunchedEffect(pagerState, loadedDeck) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            loadedDeck.getOrNull(page)?.let { onPageChanged(it.cardId, it.title) }
        }
    }

    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        val card = loadedDeck[page]
        saveableStateHolder.SaveableStateProvider(card.cardId) {
            CardViewScreen(
                cardId = card.cardId,
                projectId = projectId,
                cardTitle = card.title,
                refreshKey = refreshKey,
                onEdit = { content -> onEdit(card.cardId, content) },
                onNavigateToCard = onNavigateToCard,
                onNavigateToTag = onNavigateToTag,
                onConnectionsClick = { onConnectionsClick(card.cardId) },
                onCreateChildCard = { onCreateChildCard(card.cardId) },
                onDeleted = onDeleted,
                onBack = onBack,
            )
        }
    }
}
