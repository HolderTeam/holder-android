package team.holder.android.ui

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardSwipeStyleTest {

    @Test
    fun fixedStyles_resolveToTheirCanonicalParams() {
        val rng = Random(1)
        assertEquals(ResolvedSwipeStyle.Slide, HolderCardSwipeStyle.SLIDE.resolve(rng))
        assertEquals(ResolvedSwipeStyle.Straight, HolderCardSwipeStyle.STRAIGHT.resolve(rng))
        assertEquals(ResolvedSwipeStyle.Stealth, HolderCardSwipeStyle.STEALTH.resolve(rng))
        assertEquals(ResolvedSwipeStyle.Stack(tiltDegrees = 3f), HolderCardSwipeStyle.STACK.resolve(rng))
        assertEquals(
            ResolvedSwipeStyle.Swing(SwingPivot.BASE_CENTRE, arcDegrees = 70f),
            HolderCardSwipeStyle.SWING.resolve(rng),
        )
        assertEquals(
            ResolvedSwipeStyle.Spin(degrees = 360f, followsFinger = false),
            HolderCardSwipeStyle.SPIN.resolve(rng),
        )
        assertEquals(ResolvedSwipeStyle.Snap, HolderCardSwipeStyle.SNAP.resolve(rng))
        assertEquals(ResolvedSwipeStyle.Surf(liftFraction = 0.18f), HolderCardSwipeStyle.SURF.resolve(rng))
        assertEquals(ResolvedSwipeStyle.Slingshot, HolderCardSwipeStyle.SLINGSHOT.resolve(rng))
    }

    @Test
    fun surprise_bagExcludesTheUnproven() {
        // Snap, Surf and Slingshot ride in the picker only (see SURPRISE_BAG's comment).
        assertTrue(
            surpriseVariants.none {
                it == ResolvedSwipeStyle.Snap ||
                    it is ResolvedSwipeStyle.Surf ||
                    it == ResolvedSwipeStyle.Slingshot
            },
        )
    }

    @Test
    fun fixedStyles_ignoreTheRandomSource() {
        // Same setting, wildly different RNGs -> identical result.
        for (style in HolderCardSwipeStyle.entries - HolderCardSwipeStyle.SURPRISE) {
            assertEquals(style.resolve(Random(1)), style.resolve(Random(999_999)))
        }
    }

    @Test
    fun surprise_onlyEverProducesTheKnownVariants() {
        val rng = Random(42)
        repeat(5_000) {
            val rolled = HolderCardSwipeStyle.SURPRISE.resolve(rng)
            assertTrue("unexpected SURPRISE variant: $rolled", rolled in surpriseVariants)
        }
    }

    @Test
    fun surprise_eventuallyProducesEveryVariant() {
        val rng = Random(7)
        val seen = buildSet {
            repeat(20_000) { add(HolderCardSwipeStyle.SURPRISE.resolve(rng)) }
        }
        assertEquals(surpriseVariants.toSet(), seen)
    }

    @Test
    fun surprise_familyMixIsCalmLeaning() {
        val rng = Random(123)
        val total = 60_000
        var stack = 0
        var swing = 0
        var spin = 0
        repeat(total) {
            when (HolderCardSwipeStyle.SURPRISE.resolve(rng)) {
                is ResolvedSwipeStyle.Stack -> stack++
                is ResolvedSwipeStyle.Swing -> swing++
                is ResolvedSwipeStyle.Spin -> spin++
                else -> error("SURPRISE produced a variant outside the Stack/Swing/Spin families")
            }
        }
        // Weighted bag is Stack 12 / Swing 8 / Spin 4 out of 24 -> 50% / 33% / 17%. Loose bounds,
        // just enough to catch a bag that has been reshaped into something wild-leaning.
        assertTrue("stack share $stack/$total", stack.toDouble() / total in 0.40..0.60)
        assertTrue("swing share $swing/$total", swing.toDouble() / total in 0.24..0.42)
        assertTrue("spin share $spin/$total", spin.toDouble() / total in 0.10..0.25)
    }
}
