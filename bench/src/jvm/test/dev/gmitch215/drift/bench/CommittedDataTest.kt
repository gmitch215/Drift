package dev.gmitch215.drift.bench

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommittedDataTest {
    private val root = Path.of(".")
    private val work = Work(root)
    private val scenarios = work.loadScenarios()
    private val split = work.split()

    @Test
    fun theCommittedFilesEqualARegeneration() {
        val (text, splitText) = work.generate()
        assertEquals(text, root.resolve("data/scenarios.json").readText())
        assertEquals(splitText, root.resolve("data/split.json").readText())
    }

    @Test
    fun theSplitCommandReproducesTheCommittedSplit() {
        assertTrue(work.splitMatchesCommitted())
    }

    @Test
    fun theSplitCommandFailsWhenAScenarioIsMissing() {
        val copy = createTempDirectory("bench-split")
        try {
            copy.resolve("data").createDirectories()
            root.resolve("data/split.json").copyTo(copy.resolve("data/split.json"))
            copy.resolve("data/scenarios.json")
                .writeText(Generator.scenariosJson(scenarios.drop(1)))
            assertFalse(Work(copy).splitMatchesCommitted())
        } finally {
            copy.toFile().deleteRecursively()
        }
    }

    @Test
    fun theSealedTestListMatchesItsPinnedHash() {
        assertEquals(PINNED_TEST_SHA256, split.testSha256)
        val text = root.resolve("data/split.json").readText()
        val committed = CanonicalJson.parse(text) as JsonObject
        assertEquals(
            PINNED_TEST_SHA256,
            (committed.fields.getValue("test_sha256") as JsonString).value,
        )
    }

    @Test
    fun theSetIsTheRightSizeAndCoversEveryDimensionTwice() {
        assertTrue(scenarios.size >= 60, "${scenarios.size} scenarios")
        for (d in Dimensions.all) {
            assertTrue(scenarios.count { it.dimension == d } >= 2, "dimension $d")
        }
        assertEquals(
            setOf("single", "bundle", "decoy", "control", "intermittent"),
            scenarios.map { it.klass }.toSet(),
        )
        assertTrue(scenarios.any { !it.controllable })
    }

    @Test
    fun theDevShareIsBetweenFortyAndSixtyFivePercent() {
        val share = split.dev.size * 100 / scenarios.size
        assertTrue(share in 40..65, "dev share $share")
    }

    @Test
    fun everyLegacyTemplateHasADevInstanceAndAHeldOutOneHasNone() {
        val dev = split.dev.toSet()
        for ((template, group) in scenarios.groupBy { it.template }) {
            if (SplitRule.isHeldOut(template)) {
                assertTrue(group.none { it.id in dev }, "$template is held out")
            } else if (template in SplitRule.LEGACY_TEMPLATES) {
                assertTrue(group.any { it.id in dev }, "$template has no dev instance")
            }
        }
    }

    @Test
    fun everyDevScenarioHasAValidResultAndNoTestScenarioWasRun() {
        val dev = split.dev.toSet()
        for (s in scenarios) {
            val result = root.resolve("data/results/${s.id}.json")
            val capsule = root.resolve("data/capsules/${s.id}.red.json")
            if (s.id in dev) {
                val json = CanonicalJson.parse(result.readText()) as JsonObject
                assertEquals("true", CanonicalJson.encode(json.fields.getValue("valid")), s.id)
                assertTrue(capsule.exists(), "capsule of ${s.id}")
            } else if (work.unsealLines().isEmpty()) {
                assertTrue(!result.exists() && !capsule.exists(), "${s.id} is sealed")
            }
        }
    }

    @Test
    fun theProbedCapsulesAreTheDevScenariosOnly() {
        assertTrue(Lab.UNPROBED.all { it in split.dev })
        val dev = split.dev.toSet() - Lab.UNPROBED
        val files = root.resolve("data/probed").listDirectoryEntries().map { it.name }.toSet()
        assertEquals(dev.flatMap { listOf("$it.green.json", "$it.red.json") }.toSet(), files)
    }

    @Test
    fun droppedScenariosAreListedAndAbsent() {
        val ids = scenarios.map { it.id }.toSet()
        assertTrue(work.droppedIds().none { it in ids })
    }

    @Test
    fun everyScenarioHonoursItsOwnRate() {
        for (s in scenarios) {
            assertTrue(s.accepts(), s.id)
            assertEquals(0, s.green.rate, s.id)
            assertTrue(s.red.rate > 0, s.id)
        }
    }

    companion object {
        const val PINNED_TEST_SHA256 =
            "aea538143b9ca524864692d92e64c0ae95e0c5c42415cf4864be0236469f8b13"
    }
}
