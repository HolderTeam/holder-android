package team.holder.android.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.holder.android.HolderCard
import team.holder.android.HolderNative
import team.holder.android.HolderSettings
import team.holder.android.R
import team.holder.android.ui.CenteredMessage
import team.holder.android.ui.HolderCardSwipeStyle
import team.holder.android.ui.ResolvedSwipeStyle
import team.holder.android.ui.SwingPivot
import team.holder.android.ui.cardDeck
import team.holder.android.ui.initialDeckPage
import team.holder.android.ui.resolve
import team.holder.android.ui.sortKeyOrderedSiblings

/**
 * Swipeable card navigation: horizontal swipe moves between [cardId]'s siblings -- same
 * parentCardId, Holder's existing sort_key order (see [sortKeyOrderedSiblings]) -- while
 * everything else about viewing a card stays exactly [CardViewScreen]'s own behavior: vertical
 * scrolling, links/tags, and system/predictive Back are all untouched. This wrapper never
 * reaches into CardViewScreen's internals; it only decides *which* card each page shows, and
 * (see [HolderCardSwipeStyle]) *how* the transition between them looks.
 *
 * Tapping a link or connection -- including a sibling's own Next/Previous row -- still goes
 * through [onNavigateToCard] and pushes a new back-stack entry with a fresh deck scoped to
 * wherever it lands. Only the swipe gesture is deck-internal, non-navigating state: "horizontal
 * = move between siblings in place, tap = navigate."
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
 *
 * **Visual styles** (see holder-planning/current/swipe-styles.md for the full brief): each
 * [HolderCardSwipeStyle] is a `graphicsLayer` hand-computed per page from
 * [androidx.compose.foundation.pager.PagerState.currentPageOffsetFraction] -- this only changes
 * how pages are *drawn*; HorizontalPager's own gesture handling, fling physics, and
 * page-count/settling logic are untouched by all of them, which is also why every style needs
 * `beyondViewportPageCount = 1` -- the one neighbor a style might reveal has to actually be
 * composed during the drag, not just conceptually adjacent. `zIndex` unconditionally draws the
 * front page over its neighbor: load-bearing for the styles whose neighbor sits at full
 * opacity in the same spot as the front card (it would otherwise cover the front card outright,
 * depending on whatever order HorizontalPager happens to compose pages in) and a harmless no-op
 * for the others. The one style that *isn't* a per-page transform is Snap, which draws like
 * Slide and instead swaps the pager's settle spring for a bouncy one (see [flingBehavior] below).
 *
 * The bottom action bar (Focus/Tools/Child/Edit) lives here, not in CardViewScreen: it used to
 * be part of CardViewScreen's own per-page Scaffold, which meant it visibly moved/duplicated
 * along with every card during a swipe (both the outgoing and incoming card's bars briefly
 * visible together) -- a "zoetrope" artifact independent of, and worse than, any visual style
 * above. A single persistent bar here, outside the pager's transformed per-page content
 * entirely, fixes that for every style at once (it's not part of what gets transformed), and
 * as a side effect also fixes a real bug: tapping Edit mid-drag could previously land on
 * whichever page's bar happened to be under the finger during the unsettled transition. With
 * one shared bar outside the draggable content, there's nothing to mis-tap between. [focusMode]
 * is therefore hoisted here too (CardViewScreen only reads it and reports exiting it via Back)
 * and reset per card -- swiping to a different card exits focus mode rather than carrying it
 * along, which reads as the more expected behavior. The current card's content for the Edit
 * button is fetched independently here rather than threaded up out of CardViewScreen, matching
 * the same "small, cheap, redundant local read" pattern already used elsewhere in this codebase
 * (e.g. ConnectionsSummary re-fetching the card list for itself) rather than inventing new
 * state-reporting plumbing.
 *
 * Every style's actual numbers live on [ResolvedSwipeStyle], not here -- so the "Surprise"
 * setting, which rolls a different pre-tuned variant per gesture, is drawn by the same `when`
 * as the fixed styles rather than being a special case. The roll is locked for a whole gesture:
 * [androidx.compose.foundation.pager.PagerState.isScrollInProgress] going true is the only
 * moment a fresh variant is chosen, so a drag that's flung, settled, or cancelled and re-dragged
 * keeps the look it started with.
 */
