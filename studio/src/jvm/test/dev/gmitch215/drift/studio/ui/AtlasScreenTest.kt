package dev.gmitch215.drift.studio.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
        waitUntil { onAllNodesWithText("Measuring this device").fetchSemanticsNodes().isEmpty() }
    }

    private fun wasm(v: AtlasView) =
        v.rows.first { it.id == boundaries }.cells.first { it.target == "wasm" }

    private fun view(version: String = "2.4.20") = AtlasView.of(AtlasState(dataset, version))

    @Test
    fun matrixRendersEveryProbeWithDivergenceMarked() = runComposeUiTest {
        show()
        val v = view()
        v.rows.forEach { onNodeWithTag("row:${it.id}").assertTextEquals(it.name) }
        assertEquals(
            v.rows.count { it.divergent },
            onAllNodesWithContentDescription("differs between targets").fetchSemanticsNodes().size,
        )
        onNodeWithTag("count")
            .assertTextEquals("32 of 38 probes differ between targets on kotlin 2.4.20")
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
        onNodeWithTag("home:atlas").performScrollTo().performClick()
        waitUntil { onAllNodesWithText("Measuring this device").fetchSemanticsNodes().isEmpty() }
        assertEquals(1, calls)
    }

    @Test
    fun studioAppStartsOnTheAtlasAndMeasuresWhenAsked() = runComposeUiTest {
        var calls = 0
        val counted = { d: Dataset ->
            calls++
            fixture(d)
        }
        setContent { StudioApp(dataset, measure = counted, startOnAtlas = true) }
        waitUntil { onAllNodesWithText("Measuring this device").fetchSemanticsNodes().isEmpty() }
        onNodeWithTag("count").assertExists()
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
        onNodeWithText("6 identical probes hidden").assertIsDisplayed()
        onNodeWithTag("filter:divergent").performClick()
        onNodeWithTag("row:kotlin.random.seeded").assertExists()
    }

    @Test
    fun detailLevelsAddTextOnly() = runComposeUiTest {
        show()
        val question = dataset.probe(boundaries)!!.question
        onNodeWithText(question).assertDoesNotExist()
        onNodeWithText("Detail").performClick()
        onNodeWithText(question).assertExists()
        onNodeWithText("Full").performClick()
        val cited = onAllNodesWithText("linked issues: KT-88414", substring = true)
        assertTrue(cited.fetchSemanticsNodes().isNotEmpty())
        onNodeWithText("Summary").performClick()
        onNodeWithText(question).assertDoesNotExist()
    }

    @Test
    fun tappingARowShowsTheFullResults() = runComposeUiTest {
        show()
        val row = onNodeWithTag("row:kotlin.collections.iterator-modification")
        onNodeWithText("Selected Probe").assertDoesNotExist()
        row.performScrollTo().performClick()
        onNodeWithText("Selected Probe").assertExists()
        onNodeWithText("jvm on kotlin 2.4.20:").assertExists()
        onNodeWithText("linked issues: KT-88775 (Open), KT-89031 (To be discussed)")
            .assertExists()
        row.performScrollTo().performClick()
        onNodeWithText("Selected Probe").assertDoesNotExist()
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
        onNodeWithTag("score").assertTextEquals("Score: 0 correct, 0 wrong, 0 skipped")
        val first = PredictView.of(PredictState(dataset))!!
        onNodeWithTag("prompt").assertTextEquals(first.prompt)
        onNodeWithText(first.question).assertExists()
        onNodeWithTag("choice:${first.choices.indexOfFirst { first.target in it.targets }}")
            .performScrollTo().performClick()
        onNodeWithTag("verdict").assertTextEquals("Correct")
        onNodeWithTag("score").assertTextEquals("Score: 1 correct, 0 wrong, 0 skipped")
        onNodeWithTag("next").performScrollTo().performClick()
        val second = PredictView.of(PredictState(dataset, position = 1))!!
        onNodeWithTag("prompt").assertTextEquals(second.prompt)
        onNodeWithTag("choice:unknown").performScrollTo().performClick()
        onNodeWithTag("verdict").assertTextEquals("Skipped")
        onNodeWithTag("score").assertTextEquals("Score: 1 correct, 0 wrong, 1 skipped")
    }

    @Test
    fun theAppOpensOnHomeAndTheTabsReachTheOtherScreens() = runComposeUiTest {
        setContent { StudioApp(dataset) }
        onNodeWithText("Welcome to Drift Studio").assertExists()
        onNodeWithText("Home").assertIsSelected()
        onNodeWithText("Atlas").assertIsNotSelected()
        onNodeWithText("Kotlin Across Targets").assertDoesNotExist()
        onNodeWithText("Atlas").performClick()
        onNodeWithText("Atlas").assertIsSelected()
        onNodeWithText("Kotlin Across Targets").assertExists()
        onNodeWithText("Investigation").performClick()
        onNodeWithText("Investigation").assertIsSelected()
        onNodeWithText("What Changed Between Two Runs").assertExists()
        onNodeWithText("Kotlin Across Targets").assertDoesNotExist()
    }

    @Test
    fun theAtlasExplainsProbesAndOffersPlainLabels() = runComposeUiTest {
        show()
        onNodeWithText(Glossary.PROBE).assertExists()
        onNodeWithText("Only Differences").assertExists()
        onNodeWithText("Compare Targets").assertIsSelected()
        onNodeWithTag("mode:predict").performClick()
        onNodeWithText("Guess the Result").assertIsSelected()
        onNodeWithText("Each question shows", substring = true).assertExists()
    }

    @Test
    fun aFailedMeasurementIsShownAndTheRecordedMatrixStays() = runComposeUiTest {
        setContent { AtlasScreen(dataset) { error("no engine") } }
        onNodeWithTag("device-error").assertTextEquals(
            "Could not measure this device: no engine. The recorded columns are unaffected.",
        )
        onNodeWithText("Measuring this device").assertDoesNotExist()
        onNodeWithTag("row:$boundaries").assertExists()
    }
}
