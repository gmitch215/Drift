package dev.gmitch215.drift.studio.ui

import dev.gmitch215.drift.symptom.Performance
import dev.gmitch215.drift.symptom.Spread
import kotlin.test.Test
import kotlin.test.assertEquals

class RangeBarsTest {
    @Test
    fun bothBarsShareOneAxisThatEndsAtTheLargerMaximum() {
        val (first, second) = RangeBars.layout(Spread(5, 100, 101, 103), Spread(5, 200, 250, 400))
        assertEquals(RangeBar(0.25f, 0.2575f, 0.2525f), first)
        assertEquals(RangeBar(0.5f, 1f, 0.625f), second)
    }

    @Test
    fun aSingleValueHasAZeroWidthRangeAndAnEmptyAxisDoesNotDivideByZero() {
        val (a, b) = RangeBars.layout(Spread(3, 50, 50, 50), Spread(3, 100, 100, 100))
        assertEquals(RangeBar(0.5f, 0.5f, 0.5f), a)
        assertEquals(RangeBar(1f, 1f, 1f), b)
        val (zero, _) = RangeBars.layout(Spread(2, 0, 0, 0), Spread(2, 0, 0, 0))
        assertEquals(RangeBar(0f, 0f, 0f), zero)
    }

    @Test
    fun theDescriptionNamesTheRunTheRangeTheMedianAndTheCount() {
        val range = Performance.Range("Wall time", "ms", Spread(5, 1, 2, 3), Spread(5, 4, 5, 6))
        assertEquals(
            "Second run wall time: 4 to 6 ms, median 5, 5 samples",
            RangeBars.describe("Second run", range, range.second),
        )
    }
}
