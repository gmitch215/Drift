package dev.gmitch215.drift.studio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.gmitch215.drift.symptom.Performance
import dev.gmitch215.drift.symptom.Spread

const val RANGE_NOTE =
    "Each bar runs from the smallest to the largest sample of one run, and the dark mark is " +
        "the median. This compares the samples of one host and names no cause."

/** One bar on a shared axis that starts at 0; each position is a fraction of the axis. */
data class RangeBar(val start: Float, val end: Float, val median: Float)

object RangeBars {
    /** The bars of the two spreads, on an axis that ends at the larger maximum. */
    fun layout(first: Spread, second: Spread): Pair<RangeBar, RangeBar> {
        val axis = maxOf(first.max, second.max, 1L).toFloat()
        fun bar(s: Spread) = RangeBar(s.min / axis, s.max / axis, s.median / axis)
        return bar(first) to bar(second)
    }

    fun describe(who: String, range: Performance.Range, spread: Spread): String =
        "$who ${range.name.lowercase()}: ${spread.min} to ${spread.max} ${range.unit}, " +
            "median ${spread.median}, ${spread.count} samples"
}

/** Horizontal range bars for each measurement, one bar per run, with the median marked. */
@Composable
internal fun RangeChart(ranges: List<Performance.Range>) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.l)) {
        ranges.forEach { range ->
            val (first, second) = RangeBars.layout(range.first, range.second)
            Column(
                Modifier.testTag("range:${range.name}"),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                StudioText("${range.name} (${range.unit})")
                RangeRow("First run", RangeBars.describe("First run", range, range.first), first)
                RangeRow(
                    "Second run",
                    RangeBars.describe("Second run", range, range.second),
                    second,
                )
            }
        }
        Muted(RANGE_NOTE, style = Type.body)
    }
}

@Composable
private fun RangeRow(label: String, description: String, bar: RangeBar) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = description
        },
        horizontalArrangement = Arrangement.spacedBy(Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Muted(label, Modifier.widthIn(min = 96.dp), Type.body)
        Canvas(Modifier.weight(1f).height(16.dp)) {
            val width = size.width
            val left = bar.start * width
            val span = maxOf((bar.end - bar.start) * width, MIN_BAR.toPx())
            drawRect(palette.accent, Offset(left, 4.dp.toPx()), Size(span, 8.dp.toPx()))
            val mark = bar.median * width
            drawRect(palette.text, Offset(mark, 0f), Size(MARK.toPx(), size.height))
        }
    }
}

private val MIN_BAR = 2.dp
private val MARK = 2.dp
