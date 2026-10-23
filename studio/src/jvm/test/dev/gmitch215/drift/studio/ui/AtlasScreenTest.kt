package dev.gmitch215.drift.studio.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.gmitch215.drift.fixtures.AtlasFixtures
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.scan.atlas.ColumnKey
import dev.gmitch215.drift.scan.atlas.Dataset
import dev.gmitch215.drift.scan.atlas.Transcripts
import dev.gmitch215.drift.scan.probe.kotlin.KotlinCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AtlasScreenTest {
    private val dataset = AtlasData.dataset
    private val boundaries = "kotlin.double.tostring-boundaries"
    private val fixture = DeviceMeasure { d, _ ->
        Devices.of("this JVM", AtlasFixtures.text("macos.txt"), d)
    }

    private fun ComposeUiTest.show(measure: DeviceMeasure = fixture) {
        setContent { AtlasScreen(dataset, measure) }
        waitUntil(timeoutMillis = 30_000) {
            onAllNodesWithText("Measuring this device").fetchSemanticsNodes().isEmpty()
        }
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
        onNodeWithTag("badge:kotlin.regex.anchors-lines")
            .assertTextEquals("platform-defined")
    }

    @Test
    fun studioAppMeasuresThroughTheFunctionItIsGiven() = runComposeUiTest {
        var calls = 0
        val counted = DeviceMeasure { d, p ->
            calls++
            fixture.measure(d, p)
        }
        setContent { StudioApp(dataset, measure = counted) }
        onNodeWithTag("home:atlas").performScrollTo().performClick()
        waitUntil { onAllNodesWithText("Measuring this device").fetchSemanticsNodes().isEmpty() }
        assertEquals(1, calls)
    }

    @Test
    fun studioAppStartsOnTheAtlasAndMeasuresWhenAsked() = runComposeUiTest {
        var calls = 0
        val counted = DeviceMeasure { d, p ->
            calls++
            fixture.measure(d, p)
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
        val row = onNodeWithTag("row:kotlin.regex.anchors-lines")
        onNodeWithText("Selected Probe").assertDoesNotExist()
        row.performScrollTo().performClick()
        onNodeWithText("Selected Probe").assertExists()
        onNodeWithText("jvm on kotlin 2.4.20:").assertExists()
        onNodeWithText("linked issues: KT-82450 (Submitted)")
            .assertExists()
        row.performScrollTo().performClick()
        onNodeWithText("Selected Probe").assertDoesNotExist()
    }

    @Test
    fun thisDeviceColumnIsLiveAndLabelledWithACoarsePlatformId() = runComposeUiTest {
        show(measure = Devices.measurer("21"))
        val own = runBlocking { Devices.measure(dataset, runtime = "21") }
        onNodeWithTag("device-header").assertTextEquals(own.label)
        assertTrue(Regex("jvm-21(-[a-z]+)?").matches(own.label), own.label)
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
    fun measuringRunsEveryKotlinProbeWithoutTouchingProcessesFilesOrTheEnvironment() {
        val host = FakeHost("jvm", "linux", env = mapOf("USER" to "someone"))
        val seen = mutableListOf<Progress>()
        val device = runBlocking {
            Devices.measure(dataset, host, "21") { done, total -> seen += Progress(done, total) }
        }
        assertEquals("jvm-21-linux", device.label)
        assertEquals(KotlinCatalog.all.size, device.measured)
        assertEquals(KotlinCatalog.all.size, seen.size)
        assertEquals(Progress(1, 38), seen.first())
        assertEquals(Progress(38, 38), seen.last())
        assertTrue(host.ran.isEmpty())
        assertTrue("someone" !in device.transcript)
    }

    @Test
    fun runOnThisDeviceShowsProgressThenKeepsTheColumnAndAgreementMarks() = runComposeUiTest {
        val gates = mutableListOf<CompletableDeferred<Unit>>()
        val gated = DeviceMeasure { d, progress ->
            val gate = CompletableDeferred<Unit>().also { gates += it }
            progress(1, 3)
            gate.await()
            progress(3, 3)
            fixture.measure(d, progress)
        }
        setContent { AtlasScreen(dataset, gated) }
        waitUntil { onAllNodesWithText("1 of 3 probes run").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("run").assertDoesNotExist()
        gates[0].complete(Unit)
        waitUntil { onAllNodesWithTag("run").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("device-header").assertTextEquals("this JVM")
        val own = Devices.of("this JVM", AtlasFixtures.text("macos.txt"), dataset)
        val v = AtlasView.of(AtlasState(dataset, device = own))
        val same = v.rows.first { "macos" in it.agrees }
        val other = v.rows.first { it.differs.isNotEmpty() }
        onNodeWithTag("mark:${same.id}:macos")
            .assertContentDescriptionEquals("same as this device").assertTextEquals("=")
        onNodeWithTag("mark:${other.id}:${other.differs.first()}")
            .assertContentDescriptionEquals("differs from this device").assertTextEquals("!=")
        onNodeWithTag("run").performScrollTo().performClick()
        waitUntil { onAllNodesWithText("1 of 3 probes run").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("device-header").assertTextEquals("this JVM")
        onNodeWithTag("run").assertDoesNotExist()
        gates[1].complete(Unit)
        waitUntil { onAllNodesWithTag("run").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, gates.size)
    }

    @Test
    fun theActionAndTheColumnAreReachableAtPhoneWidth() = runComposeUiTest {
        setContent { Box(Modifier.width(360.dp)) { AtlasScreen(dataset, fixture) } }
        waitUntil { onAllNodesWithText("Measuring this device").fetchSemanticsNodes().isEmpty() }
        onNodeWithTag("run").performScrollTo().assertIsDisplayed()
        assertTrue(onNodeWithTag("run").getUnclippedBoundsInRoot().right <= 360.dp)
        onNodeWithTag("device-header").assertTextEquals("this JVM")
        onNodeWithTag("row:$boundaries").performScrollTo().assertIsDisplayed()
        onNodeWithTag("cell:$boundaries:device").assertIsDisplayed()
        onNodeWithTag("cell:$boundaries:ios").performScrollTo().assertIsDisplayed()
        onNodeWithTag("mark:$boundaries:ios").assertIsDisplayed()
        onNodeWithTag("export").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun savingHandsTheColumnTextToTheSaverAndReadsBackThroughTheDatasetReader() = runComposeUiTest {
        var saved: Pair<String, String>? = null
        setContent {
            AtlasScreen(dataset, Devices.measurer("21"), { name, text ->
                saved = name to text
                null
            })
        }
        waitUntil(timeoutMillis = 30_000) {
            onAllNodesWithTag("export-save").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("export-save").performScrollTo().performClick()
        val (name, text) = saved!!
        val column = Transcripts.read(name, text)
        assertEquals("${column.target}.txt", name)
        assertTrue(Regex("jvm-21(-[a-z]+)?").matches(column.target), column.target)
        assertEquals(KotlinCatalog.all.size, column.cells.size)
        val recorded = AtlasFixtures.names.filter { it.endsWith(".txt") }
            .map { Transcripts.read(it, AtlasFixtures.text(it)) }
        val joined = Dataset.build(recorded + column)
        assertTrue(ColumnKey(column.target, column.version) in joined.columns)
        onNodeWithTag("export-problem").assertDoesNotExist()
    }

    @Test
    fun aSaveThatFailsSaysWhy() = runComposeUiTest {
        setContent { AtlasScreen(dataset, fixture, { _, _ -> "disk full" }) }
        waitUntil { onAllNodesWithTag("export-save").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("export-save").performScrollTo().performClick()
        onNodeWithTag("export-problem")
            .assertTextEquals("Could not save the transcript: disk full")
    }

    @Test
    fun openingStudioToTheDeviceColumnTakesOneClickFromHome() = runComposeUiTest {
        setContent { StudioApp(dataset, measure = fixture) }
        onNodeWithTag("home:atlas").performScrollTo().performClick()
        waitUntil { onAllNodesWithText("Measuring this device").fetchSemanticsNodes().isEmpty() }
        onNodeWithTag("device-header").assertTextEquals("this JVM")
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
        setContent { AtlasScreen(dataset, { _, _ -> error("no engine") }) }
        onNodeWithTag("device-error").assertTextEquals(
            "Could not measure this device: no engine. The recorded columns are unaffected.",
        )
        onNodeWithText("Measuring this device").assertDoesNotExist()
        onNodeWithTag("row:$boundaries").assertExists()
    }
}
