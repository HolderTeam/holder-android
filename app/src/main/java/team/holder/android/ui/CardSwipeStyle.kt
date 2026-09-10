package team.holder.android.ui

import kotlin.random.Random

/**
 * How swiping between a card and its siblings looks and feels (see CardViewPagerScreen) -- purely a
 * rendering choice layered on the same underlying gesture, sibling deck, and navigation semantics
 * regardless of which one is picked; switching styles never changes what a swipe *does*, only how
 * it looks while doing it. See holder-planning/current/swipe-styles.md for the full brief (twelve
 * styles, "serious businessman to teenager") -- the rest arrive as they're built and validated, not
 * all at once. Order here matches the brief's numbering (1 Slide, 2 Straight, 3 Stack, 4 Swing, 5
 * Stealth, ..., 11 Spin), with [SURPRISE] -- not in the brief -- last, so a future picker reads in
 * the same order the brief does.
 */
enum class HolderCardSwipeStyle(val label: String, val description: String) {
    SLIDE("Slide", "Walking on by."),
    STRAIGHT("Straight", "As the crow flies."),
    STACK("Stack", "Know what to keep"),
    SWING("Swing", "It don't mean a thing."),
    STEALTH("Stealth", "Down to business."),
    SNAP("Snap", "Snap back to reality."),
    SWAP("Swap", "Trading places."),
    SPIN("Spin", "Me right round."),
    SURPRISE("Surprise", "Life is like a box."),
}

/**
 * Where a [ResolvedSwipeStyle.Swing] hinges. [LEADING_CORNER] follows the swipe -- bottom-left when
 * heading to the next card, bottom-right when heading back -- so the card always opens the way the
 * finger is going.
 */
enum class SwingPivot {
    BASE_CENTRE,
    LEADING_CORNER
}

/**
 * A [HolderCardSwipeStyle] with its actual numbers filled in. The user-facing setting is the enum;
 * this is what the renderer in CardViewPagerScreen dispatches on. For every setting except
 * [HolderCardSwipeStyle.SURPRISE] the mapping is fixed (see [resolve]); SURPRISE rolls one of a
 * fixed, hand-tuned set of variants per gesture. Keeping the params here -- rather than as
 * constants next to the renderer -- means the fixed styles and SURPRISE's variants are the same
 * kind of thing, drawn by the same `when`.
 */
sealed interface ResolvedSwipeStyle {
    /** Untouched: HorizontalPager's own side-by-side placement is the whole effect. */
    data object Slide : ResolvedSwipeStyle

    /**
     * The neighbour waits underneath at full size/opacity; the front card slides straight off it
     * (its natural drag-follow), so the neighbour reads as having been there all along rather than
     * sliding in from the edge.
     */
    data object Straight : ResolvedSwipeStyle

    /**
     * Straight's placement, plus a small tilt on the departing front card about its centre -- the
     * physical-pile illusion. A ~10-degree version read as far too much during prototyping; the
     * fixed [HolderCardSwipeStyle.STACK] uses 3.
     */
    data class Stack(val tiltDegrees: Float) : ResolvedSwipeStyle

    /**
     * No spatial motion at all: every page cancels the pager's side-by-side placement and stays
     * put; the drag drives alpha alone, the front fading out as the neighbour fades in.
     */
    data object Stealth : ResolvedSwipeStyle

    /**
     * The front card is pinned in place and rotates about a bottom pivot, so it reads as hinged at
     * its base and swinging aside to uncover the card beneath. Rotation follows the finger.
     * Neighbour waits underneath as in [Straight].
     */
    data class Swing(val pivot: SwingPivot, val arcDegrees: Float) : ResolvedSwipeStyle

    /**
     * The departing front card keeps its natural drag-follow and the neighbour slides in normally
     * (both so the gesture stays connected to the pager); on top of that the outgoing card turns
     * [degrees] on its way off. [followsFinger] picks the direction of the turn.
     */
    data class Spin(val degrees: Float, val followsFinger: Boolean) : ResolvedSwipeStyle

    /**
     * No drawing change -- the card slides as in [Slide]. What differs is the pager's settle: a
     * bouncy spring instead of a smooth ease, so a released swipe snaps into place with a little
     * overshoot. [dampingRatio] below 1 is the bounce; [stiffness] is how fast it arrives, kept
     * high enough that a flick still feels quick. Unlike every other style this one isn't a
     * per-page transform -- it's a `flingBehavior` on the pager itself.
     */
    data class Snap(val dampingRatio: Float, val stiffness: Float) : ResolvedSwipeStyle