// Slingshot. While dragging, the flung card is pulled back against travel by up to
// SLINGSHOT_MAX_PULLBACK of its width, reached at SLINGSHOT_LOAD_WINDOW of a drag and held --
// drawing the band. On release (a real event, off the pager's interaction source) an Animatable
// fires the card SLINGSHOT_FLYOFF widths off screen with SLINGSHOT_LAUNCH_VELOCITY (widths/sec,
// scaled by how far it was pulled), so it whips away on its own clock rather than riding the
// pager's decay. A release short of SLINGSHOT_COMMIT_OFFSET just snaps back, no launch.
private const val SLINGSHOT_LOAD_WINDOW = 0.35f
private const val SLINGSHOT_MAX_PULLBACK = 0.32f
private const val SLINGSHOT_FLYOFF = 2.6f
private const val SLINGSHOT_LAUNCH_VELOCITY = 9f
private const val SLINGSHOT_COMMIT_OFFSET = 0.06f

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
    val context = LocalContext.current
    val swipeStyle by HolderSettings.cardSwipeStyle(context).collectAsState(initial = HolderCardSwipeStyle.SLIDE)

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
    val currentCard = loadedDeck[pagerState.currentPage.coerceIn(loadedDeck.indices)]

    // The look this gesture is drawn with. For every setting but Surprise this is a fixed
    // mapping; Surprise re-rolls it each time a scroll begins (see the doc comment above for why
    // that boundary, not per-frame). Re-keyed on swipeStyle so switching the setting takes
    // effect immediately rather than at the next scroll.
    var resolvedStyle by remember(swipeStyle) { mutableStateOf(swipeStyle.resolve(Random.Default)) }
    LaunchedEffect(pagerState, swipeStyle) {
        snapshotFlow { pagerState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) resolvedStyle = swipeStyle.resolve(Random.Default)
        }
    }

    // Snap changes how the pager *settles* rather than how a page is drawn -- an instant cut.
    // (Slingshot also overrides the settle, but by driving animateScrollToPage itself below.)
    val flingBehavior = PagerDefaults.flingBehavior(
        state = pagerState,
        snapAnimationSpec = if (resolvedStyle == ResolvedSwipeStyle.Snap) {
            snap()
        } else {
            spring(stiffness = Spring.StiffnessMediumLow)
        },
    )

    // Which way this gesture is going, captured from the first non-zero offset after a scroll
    // starts (currentPageOffsetFraction flips sign at the page change, so a live read isn't
    // stable). Slingshot needs it to tell the card being flung from the one arriving.
    var draggingForward by remember { mutableStateOf(true) }
    LaunchedEffect(pagerState, swipeStyle) {
        if (swipeStyle != HolderCardSwipeStyle.SLINGSHOT) return@LaunchedEffect
        var armed = true
        snapshotFlow { pagerState.isScrollInProgress to pagerState.currentPageOffsetFraction }
            .collect { (scrolling, fraction) ->
                if (!scrolling) {
                    armed = true
                } else if (armed && fraction != 0f) {
                    draggingForward = fraction > 0f
                    armed = false
                }
            }
    }

    // Slingshot's launch. slingLaunch is the flung card's on-screen position in card-widths
    // while firing (the graphicsLayer cancels the pager under it); `slingLaunching` says whether
    // to use it instead of the held pull-back. Kicked from a real release event
    // (DragInteraction.Stop off the pager's own gesture source), and it *also* drives the page
    // change itself -- the pull-back makes the card look stuck, so the drag distance the pager
    // sees is never enough to commit on its own.
    val slingLaunch = remember { Animatable(0f) }
    var slingLaunching by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState, swipeStyle, loadedDeck.size) {
        if (swipeStyle != HolderCardSwipeStyle.SLINGSHOT) return@LaunchedEffect
        pagerState.interactionSource.interactions.collect { interaction ->
            if (interaction !is DragInteraction.Stop) return@collect
            val pulled = abs(pagerState.currentPageOffsetFraction)
            if (pulled < SLINGSHOT_COMMIT_OFFSET) return@collect
            val target = (pagerState.currentPage + if (draggingForward) 1 else -1)
                .coerceIn(0, loadedDeck.lastIndex)
            if (target == pagerState.currentPage) return@collect // at the deck edge, nowhere to fling
            val travel = if (draggingForward) -1f else 1f
            val power = pulled.coerceIn(0f, 0.5f) / 0.5f
            slingLaunching = true
            // Start where the held pull-back had it so nothing hops when the Animatable takes over.
            slingLaunch.snapTo(
                -travel * (pulled / SLINGSHOT_LOAD_WINDOW).coerceIn(0f, 1f) * SLINGSHOT_MAX_PULLBACK,
            )
            val pageChange = launch {
                pagerState.animateScrollToPage(
                    target,
                    animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow),
                )
            }
            slingLaunch.animateTo(
                targetValue = travel * SLINGSHOT_FLYOFF,
                initialVelocity = travel * SLINGSHOT_LAUNCH_VELOCITY * (0.5f + power),
                animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow),
            )
            pageChange.join() // keep the flung card parked off-screen until the pager has settled
            slingLaunching = false
            slingLaunch.snapTo(0f)
        }
    }

    LaunchedEffect(pagerState, loadedDeck) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            loadedDeck.getOrNull(page)?.let { onPageChanged(it.cardId, it.title) }
        }
    }

    // Reset per card rather than carried across a swipe -- see the doc comment above.
    var focusMode by remember(currentCard.cardId) { mutableStateOf(false) }

    // Independent of CardViewScreen's own content fetch: just enough to know whether Edit has
    // something to hand onEdit yet. See the doc comment above for why this is a second small
    // fetch rather than plumbing CardViewScreen's own loaded state upward.
    var currentCardContent by remember(currentCard.cardId) { mutableStateOf<String?>(null) }
    LaunchedEffect(currentCard.cardId, refreshKey) {
        currentCardContent = runCatching {
            withContext(Dispatchers.IO) { HolderNative.getCardContent(currentCard.cardId) }
        }.getOrNull()
    }

    Scaffold(
        bottomBar = {
            if (!focusMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .navigationBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    CardViewActionButton(
                        icon = {
                            Icon(painterResource(R.drawable.ic_fullscreen), contentDescription = "Focus mode")
                        },
                        label = "Focus",
                        onClick = { focusMode = true },
                    )
                    CardViewActionButton(
                        icon = { Icon(painterResource(R.drawable.ic_flask), contentDescription = "Tools") },
                        label = "Tools",
                        onClick = { onConnectionsClick(currentCard.cardId) },
                    )
                    CardViewActionButton(
                        icon = { Icon(Icons.Filled.Add, contentDescription = "New child card") },
                        label = "Child",
                        onClick = { onCreateChildCard(currentCard.cardId) },
                    )
                    CardViewActionButton(
                        icon = { Icon(Icons.Filled.Edit, contentDescription = "Edit") },
                        label = "Edit",
                        onClick = { currentCardContent?.let { onEdit(currentCard.cardId, it) } },
                    )
                }
            }
        },
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            // Only the bottom inset -- this Scaffold has no topBar of its own, so its top
            // component would otherwise double-reserve status-bar height on top of what each
            // page's own nested TopAppBar already accounts for.
            modifier = Modifier.fillMaxSize().padding(bottom = innerPadding.calculateBottomPadding()),
            beyondViewportPageCount = 1,
            flingBehavior = flingBehavior,
        ) { page ->
            val card = loadedDeck[page]
            val isFront = page == pagerState.currentPage
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Front-over-neighbor draw order: load-bearing for Straight/Stack/Swing,
                    // whose neighbor sits fully opaque in the same spot as the front card; a
                    // no-op for Slide/Spin/Snap/Slingshot (pages don't meaningfully overlap),
                    // Surf (the two cards are vertically separated the whole time) and Stealth
                    // (alpha alone already reads as front/back regardless of z-order).
                    .zIndex(if (isFront) 1f else 0f)
                    .graphicsLayer {
                        val offset = (page - pagerState.currentPage) - pagerState.currentPageOffsetFraction
                        when (val style = resolvedStyle) {
                            ResolvedSwipeStyle.Slide -> {
                                // Untouched: HorizontalPager's own side-by-side placement is
                                // the whole effect.
                            }
                            ResolvedSwipeStyle.Straight -> {
                                // The front card keeps its natural 1:1 drag-follow placement --
                                // "slides straight off." The neighbor cancels its own
                                // side-by-side placement (putting it exactly where the front
                                // card is) and stays at full size/opacity throughout, so it
                                // reads as having been there underneath the whole time rather
                                // than sliding in from the edge.
                                if (!isFront) translationX = -offset * size.width
                            }
                            is ResolvedSwipeStyle.Stack -> {
                                // Straight's placement, plus a light tilt on the departing
                                // front card -- the physical-pile illusion.
                                if (isFront) {
                                    rotationZ = -offset * style.tiltDegrees
                                } else {
                                    translationX = -offset * size.width
                                }
                            }
                            is ResolvedSwipeStyle.Swing -> {
                                // The neighbor waits underneath (as in Straight/Stack). The
                                // front card is pinned in place -- translationX cancels the
                                // pager's own drag-follow drift -- and instead rotates about a
                                // bottom pivot, so it reads as hinged at its base and swinging
                                // aside to uncover the card below, not sliding off.
                                if (isFront) {
                                    translationX = -offset * size.width
                                    val pivotX = when (style.pivot) {
                                        SwingPivot.BASE_CENTRE -> 0.5f
                                        // The corner the swipe is heading toward, so the card
                                        // still opens the way the finger is going.
                                        SwingPivot.LEADING_CORNER -> if (offset < 0f) 0f else 1f
                                    }
                                    transformOrigin = TransformOrigin(pivotX, 1f)
                                    // Sign follows the finger: swiping toward the next card
                                    // swings the card's top the same way, opening it aside like
                                    // a hinged flap rather than against the drag.
                                    rotationZ = offset * style.arcDegrees
                                } else {
                                    translationX = -offset * size.width
                                }
                            }
                            ResolvedSwipeStyle.Stealth -> {
                                // No spatial motion at all: every page cancels HorizontalPager's
                                // own side-by-side placement and stays put; dragging drives
                                // alpha alone; front fades out as the neighbor fades in.
                                translationX = -offset * size.width
                                alpha = (1f - abs(offset)).coerceIn(0f, 1f)
                            }
                            is ResolvedSwipeStyle.Spin -> {
                                // The neighbor slides in normally (untouched, as in Slide) so
                                // the gesture still feels connected to the pager. The departing
                                // front card keeps that natural drag-follow too, and on top of
                                // it turns on its way out -- toward or against the finger per
                                // the variant.
                                if (isFront) {
                                    val direction = if (style.followsFinger) 1f else -1f
                                    rotationZ = direction * offset * style.degrees
                                }
                            }
                            ResolvedSwipeStyle.Snap -> {
                                // Nothing per-page -- draws like Slide. The character is all in
                                // the pager's settle spec (see flingBehavior above).
                            }
                            is ResolvedSwipeStyle.Surf -> {
                                // Both cards keep their native side-by-side horizontal placement
                                // (as in Slide); they just take opposite vertical arcs -- the
                                // front lifts away upward, the neighbor swells up from below --
                                // so the two ride past each other like a wave, never overlapping.
                                translationY =
                                    (if (isFront) -1f else 1f) *
                                        abs(offset).coerceIn(0f, 1f) *
                                        style.liftFraction *
                                        size.height
                            }
                            ResolvedSwipeStyle.Slingshot -> {
                                // Only the card being flung gets moved -- the one whose offset
                                // points away from its slot in the drag direction (offset = page
                                // - absolute scroll position, so its sign holds through the
                                // mid-fling page-flip). Every other page keeps its natural
                                // placement: the arriving card just slides in and does the bouncy
                                // settle.
                                //
                                // The loading and firing branches both start by *cancelling*
                                // the pager's placement (`-offset * width`) so the card doesn't
                                // track the finger at all, then position it explicitly:
                                //   - Loading (|offset| in the window): held pulled back against
                                //     travel, ramping to a maximum -- drawing the band. The card
                                //     resists rather than following.
                                //   - Firing (slingLaunching): the Animatable drives position,
                                //     whipping off screen on its own clock.
                                //   - Otherwise (fast flick that skipped the launch, or the
                                //     spent card afterwards): natural pager placement.
                                val beingFlung = if (draggingForward) offset < 0f else offset > 0f
                                if (beingFlung) {
                                    val mag = abs(offset).coerceIn(0f, 1f)
                                    val awayFromTravel = if (offset < 0f) 1f else -1f
                                    translationX = when {
                                        slingLaunching ->
                                            -offset * size.width + slingLaunch.value * size.width
                                        mag < SLINGSHOT_LOAD_WINDOW ->
                                            -offset * size.width +
                                                awayFromTravel * (mag / SLINGSHOT_LOAD_WINDOW) *
                                                SLINGSHOT_MAX_PULLBACK * size.width
                                        else -> 0f
                                    }
                                }
                            }
                        }
                    },
            ) {
                saveableStateHolder.SaveableStateProvider(card.cardId) {
                    CardViewScreen(
                        cardId = card.cardId,
                        projectId = projectId,
                        cardTitle = card.title,
                        refreshKey = refreshKey,
                        // Only the front page's focus state/exit is real; a composed-but-not-current
                        // neighbor (beyondViewportPageCount = 1) can't be in focus mode at all.
                        focusMode = isFront && focusMode,
                        onExitFocusMode = { if (isFront) focusMode = false },
                        onNavigateToCard = onNavigateToCard,
                        onNavigateToTag = onNavigateToTag,
                        onDeleted = onDeleted,
                        onBack = onBack,
                    )
                }
            }
        }
    }
}
