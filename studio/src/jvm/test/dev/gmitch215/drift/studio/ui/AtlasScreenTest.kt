package dev.gmitch215.drift.studio.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.gmitch215.drift.fixtures.AtlasFixtures
import dev.gmitch215.drift.scan.atlas.Dataset
import dev.gmitch215.drift.scan.probe.kotlin.KotlinCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AtlasScreenTest {
	private val dataset = AtlasData.dataset
	private val boundaries = "kotlin.double.tostring-boundaries"
	private val fixture = { d: Dataset ->
		Devices.of("this JVM", AtlasFixtures.text("macos.txt"), d)
	}

	private fun ComposeUiTest.show(measure: (Dataset) -> Device = fixture) {
		setContent { AtlasScreen(dataset, measure) }
		waitUntil { onAllNodesWithText("measuring this device...").fetchSemanticsNodes().isEmpty() }
	}

	private fun wasm(v: AtlasView) =
		v.rows.first { it.id == boundaries }.cells.first { it.target == "wasm" }

	private fun view(version: String = "2.4.20") = AtlasView.of(AtlasState(dataset, version))

	@Test
	fun matrixRendersEveryProbeWithDivergenceMarked() = runComposeUiTest {
		show()
		val v = view()
		v.rows.forEach {
			val mark = if (it.divergent) "* " else "  "
			onNodeWithTag("row:${it.id}").assertTextEquals(mark + it.name)
		}
		onNodeWithTag("count")
			.assertTextEquals("23 of 38 probes differ between targets on kotlin 2.4.20")
		onNodeWithText(ATLAS_FOOTER).assertExists()
		onNodeWithTag("badge:kotlin.double.parse")
			.assertTextEquals("unclassified: no explanation found")
		onNodeWithTag("badge:kotlin.collections.iterator-modification")
			.assertTextEquals("platform-defined")
	}

	@Test
	fun studioAppMeasuresThroughTheFunctionItIsGiven() = runComposeUiTest {
		var calls = 0
		val counted = { d: Dataset ->
			calls++
			fixture(d)
		}
		setContent { StudioApp(dataset, measure = counted) }
		waitUntil { onAllNodesWithText("measuring this device...").fetchSemanticsNodes().isEmpty() }
		assertEquals(1, calls)
	}

	@Test
	fun versionSelectorNeverMergesVersions() = runComposeUiTest {
		show()
		val old = wasm(view("2.4.20"))
		val new = wasm(view("2.5.0-Beta1"))
		onNodeWithTag("cell:$boundaries:wasm").assertTextEquals(old.short)
		onNodeWithTag("version:2.5.0-Beta1").performClick()
		onNodeWithTag("count")
			.assertTextEquals("18 of 38 probes differ between targets on kotlin 2.5.0-Beta1")
		onNodeWithTag("cell:$boundaries:wasm").assertTextEquals(new.short)
		assertNotEquals(old.short, new.short)
	}

	@Test
	fun divergentFilterHidesIdenticalRows() = runComposeUiTest {
		show()
		onNodeWithTag("row:kotlin.random.seeded").assertExists()
		onNodeWithTag("filter:divergent").performClick()
		onNodeWithTag("row:kotlin.random.seeded").assertDoesNotExist()
		onNodeWithTag("row:$boundaries").assertExists()
		onNodeWithText("15 identical probes hidden").assertIsDisplayed()
		onNodeWithTag("filter:divergent").performClick()
		onNodeWithTag("row:kotlin.random.seeded").assertExists()
	}

	@Test
	fun detailLevelsAddTextOnly() = runComposeUiTest {
		show()
		val question = dataset.probe(boundaries)!!.question
		onNodeWithText(question).assertDoesNotExist()
		onNodeWithText("level detail").performClick()
		onNodeWithText(question).assertExists()
		onNodeWithText("level full").performClick()
		val cited = onAllNodesWithText("linked issues: KT-88414", substring = true)
		assertTrue(cited.fetchSemanticsNodes().isNotEmpty())
		onNodeWithText("level summary").performClick()
		onNodeWithText(question).assertDoesNotExist()
	}

	@Test
	fun tappingARowShowsTheFullResults() = runComposeUiTest {
		show()
		val row = onNodeWithTag("row:kotlin.collections.iterator-modification")
		onNodeWithText("Selected probe").assertDoesNotExist()
		row.performScrollTo().performClick()
		onNodeWithText("Selected probe").assertExists()
		onNodeWithText("  jvm on kotlin 2.4.20:").assertExists()
		onNodeWithText("  linked issues: KT-88775 (Open), KT-89031 (To be discussed)")
			.assertExists()
		row.performScrollTo().performClick()
		onNodeWithText("Selected probe").assertDoesNotExist()
	}

	@Test
	fun thisDeviceColumnIsLiveAndLabelledForTheJvm() = runComposeUiTest {
		show(measure = { Devices.measure(it) })
		onNodeWithTag("device-header").assertTextEquals("this JVM")
		val own = Devices.measure(dataset)
		assertEquals(KotlinCatalog.all.size, own.measured)
		val expected = AtlasView.of(AtlasState(dataset, device = own))
			.rows.first { it.id == boundaries }
		onNodeWithTag("cell:$boundaries:device").assertTextEquals(expected.device!!.short)
		onNodeWithTag("vs:$boundaries").assertTextEquals(expected.deviceVerdict!!)
		onNodeWithText("measured in", substring = true).assertExists()
		onNodeWithTag("export-text").assertDoesNotExist()
		onNodeWithTag("export").performScrollTo().performClick()
		onNodeWithTag("export-text").assertExists()
	}

	@Test
	fun predictRevealFlowKeepsAScore() = runComposeUiTest {
		show()
		onNodeWithTag("mode:predict").performClick()
		onNodeWithTag("score").assertTextEquals("score: 0 correct, 0 wrong, 0 skipped")
		val first = PredictView.of(PredictState(dataset))!!
		onNodeWithTag("prompt").assertTextEquals(first.prompt)
		onNodeWithText(first.question).assertExists()
		onNodeWithTag("choice:${first.choices.indexOfFirst { first.target in it.targets }}")
			.performScrollTo().performClick()
		onNodeWithTag("verdict").assertTextEquals("correct")
		onNodeWithTag("score").assertTextEquals("score: 1 correct, 0 wrong, 0 skipped")
		onNodeWithTag("next").performScrollTo().performClick()
		val second = PredictView.of(PredictState(dataset, position = 1))!!
		onNodeWithTag("prompt").assertTextEquals(second.prompt)
		onNodeWithTag("choice:unknown").performScrollTo().performClick()
		onNodeWithTag("verdict").assertTextEquals("skipped")
		onNodeWithTag("score").assertTextEquals("score: 1 correct, 0 wrong, 1 skipped")
	}

	@Test
	fun theAppOpensOnTheAtlasWithInvestigationAsASecondTab() = runComposeUiTest {
		setContent { StudioApp(dataset) }
		onNodeWithText("Drift Atlas").assertExists()
		onNodeWithText("> Atlas").assertExists()
		onNodeWithText("Drift Studio").assertDoesNotExist()
		onNodeWithText("Investigation").performClick()
		onNodeWithText("Drift Studio").assertExists()
		onNodeWithText("Drift Atlas").assertDoesNotExist()
	}
}
