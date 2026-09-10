package team.holder.android.ui

/** How swiping between a card and its siblings looks and feels (see CardViewPagerScreen) --
 * purely a rendering choice layered on the same underlying gesture, sibling deck, and
 * navigation semantics regardless of which one is picked; switching styles never changes what
 * a swipe *does*, only how it looks while doing it. See holder-planning/current/swipe-styles.md
 * for the full brief (twelve styles, "serious businessman to teenager") -- the rest arrive as
 * they're built and validated, not all at once. Order here matches the brief's numbering
 * (1 Slide, 2 Straight, 3 Stack, 4 Swing, 5 Stealth, ..., 11 Spin) so a future picker reads in
 * the same order the brief does. */
enum class HolderCardSwipeStyle(val label: String, val description: String) {
    SLIDE("Slide", "The classic side-by-side strip -- the least surprising option."),
    STRAIGHT("Straight", "The next card is already there; this one slides straight off it."),
    STACK("Stack", "Thumbing a card off the top of a small pile."),
    SWING("Swing", "The card swings away on a hinge, pinned at its base."),
    STEALTH("Stealth", "A quiet crossfade -- no motion, the next card just appears."),
    SPIN("Spin", "The outgoing card does a full turn on its way off -- showing off."),
}