    /**
     * Both cards keep their natural side-by-side horizontal placement but take opposite vertical
     * arcs -- the departing card lifts away upward, the arriving one sweeps up from below -- so
     * they visibly cross without ever overlapping. [liftFraction] is the vertical travel as a
     * fraction of the card's height.
     */
    data class Swap(val liftFraction: Float) : ResolvedSwipeStyle
}

/**
 * SURPRISE's grab-bag: a fixed set of pre-tuned variants, each drawn with the given relative weight
 * (roughly: 5 = common, 2 = uncommon, 1 = rare). By family this lands near Stack ~50% / Swing ~33%
 * / Spin ~17%, so most swipes are calm and the big turns are occasional. Slide and Straight are
 * left out (nothing to vary, and Slide is the anti-surprise); Stealth is left out (its personality
 * is having none). Swing never randomises its direction -- always toward the finger; only Spin
 * varies clockwise/anticlockwise. Snap and Swap are new and stay out of the mix until they've had
 * road time.
 */
private val SURPRISE_BAG: List<Pair<ResolvedSwipeStyle, Int>> =
        listOf(
                ResolvedSwipeStyle.Stack(tiltDegrees = 1.5f) to 5,
                ResolvedSwipeStyle.Stack(tiltDegrees = 3f) to 5,
                ResolvedSwipeStyle.Stack(tiltDegrees = 6f) to 2,
                ResolvedSwipeStyle.Swing(SwingPivot.BASE_CENTRE, arcDegrees = 55f) to 5,
                ResolvedSwipeStyle.Swing(SwingPivot.LEADING_CORNER, arcDegrees = 80f) to 2,
                ResolvedSwipeStyle.Swing(SwingPivot.BASE_CENTRE, arcDegrees = 90f) to 1,
                ResolvedSwipeStyle.Spin(degrees = 360f, followsFinger = true) to 2,
                ResolvedSwipeStyle.Spin(degrees = 540f, followsFinger = false) to 1,
                ResolvedSwipeStyle.Spin(degrees = 720f, followsFinger = true) to 1,
        )

/**
 * The set of variants SURPRISE can ever produce -- exposed for tests, and as the single place that
 * says "these nine and no others".
 */
internal val surpriseVariants: List<ResolvedSwipeStyle> = SURPRISE_BAG.map { it.first }

/**
 * Fill in the numbers. Deterministic for every setting except [HolderCardSwipeStyle.SURPRISE],
 * which draws from [SURPRISE_BAG] using [random] -- call it once per gesture and hold the result
 * for that gesture's duration (see CardViewPagerScreen).
 */
fun HolderCardSwipeStyle.resolve(random: Random): ResolvedSwipeStyle =
        when (this) {
            HolderCardSwipeStyle.SLIDE -> ResolvedSwipeStyle.Slide
            HolderCardSwipeStyle.STRAIGHT -> ResolvedSwipeStyle.Straight
            HolderCardSwipeStyle.STACK -> ResolvedSwipeStyle.Stack(tiltDegrees = 3f)
            HolderCardSwipeStyle.SWING ->
                    ResolvedSwipeStyle.Swing(SwingPivot.BASE_CENTRE, arcDegrees = 70f)
            HolderCardSwipeStyle.STEALTH -> ResolvedSwipeStyle.Stealth
            HolderCardSwipeStyle.SNAP ->
                    // stiffness ~ Compose Spring.StiffnessLow; damping < 1 for the overshoot.
                    ResolvedSwipeStyle.Snap(dampingRatio = 0.6f, stiffness = 200f)
            HolderCardSwipeStyle.SWAP -> ResolvedSwipeStyle.Swap(liftFraction = 0.18f)
            HolderCardSwipeStyle.SPIN ->
                    ResolvedSwipeStyle.Spin(degrees = 360f, followsFinger = false)
            HolderCardSwipeStyle.SURPRISE -> drawSurpriseVariant(random)
        }

private fun drawSurpriseVariant(random: Random): ResolvedSwipeStyle {
    var ticket = random.nextInt(SURPRISE_BAG.sumOf { it.second })
    for ((variant, weight) in SURPRISE_BAG) {
        if (ticket < weight) return variant
        ticket -= weight
    }
    error("weighted draw fell through -- SURPRISE_BAG weights and the ticket range disagree")
}
