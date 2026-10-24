package dev.gmitch215.drift.studio.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.gmitch215.drift.symptom.Performance
import dev.gmitch215.drift.symptom.Spread
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class RangeChartTest {
    private val ranges = listOf(
        Performance.Range("Wall time", "ms", Spread(5, 100, 101, 103), Spread(5, 249, 250, 252)),
        Performance.Range("Peak memory", "KiB", Spread(5, 10, 11, 12), Spread(5, 20, 21, 22)),
    )

    @Test
    fun showsOneRangeBarPerRunAndSaysItNamesNoCause() = runComposeUiTest {
        val state = sampleState().copy(performance = Performance(emptyList(), emptyList(), ranges))
        setContent { StudioTheme { InvestigationResult(state) } }
        onNodeWithText("Run Time and Memory Ranges").assertExists()
        onNodeWithTag("range:Wall time").assertExists()
        onNodeWithTag("range:Peak memory").assertExists()
        onNodeWithContentDescription("First run wall time: 100 to 103 ms, median 101, 5 samples")
            .assertExists()
        onNodeWithContentDescription("Second run wall time: 249 to 252 ms, median 250, 5 samples")
            .assertExists()
        onNodeWithText(RANGE_NOTE).assertExists()
    }

    @Test
    fun showsNothingWithoutPerformanceData() = runComposeUiTest {
        setContent { StudioTheme { InvestigationResult(sampleState()) } }
        onNodeWithText("Run Time and Memory Ranges").assertDoesNotExist()
        onNodeWithText(RANGE_NOTE).assertDoesNotExist()
    }

    @Test
    fun showsNothingWhenThePerformanceDataHasNoRanges() = runComposeUiTest {
        val none = sampleState().copy(performance = Performance(listOf("x"), emptyList()))
        setContent { StudioTheme { InvestigationResult(none) } }
        onNodeWithText("Run Time and Memory Ranges").assertDoesNotExist()
    }
}
