package dev.gmitch215.drift

import dev.gmitch215.drift.history.HistoryState
import dev.gmitch215.drift.history.Repeat
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.ProbeResult
import dev.gmitch215.drift.model.ProbeStatus
import dev.gmitch215.drift.model.Run
import dev.gmitch215.drift.model.RunOutcome
import dev.gmitch215.drift.model.Sample
import dev.gmitch215.drift.model.Step
import dev.gmitch215.drift.model.StepConclusion
import dev.gmitch215.drift.rank.ProbeSymptoms
import dev.gmitch215.drift.rank.Ranker
import dev.gmitch215.drift.rank.Tier
import dev.gmitch215.drift.symptom.FailureSignature
import dev.gmitch215.drift.symptom.Performance
import dev.gmitch215.drift.symptom.Spread
import dev.gmitch215.drift.symptom.Symptom
import dev.gmitch215.drift.symptom.SymptomKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PerformanceTest {
    private fun run(
        wall: List<Long>,
        command: String = "make test",
        peak: List<Long?> = wall.map { null },
        exits: List<Int> = wall.map { 0 },
    ) = Run(
        "r",
        if (exits.all { it == 0 }) RunOutcome.PASS else RunOutcome.FAIL,
        listOf(Step(null, command, StepConclusion.SUCCESS, wall.first())),
        samples = wall.indices.map { Sample(exits[it], wall[it], null, null, peak[it]) },
    )

    private fun capsule(label: String, vararg attrs: Pair<String, String>) =
        Capsule(label, attrs.map { (path, value) -> Attribute(path, value, "test") })

    private val plain = capsule("a")

    private fun compare(first: Run?, second: Run?, a: Capsule = plain, b: Capsule = plain) =
        Performance.compare(first, second, a, b)

    private val fast = run(listOf(100, 100, 101, 102, 103))
    private val slow = run(listOf(249, 250, 250, 251, 252))

    @Test
    fun medianAndRatioUseIntegersOnly() {
        assertEquals(Spread(4, 1, 2, 4), Spread.of(listOf(4, 1, 3, 2)))
        assertEquals(Spread(3, 1, 2, 9), Spread.of(listOf(9, 1, 2)))
        assertNull(Spread.of(emptyList()))
        assertEquals(25L, Spread.tenths(250, 101))
        assertEquals(20L, Spread.tenths(2, 1))
        assertEquals(10L, Spread.tenths(100, 100))
        assertEquals(25L, Spread.tenths(5, 2))
        assertEquals("2.5", Spread.format(25))
        assertEquals("12.0", Spread.format(120))
        assertEquals(10L, Spread.tenths(1, 0))
    }

    @Test
    fun rangesThatDoNotOverlapGiveASlowerSymptomWithTheRatioOfTheMedians() {
        val result = compare(fast, slow)!!
        assertEquals(
            listOf(Symptom(SymptomKind.SLOWER, "2.5 times slower", 1)),
            result.symptoms,
        )
        assertTrue("the second run is 2.5 times slower than the first" in result.lines.first())
        assertTrue("the smallest second sample, 249 ms" in result.lines.first())
    }

    @Test
    fun overlappingRangesSayThereIsNoDifferenceAndGiveNoSymptom() {
        val noisy = run(listOf(98, 130, 101, 140, 120))
        val result = compare(fast, noisy)!!
        assertEquals(emptyList(), result.symptoms)
        assertEquals(
            "Wall time: no difference larger than the run to run spread " +
                "(first 100 to 103 ms, second 98 to 140 ms).",
            result.lines.first(),
        )
        val touching = run(listOf(103, 150))
        assertEquals(emptyList(), compare(fast, touching)!!.symptoms)
    }

    @Test
    fun aFirstRunThatIsSlowerGivesNoSymptomForTheSecond() {
        val result = compare(slow, fast)!!
        assertEquals(emptyList(), result.symptoms)
        assertTrue("the first run is 2.5 times slower than the second" in result.lines.first())
    }

    @Test
    fun runsWithoutSamplesOrWithOneSampleAreNotCompared() {
        assertNull(compare(Run("a", RunOutcome.PASS), slow))
        assertNull(compare(fast, null))
        val one = compare(run(listOf(100)), slow)!!
        assertEquals(emptyList(), one.symptoms)
        assertEquals(1, one.lines.size)
        assertTrue("at least 2 samples" in one.lines.single())
    }

    @Test
    fun peakMemoryGivesAMemorySymptomOnlyWhenBothSidesMeasuredIt() {
        val small = run(listOf(10, 11, 12), peak = listOf(1000L, 1010L, 1020L))
        val big = run(listOf(10, 11, 12), peak = listOf(2040L, 2050L, 2060L))
        val result = compare(small, big)!!
        assertEquals(
            listOf(Symptom(SymptomKind.MEMORY, "2.0 times more peak memory", 1)),
            result.symptoms,
        )
        assertTrue(result.lines.any { it.startsWith("Wall time: no difference") })
        assertTrue(result.lines.any { it.startsWith("Peak memory: the second run used 2.0") })
        val unmeasured = compare(small, run(listOf(10, 11, 12)))!!
        assertFalse(unmeasured.lines.any { it.startsWith("Peak memory") })
        assertEquals(emptyList(), unmeasured.symptoms)
        val partial = run(listOf(10, 11, 12), peak = listOf(2040L, null, null))
        assertFalse(compare(small, partial)!!.lines.any { it.startsWith("Peak memory") })
    }

    @Test
    fun limitsAppearOnlyWhenTheyApply() {
        val clean = compare(fast, slow)!!.lines
        assertEquals(1, clean.size, clean.toString())

        val hardware = compare(
            fast,
            slow,
            capsule("a", "cpu.count" to "4", "hw.cpu.model" to "M2", "os.arch" to "aarch64"),
            capsule("b", "cpu.count" to "12", "hw.cpu.model" to "Ryzen", "os.arch" to "aarch64"),
        )!!.lines.joinToString("\n")
        val different = "different hardware (hw.cpu.model M2 against Ryzen; cpu.count 4 against 12)"
        assertTrue(different in hardware, hardware)
        assertFalse("os.arch" in hardware)

        val few = compare(run(listOf(1, 2, 3)), run(listOf(9, 9, 9)))!!.lines
        assertTrue(few.any { "fewer than 5 samples on a side (first 3, second 3)" in it })
    }

    @Test
    fun aShortJvmRunGetsTheWarmUpLimit() {
        val jvm = run(listOf(100, 101, 102, 103, 104), command = "./gradlew test")
        assertTrue(compare(jvm, slow)!!.lines.any { "JVM" in it })
        val long = run(listOf(9000, 9001, 9002, 9003, 9004), command = "/usr/bin/java -jar a.jar")
        val longer = run(listOf(19000, 19001, 19002, 19003, 19004), command = "java -jar a.jar")
        assertFalse(compare(long, longer)!!.lines.any { "JVM" in it })
        assertFalse(compare(fast, slow)!!.lines.any { "JVM" in it })
    }

    @Test
    fun wasmInNodeAgainstABrowserGetsALimit() {
        val node = capsule("n", "runtime.platform" to "wasm", "kotlin.target" to "wasm")
        val browser = capsule("b", "runtime.platform" to "wasm", "browser.engine" to "chromium")
        val lines = compare(fast, slow, node, browser)!!.lines
        assertTrue(lines.any { "Node or a browser" in it })
        assertFalse(compare(fast, slow, node, node)!!.lines.any { "Node or a browser" in it })
        assertFalse(compare(fast, slow, plain, plain)!!.lines.any { "Node or a browser" in it })
    }

    @Test
    fun theReportRoundTripsThroughJson() {
        val json = compare(fast, slow)!!.toJson()
        assertEquals(setOf("lines", "symptoms", "ranges"), json.fields.keys)
    }

    @Test
    fun mixedOutcomesAreClassifiedByHistory() {
        val mixed = listOf(0, 1, 0, 0, 1, 0).map { Sample(it, 10) }
        assertEquals(HistoryState.FLAKY, Repeat.history("x", mixed).state)
        val lines = Repeat.summary("x", mixed)
        assertEquals("6 runs, 4 passed (67%), 2 failed; history state FLAKY", lines.first())
        assertEquals("wall time median 10 ms, min 10 ms, max 10 ms", lines[1])
        assertEquals(2, lines.size)
    }

    @Test
    fun aSingleChangeOfOutcomeIsNotCalledFlaky() {
        val samples = listOf(0, 0, 0, 1, 1).map { Sample(it, 10) }
        val line = Repeat.summary("x", samples).first()
        assertTrue("history state REGRESSION, one change of outcome" in line, line)
        assertTrue(Repeat.intermittent("The second run", "x", samples)!!.contains("REGRESSION"))
    }

    @Test
    fun uniformRunsHaveNoStateAndNoIntermittentNote() {
        val green = List(3) { Sample(0, 10 + it.toLong(), 5, 1, 2048) }
        val lines = Repeat.summary("x", green)
        assertEquals("3 runs, 3 passed (100%)", lines.first())
        assertEquals("wall time median 11 ms, min 10 ms, max 12 ms", lines[1])
        assertEquals("cpu time median 5 ms user, 1 ms system", lines[2])
        assertEquals("peak memory median 2048 KiB, min 2048 KiB, max 2048 KiB", lines[3])
        assertNull(Repeat.intermittent("The first run", "x", green))
        assertNull(Repeat.intermittent("The first run", "x", List(3) { Sample(2, 1) }))
        assertEquals(emptyList(), Repeat.summary("x", emptyList()))
    }

    @Test
    fun absentMeasurementsStayAbsent() {
        val some = listOf(Sample(0, 10, null, null, 100), Sample(0, 12), Sample(0, 14))
        val lines = Repeat.summary("x", some)
        assertEquals(3, lines.size)
        assertEquals(
            "peak memory median 100 KiB, min 100 KiB, max 100 KiB (1 of 3 runs measured)",
            lines[2],
        )
        assertEquals(33, Repeat.percent(1, 3))
        assertEquals(67, Repeat.percent(2, 3))
        assertEquals(50, Repeat.percent(1, 2))
    }

    @Test
    fun theSymptomsReachTheProbeThatReadsTheCpuLimitAndTheMemoryLimit() {
        val slower = FailureSignature(listOf(Symptom(SymptomKind.SLOWER, "2.5 times slower", 1)))
        val memory = FailureSignature(
            listOf(Symptom(SymptomKind.MEMORY, "2.0 times more peak memory", 1)),
        )
        assertTrue(ProbeSymptoms.explains("resources.cgroup-cpu", slower))
        assertFalse(ProbeSymptoms.explains("resources.cgroup-memory", slower))
        assertTrue(ProbeSymptoms.explains("resources.cgroup-memory", memory))
        assertFalse(ProbeSymptoms.explains("process.timezone", slower))
    }

    @Test
    fun aSlowerSymptomPutsTheCpuLimitAheadOfADifferenceThatDoesNotExplainIt() {
        fun capture(label: String, cpu: String, zone: String, java: String) = Capsule(
            label,
            listOf(
                Attribute("cgroup.cpu.max", cpu, "test"),
                Attribute("env.TZ", zone, "test"),
                Attribute("tool.java.version", java, "test"),
            ),
            listOf(
                ProbeResult("resources.cgroup-cpu", ProbeStatus.OK, "cpus=$cpu"),
                ProbeResult("process.timezone", ProbeStatus.OK, zone),
            ),
        )
        val green = capture("green", "max 100000", "UTC", "17")
        val red = capture("red", "100000 100000", "Asia/Tokyo", "21")
        val before = Ranker.rank(green, red, emptyList()).candidates.associateBy { it.path }
        assertEquals(Tier.PLAUSIBLE, before.getValue("env.TZ").tier)
        val symptom = Symptom(SymptomKind.SLOWER, "2.5 times slower", 1)
        val after = Ranker.rank(green, red, emptyList(), listOf(symptom)).candidates
        assertEquals("cgroup.cpu.max", after.first().path)
        assertEquals(Tier.PLAUSIBLE, after.first().tier)
        assertEquals(Tier.CORRELATED, after.first { it.path == "env.TZ" }.tier)
        assertEquals(Tier.DIFFERENCE, after.first { it.path == "tool.java.version" }.tier)
    }
}
