package dev.gmitch215.drift.studio.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class StudioScreenTest {
    private val state = sampleState()

    @Test
    fun leadsWithAPlainResultThenTheDifferencesAndKeepsRawOutputHidden() = runComposeUiTest {
        setContent { StudioScreen() }
        val reading = Plain.reading(state)
        val view = StudioView.of(state, DetailLevel.SUMMARY)
        onNodeWithText("What Changed Between Two Runs").assertIsDisplayed()
        onNodeWithText("What to do next", substring = true).assertExists()
        onNodeWithTag("summary").assertTextEquals(reading.headline)
        onNodeWithTag("evidence").assertTextEquals(reading.evidence!!)
        onNodeWithText(Plain.CAUTION).assertExists()
        onNodeWithText(Glossary.TIER).assertExists()
        view.candidates.forEach { onNodeWithTag("candidate:${it.path}").assertExists() }
        onNodeWithTag("candidate:${view.candidates.first().path}")
            .assertTextContains("Evidence level", substring = true)
        view.funnel.forEach { onNodeWithTag("funnel:${it.label}").assertDoesNotExist() }
        onNodeWithText("Show Technical Details").assertExists()
        onNodeWithText(FOOTER).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun technicalDetailsHoldTheFunnelAndTheOtherFindings() = runComposeUiTest {
        setContent { StudioScreen() }
        onNodeWithText("Show Technical Details").performScrollTo().performClick()
        onNodeWithText("Hide Technical Details").assertExists()
        val view = StudioView.of(state, DetailLevel.SUMMARY)
        view.funnel.forEach {
            val name = it.label.replaceFirstChar { c -> c.uppercase() }
            onNodeWithTag("funnel:${it.label}").performScrollTo()
                .assertTextEquals(name, "${it.count}")
        }
        StudioView.of(state, DetailLevel.FULL).extras.forEach {
            onNodeWithText(it).performScrollTo().assertExists()
        }
        onNodeWithText("Hide Technical Details").performScrollTo().performClick()
        onNodeWithTag("funnel:facts").assertDoesNotExist()
    }

    @Test
    fun showMoreListsEveryDifference() = runComposeUiTest {
        setContent { StudioScreen() }
        val summary = StudioView.of(state, DetailLevel.SUMMARY)
        val detail = StudioView.of(state, DetailLevel.DETAIL)
        onNodeWithTag("show-all").assertTextEquals("Show ${summary.hidden} More Differences")
        onNodeWithTag("candidate:deps.wrangler.version").assertDoesNotExist()
        onNodeWithTag("show-all").performScrollTo().performClick()
        detail.candidates.forEach { onNodeWithTag("candidate:${it.path}").assertExists() }
        onNodeWithTag("candidate:deps.wrangler.version")
            .assertTextContains("4.123.0 -> 4.146.0", substring = true)
        onNodeWithTag("show-all").assertTextEquals("Show Fewer Differences")
        onNodeWithTag("show-all").performScrollTo().performClick()
        onNodeWithTag("candidate:deps.wrangler.version").assertDoesNotExist()
    }

    @Test
    fun selectorSwitchesTheSample() = runComposeUiTest {
        setContent { StudioScreen() }
        val other = samples()[1]
        onNodeWithText(other.name).assertIsNotSelected()
        onNodeWithText(other.name).performClick()
        onNodeWithText(other.name).assertIsSelected()
        onNodeWithTag("summary").assertTextEquals(Plain.reading(other.state).headline)
    }

    @Test
    fun clickingADifferenceShowsItsEvidenceAndClickingAgainHidesIt() = runComposeUiTest {
        setContent { StudioScreen() }
        onNodeWithTag("show-all").performScrollTo().performClick()
        onNodeWithText("Technical Details").assertDoesNotExist()
        val row = onNodeWithTag("candidate:deps.wrangler.version")
        row.performScrollTo().performClick()
        row.assertIsSelected()
        onNodeWithText("Technical Details").assertExists()
        val tier = state.relevant.first { it.path == "deps.wrangler.version" }.tier
        onNodeWithText(Plain.tier(tier).phrase).assertExists()
        onNodeWithText("change: changed  4.123.0 -> 4.146.0").assertExists()
        row.performScrollTo().performClick()
        onNodeWithText("Technical Details").assertDoesNotExist()
    }

    @Test
    fun anEmptyRankingSaysSoInsteadOfShowingAnEmptyList() = runComposeUiTest {
        val green = samples().first().green
        setContent { StudioScreen(listOf(Sample("Same Run", green, green))) }
        onNodeWithText("No difference between these runs is ranked.").assertExists()
        onNodeWithTag("summary").assertTextContains("No difference", substring = true)
        onNodeWithTag("show-all").assertDoesNotExist()
    }

    @Test
    fun darkThemeRendersTheSameScreen() = runComposeUiTest {
        setContent { StudioTheme(dark = true) { StudioScreen() } }
        onNodeWithText("What Changed Between Two Runs").assertExists()
    }
}
