package dev.gmitch215.drift.studio.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class StudioScreenTest {
	private val state = sampleState()

	@Test
	fun rendersFunnelFooterAndSummaryCandidates() = runComposeUiTest {
		setContent { StudioScreen() }
		val view = StudioView.of(state, DetailLevel.SUMMARY)
		onNodeWithText("Drift Studio").assertIsDisplayed()
		view.funnel.forEach { onNodeWithText("${it.label}: ${it.count}").assertIsDisplayed() }
		view.candidates.forEach { onNodeWithText(it.text).assertIsDisplayed() }
		onNodeWithText("+${view.hidden} more").assertIsDisplayed()
		onNodeWithText(FOOTER).assertIsDisplayed()
	}

	@Test
	fun levelControlShowsMoreOfTheSameState() = runComposeUiTest {
		setContent { StudioScreen() }
		val detail = StudioView.of(state, DetailLevel.DETAIL)
		onNodeWithText("level detail").performClick()
		detail.candidates.forEach { onNodeWithText(it.text).assertExists() }
		onNodeWithText(detail.verdict).assertExists()
		val full = StudioView.of(state, DetailLevel.FULL)
		onNodeWithText("level full").performClick()
		full.candidates.forEach { onNodeWithText(it.text).assertExists() }
		full.extras.forEach { onNodeWithText(it).assertExists() }
		onNodeWithText("level summary").performClick()
		onNodeWithText("not ranked: tool.yarn.version  (absent) -> 1.22.22").assertDoesNotExist()
	}

	@Test
	fun selectorSwitchesTheSample() = runComposeUiTest {
		setContent { StudioScreen() }
		onNodeWithText("level full").performClick()
		onNodeWithText("volatile, ignored: ci.runner.region", substring = true).assertDoesNotExist()
		onNodeWithText("green vs red 37114464625").performClick()
		onNodeWithText("volatile, ignored: ci.runner.region", substring = true).assertExists()
	}

	@Test
	fun clickingACandidateShowsItsDetail() = runComposeUiTest {
		setContent { StudioScreen() }
		onNodeWithText("level detail").performClick()
		onNodeWithText("Selected candidate").assertDoesNotExist()
		val rows = StudioView.of(state, DetailLevel.DETAIL).candidates
		val row = rows.first { it.path == "deps.wrangler.version" }
		onNodeWithText(row.text).performClick()
		onNodeWithText("Selected candidate").assertExists()
		onNodeWithText("  change: changed  4.123.0 -> 4.146.0").assertExists()
	}
}
