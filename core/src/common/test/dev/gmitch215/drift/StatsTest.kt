package dev.gmitch215.drift

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.CgroupDelta
import dev.gmitch215.drift.model.Run
import dev.gmitch215.drift.model.RunOutcome
import dev.gmitch215.drift.model.Sample
import dev.gmitch215.drift.model.Step
import dev.gmitch215.drift.model.StepConclusion
import dev.gmitch215.drift.stats.Bootstrap
import dev.gmitch215.drift.stats.Cgroup
import dev.gmitch215.drift.stats.CliffsDelta
import dev.gmitch215.drift.stats.MannWhitney
import dev.gmitch215.drift.stats.MetricComparison
import dev.gmitch215.drift.stats.RatioInterval
import dev.gmitch215.drift.stats.RunComparison
import dev.gmitch215.drift.symptom.Performance
import dev.gmitch215.drift.symptom.Spread
import dev.gmitch215.drift.symptom.SymptomKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatsTest {
    private val fast = listOf(100L, 100, 101, 102, 103)
    private val slow = listOf(249L, 250, 250, 251, 252)

    private fun mw(a: List<Long>, b: List<Long>) = MannWhitney.of(a, b)

    @Test
    fun completeSeparationGivesTheSmallestPossibleExactP() {
        val three = mw(listOf(1, 2, 3), listOf(4, 5, 6))
        assertEquals(9L, three.greater)
        assertEquals(0L, three.twoU)
        assertEquals(1000L, three.delta)
        val p3 = three.exact!!
        assertEquals(Triple(2L, 20L, 2L), Triple(p3.extreme, p3.total, p3.smallest))
        assertEquals("0.1000", p3.format())
        assertFalse(p3.significant)
        assertTrue(p3.unreachable)

        val five = mw((1L..5).toList(), (6L..10).toList()).exact!!
        assertEquals(Triple(2L, 252L, 2L), Triple(five.extreme, five.total, five.smallest))
        assertEquals("0.0079", five.format())
        assertTrue(five.significant)
        assertFalse(five.unreachable)

        val ten = mw((1L..10).toList(), (11L..20).toList()).exact!!
        assertEquals(184_756L, ten.total)
        assertEquals("below 0.0001", ten.format())
    }

    @Test
    fun tiesTakeTheMidrankOfTheirGroup() {
        assertEquals(
            listOf(2L, 6, 6, 6, 11, 11),
            MannWhitney.doubledMidranks(listOf(1, 2, 2, 2, 3, 3)),
        )
        val tied = mw(listOf(1, 2, 2), listOf(2, 3, 3))
        assertEquals(Triple(7L, 0L, 2L), Triple(tied.greater, tied.less, tied.equal))
        assertEquals(16L, tied.twoSecondU)
        assertEquals(2L, tied.twoU)
        assertEquals(778L, tied.delta)
        val p = tied.exact!!
        assertEquals(Triple(6L, 20L, 6L), Triple(p.extreme, p.total, p.smallest))
        assertEquals("0.3000", p.format())
    }

    @Test
    fun identicalSamplesHaveAPValueOfOne() {
        val same = mw(listOf(1, 2, 3, 4), listOf(1, 2, 3, 4))
        assertEquals(0L, same.delta)
        assertEquals(16L, same.twoSecondU)
        val p = same.exact!!
        assertEquals(p.total, p.extreme)
        assertEquals("1.0000", p.format())
        assertFalse(p.significant)
    }

    private fun bruteForce(a: List<Long>, b: List<Long>): Pair<Long, Long> {
        val pooled = a + b
        fun doubledRank(v: Long) = 2 * pooled.count { it < v } + pooled.count { it == v } + 1L
        val ranks = pooled.map(::doubledRank)
        val m = b.size
        val mean = m.toLong() * (pooled.size + 1)
        val observed = kotlin.math.abs(b.sumOf(::doubledRank) - mean)
        var extreme = 0L
        var total = 0L
        for (mask in 0 until (1 shl pooled.size)) {
            if (mask.countOneBits() != m) continue
            val sum = pooled.indices.filter { mask and (1 shl it) != 0 }.sumOf { ranks[it] }
            total++
            if (kotlin.math.abs(sum - mean) >= observed) extreme++
        }
        return extreme to total
    }

    @Test
    fun theCountingMatchesABruteForceOverEverySplit() {
        val cases = listOf(
            listOf(1L, 2, 3) to listOf(4L, 5, 6),
            listOf(1L, 2, 2) to listOf(2L, 3, 3),
            listOf(5L, 5, 5, 5) to listOf(5L, 5, 5),
            listOf(3L, 1, 4, 1, 5) to listOf(9L, 2, 6, 5, 3),
            listOf(10L, 12, 11, 15) to listOf(11L, 14, 12, 16, 10, 12),
            listOf(7L) to listOf(7L, 8, 9, 7),
            listOf(100L, 100, 101, 102, 103) to listOf(98L, 130, 101, 140, 120),
        )
        for ((a, b) in cases) {
            val exact = mw(a, b).exact!!
            val (extreme, total) = bruteForce(a, b)
            assertEquals(extreme to total, exact.extreme to exact.total, "$a against $b")
        }
    }

    @Test
    fun theExactTestRunsToThirtySamplesAndNeverApproximates() {
        val thirty = mw((1L..30).toList(), (31L..60).toList()).exact!!
        assertEquals(118_264_581_564_861_424L, thirty.total)
        assertEquals(2L, thirty.extreme)
        assertNull(mw((1L..31).toList(), (32L..60).toList()).exact)
        assertNull(mw((1L..30).toList(), (31L..61).toList()).exact)
        assertNull(mw(emptyList(), listOf(1)).exact)
    }

    @Test
    fun cliffsDeltaUsesThePublishedThresholds() {
        assertEquals("negligible", CliffsDelta.label(146))
        assertEquals("small", CliffsDelta.label(147))
        assertEquals("small", CliffsDelta.label(329))
        assertEquals("medium", CliffsDelta.label(330))
        assertEquals("medium", CliffsDelta.label(473))
        assertEquals("large", CliffsDelta.label(474))
        assertEquals("large", CliffsDelta.label(-1000))
        assertEquals("negligible", CliffsDelta.label(-100))
        assertEquals(-778L, mw(listOf(2, 3, 3), listOf(1, 2, 2)).delta)
        assertEquals(0L, CliffsDelta.permille(0, 0, 0))
        assertEquals(333L, CliffsDelta.permille(2, 1, 3))
        assertEquals(-333L, CliffsDelta.permille(1, 2, 3))
    }

    @Test
    fun theBootstrapMatchesAnIndependentImplementation() {
        assertEquals(RatioInterval(2427, 2510), Bootstrap.ratio(fast, slow))
        assertEquals(RatioInterval(1333, 6000), Bootstrap.ratio(listOf(1, 2, 3), listOf(4, 5, 6)))
        val a = listOf(10L, 12, 11, 15, 9, 10, 13)
        val b = listOf(11L, 14, 12, 16, 10, 12, 15, 13)
        assertEquals(RatioInterval(917, 1400), Bootstrap.ratio(a, b))
        val flat = Bootstrap.ratio(listOf(100, 100, 100), listOf(250, 250, 250))
        assertEquals(RatioInterval(2500, 2500), flat)
        assertEquals(-2858710155271045367L, Bootstrap.seed(fast, slow))
    }

    @Test
    fun shufflingASideChangesNothing() {
        val shuffled = listOf(103L, 100, 102, 100, 101)
        val other = listOf(252L, 249, 251, 250, 250)
        assertEquals(Bootstrap.seed(fast, slow), Bootstrap.seed(shuffled, other))
        assertEquals(Bootstrap.ratio(fast, slow), Bootstrap.ratio(shuffled, other))
        assertEquals(mw(fast, slow), mw(shuffled, other))
        assertEquals(Bootstrap.ratio(fast, slow), Bootstrap.ratio(fast, slow))
    }

    @Test
    fun theIntervalFormatsInTenthsAndSaysWhenItLeavesOne() {
        assertEquals("2.4 to 2.5", RatioInterval(2427, 2510).format())
        assertTrue(RatioInterval(1001, 1200).excludesOne)
        assertTrue(RatioInterval(800, 999).excludesOne)
        assertFalse(RatioInterval(900, 1100).excludesOne)
        assertFalse(RatioInterval(1000, 1100).excludesOne)
    }

    @Test
    fun aClearGapGivesADirectionAndAPlainSentence() {
        val c = MetricComparison.of(MetricComparison.WALL, fast, slow)!!
        assertEquals(1, c.direction)
        assertEquals(
            "B is 2.5 times slower than A; exact p 0.0079; " +
                "the interval of the ratio of B to A is 2.4 to 2.5.",
            c.verdict(),
        )
        assertEquals(
            "Wall time (ms): A 5 samples, B 5 samples\n" +
                "  A: min 100, median 101, max 103\n" +
                "  B: min 249, median 250, max 252\n" +
                "  ratio of medians, B over A: 2.5\n" +
                "  Mann-Whitney U 0; exact p 0.0079\n" +
                "  Cliff's delta +1000 permille (large; positive means B is larger)\n" +
                "  bootstrap interval of the ratio, 2000 resamples, 95 percent: 2.4 to 2.5\n" +
                "  ${c.verdict()}",
            c.lines().joinToString("\n"),
        )
        val back = MetricComparison.of(MetricComparison.WALL, slow, fast)!!
        assertEquals(-1, back.direction)
        assertTrue(back.verdict().startsWith("A is 2.5 times slower than B; exact p 0.0079"))
        val memory = MetricComparison.of(
            MetricComparison.MEMORY,
            listOf(1000, 1010, 1020, 1030),
            listOf(2040L, 2050, 2060, 2070),
        )!!
        assertTrue(memory.verdict().startsWith("B used 2.0 times more peak memory than A; "))
    }

    @Test
    fun aShiftUnderTenPercentIsNamedInPercentNotInTimes() {
        val a = listOf(1000L, 1001, 1002, 1003, 1004, 1005)
        val b = listOf(1009L, 1010, 1011, 1012, 1013, 1014)
        val c = MetricComparison.of(MetricComparison.WALL, a, b)!!
        assertEquals(1, c.direction)
        val slower = "B is 0.9 percent slower than A; exact p 0.0022"
        assertTrue(c.verdict().startsWith(slower), c.verdict())
        val memory = MetricComparison.of(MetricComparison.MEMORY, b, a)!!
        assertTrue(memory.verdict().startsWith("A used 0.9 percent more peak memory than B;"))
    }

    @Test
    fun overlapAndTooFewSamplesGiveNoDifference() {
        val same = MetricComparison.of(MetricComparison.WALL, fast, fast)!!
        assertEquals(0, same.direction)
        assertTrue(
            same.verdict()
                .startsWith("No difference larger than the run to run spread (exact p 1.0000;"),
        )
        val three = MetricComparison.of(
            MetricComparison.WALL,
            listOf(10, 11, 12),
            listOf(20L, 21, 22),
        )!!
        assertEquals(0, three.direction)
        val lowest = "with 3 and 3 samples the lowest possible exact p is 0.1000, " +
            "so more samples are needed"
        assertTrue(lowest in three.verdict(), three.verdict())
        assertNull(MetricComparison.of(MetricComparison.WALL, listOf(1), fast))
    }

    @Test
    fun aSideAboveThirtySamplesSaysTheExactTestWasNotComputed() {
        val a = List(31) { 100L + it % 3 }
        val b = List(31) { 300L + it % 3 }
        val c = MetricComparison.of(MetricComparison.WALL, a, b)!!
        assertNull(c.test.exact)
        assertEquals(1, c.direction)
        assertTrue("exact test not computed above 30 samples per side" in c.verdict(), c.verdict())
        assertTrue("exact test not computed above 30 samples per side" in c.lines()[4])
        assertEquals(CanonicalJson.encode(c.toJson()), CanonicalJson.encode(c.toJson()))
        assertTrue("\"exactP\":null" in CanonicalJson.encode(c.toJson()))
    }

    private fun run(vararg samples: Sample, command: String = "make test") = Run(
        "r",
        RunOutcome.PASS,
        listOf(Step(null, command, StepConclusion.SUCCESS, 1)),
        samples = samples.toList(),
    )

    private fun capsule(label: String) = Capsule(label, listOf(Attribute("cpu.count", "4", "t")))

    @Test
    fun diagnoseCarriesTheRangesOfBothRunsForAChart() {
        fun timed(walls: List<Long>, peak: Long? = null) =
            run(*walls.map { Sample(0, it, peakKb = peak) }.toTypedArray())

        val result = Performance.compare(timed(fast), timed(slow), capsule("a"), capsule("b"))!!
        assertEquals(listOf("Wall time"), result.ranges.map { it.name })
        assertEquals(Spread(5, 100, 101, 103), result.ranges.single().first)
        assertEquals(Spread(5, 249, 250, 252), result.ranges.single().second)
        val json = CanonicalJson.encode(result.toJson())
        assertTrue("\"ranges\":[{\"first\":{\"count\":5,\"max\":103" in json, json)
        val both = Performance.compare(
            timed(fast, 1000),
            timed(slow, 2000),
            capsule("a"),
            capsule("b"),
        )!!
        assertEquals(listOf("Wall time", "Peak memory"), both.ranges.map { it.name })
        assertEquals("KiB", both.ranges.last().unit)
        assertFalse(
            "ranges" in CanonicalJson.encode(Performance(listOf("x"), emptyList()).toJson()),
        )
    }

    private fun fires(a: List<Long>, b: List<Long>): Pair<Boolean, Boolean> {
        val ruleSaysSlower = Performance.compare(
            run(*a.map { Sample(0, it) }.toTypedArray()),
            run(*b.map { Sample(0, it) }.toTypedArray()),
            capsule("a"),
            capsule("b"),
        )!!.symptoms.any { it.kind == SymptomKind.SLOWER }
        return ruleSaysSlower to (MetricComparison.of(MetricComparison.WALL, a, b)!!.direction == 1)
    }

    @Test
    fun theStatisticsAgreeWithTheOverlapRuleExceptWhereTheSamplesAreFewOrTheShiftIsSmall() {
        val noisy = listOf(98L, 130, 101, 140, 120)
        val tenA = (100L..109).toList()
        val tenB = (105L..114).toList()
        val agree = listOf(
            Triple("clear gap, 5 each", fast to slow, true to true),
            Triple("first run slower", slow to fast, false to false),
            Triple("noisy overlap", fast to noisy, false to false),
            Triple("two against five", fast to listOf(103L, 150), false to false),
            Triple("same data", fast to fast, false to false),
        )
        for ((name, pair, expected) in agree) {
            assertEquals(expected, fires(pair.first, pair.second), name)
        }
        assertEquals(true to false, fires(listOf(1, 2, 3), listOf(9, 9, 9)), "3 each, separated")
        assertEquals(true to false, fires(listOf(10, 11, 12), listOf(20, 21, 22)), "3 each")
        assertEquals(false to true, fires(tenA, tenB), "5 percent shift, 10 each")
    }

    @Test
    fun cpuTimeAndPeakMemoryAreComparedOnlyWhereBothSidesCarryThem() {
        val a = run(*List(5) { Sample(0, 100L + it, 50, 10, 1000L + it) }.toTypedArray())
        val b = run(*List(5) { Sample(0, 100L + it, 150, 30, 3000L + it) }.toTypedArray())
        val both = RunComparison.of(a, b, capsule("a"), capsule("b"))!!
        val names = both.metrics.map { it.metric.name }
        assertEquals(listOf("Wall time", "CPU time", "Peak memory"), names)
        assertEquals(0, both.metrics[0].direction)
        assertTrue(both.metrics[1].verdict().startsWith("B used 3.0 times more CPU time than A"))
        val idle = run(*List(5) { Sample(0, 100L + it, 0, 0, 1000L + it) }.toTypedArray())
        val idleToo = run(*List(5) { Sample(0, 100L + it, 0, 0, 2000L + it) }.toTypedArray())
        val skipped = RunComparison.of(idle, idleToo, capsule("a"), capsule("b"))!!
        assertEquals(listOf("Wall time", "Peak memory"), skipped.metrics.map { it.metric.name })
        val wallOnly = run(*List(5) { Sample(0, 100L + it) }.toTypedArray())
        val half = RunComparison.of(a, wallOnly, capsule("a"), capsule("b"))!!
        assertEquals(listOf("Wall time"), half.metrics.map { it.metric.name })
        val sparse = run(Sample(0, 100, 5, 5, 10), Sample(0, 101), Sample(0, 102), Sample(0, 103))
        assertEquals(1, RunComparison.of(a, sparse, capsule("a"), capsule("b"))!!.metrics.size)
        assertNull(RunComparison.of(run(Sample(0, 1)), b, capsule("a"), capsule("b")))
    }

    @Test
    fun theLimitsAreTheOnesDiagnosePrints() {
        val a = run(*List(3) { Sample(0, 100L + it) }.toTypedArray(), command = "./gradlew test")
        val b = run(*List(3) { Sample(0, 900L + it) }.toTypedArray())
        val lines = RunComparison.of(a, b, capsule("a"), capsule("b"))!!.limits
        val wallA = Spread.of(a.samples.map { it.wallMs })!!
        val wallB = Spread.of(b.samples.map { it.wallMs })!!
        assertEquals(Performance.limits(a, b, wallA, wallB, capsule("a"), capsule("b")), lines)
        assertTrue(lines.any { "fewer than 5 samples" in it })
        assertTrue(lines.any { "JVM" in it })
    }

    private fun throttled(n: Long?, max: Long? = null, kill: Long? = null) =
        Sample(0, 10, cgroup = CgroupDelta(n, max, null, kill))

    @Test
    fun cgroupCountersSayWhichSideWasThrottledAndNothingWhenTheyAgree() {
        val calm = List(3) { throttled(0) }
        val busy = listOf(throttled(4), throttled(3), throttled(5))
        assertEquals(
            listOf("CPU throttled 12 times in B, none in A.", Cgroup.CHECK_FIRST),
            Cgroup.lines(calm, busy),
        )
        assertEquals(
            listOf("CPU throttled 12 times in A, none in B.", Cgroup.CHECK_FIRST),
            Cgroup.lines(busy, calm),
        )
        assertEquals(
            listOf("CPU throttled 12 times in A and 1 time in B.", Cgroup.CHECK_FIRST),
            Cgroup.lines(busy, listOf(throttled(1), throttled(0), throttled(0))),
        )
        assertEquals(emptyList(), Cgroup.lines(calm, calm))
        assertEquals(emptyList(), Cgroup.lines(busy, busy))
        assertEquals(emptyList(), Cgroup.lines(List(3) { Sample(0, 1) }, List(3) { Sample(0, 1) }))
        assertEquals(
            listOf("Only B has cgroup counters, so throttling cannot be compared."),
            Cgroup.lines(List(3) { Sample(0, 1) }, busy),
        )
        assertEquals(
            listOf(
                "Memory reached its cgroup limit 2 times in B, none in A.",
                "The kernel killed a process for memory 1 time in B, none in A.",
            ),
            Cgroup.lines(List(3) { throttled(0, 0, 0) }, listOf(throttled(0, 2, 1))),
        )
        assertEquals(emptyList(), Cgroup.lines(calm, listOf(throttled(0, 2, 1))))
    }

    @Test
    fun cgroupFieldsRoundTripAndStayAbsentWhenUnread() {
        val sample = Sample(0, 12, 5, 2, 900, CgroupDelta(throttled = 3, oomKill = 0))
        assertEquals(sample, Sample.fromJson(sample.toJson()))
        val json = CanonicalJson.encode(sample.toJson())
        assertTrue("\"cgroup\":{\"oomKill\":0,\"throttled\":3}" in json, json)
        val plain = Sample(0, 12)
        assertFalse("cgroup" in CanonicalJson.encode(plain.toJson()))
        assertNull(Sample.fromJson(plain.toJson()).cgroup)
        assertNotNull(Sample.fromJson(sample.toJson()).cgroup)
    }
}
